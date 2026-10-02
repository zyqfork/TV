"""Installed TV UI regression, on an already-playing OWNED bare 4K fixture.

Uses a loopback-only HTTP server/ADB reverse to delay optional subtitles. Sends
real D-pad center/back plus accessibility clicks for track-close. Never installs,
launches user media, changes source/engine/decode settings or clears history.
Requires --allow-control and home-pointer.jar/native-click.jar. Not physical-key,
HDR, high-bitrate or all-source validation.
"""
import argparse
import http.server
import importlib.util
import re
import shlex
import subprocess
import threading
import time
import urllib.parse
import urllib.request
from pathlib import Path
from PIL import Image

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--device',required=True)
p.add_argument('--out',type=Path,required=True)
p.add_argument('--allow-control',action='store_true')
p.add_argument('--owned-media',required=True)
p.add_argument('--action-port',type=int,default=19983)
p.add_argument('--subtitle-port',type=int,default=19985)
a=p.parse_args()
assert a.allow_control and '/freeze-subtitles-owned/' in a.owned_media
s=importlib.util.spec_from_file_location('h',Path(__file__).with_name('test_mpv_native_overlay.py'))
h=importlib.util.module_from_spec(s);s.loader.exec_module(h)
h.D=['adb','-s',a.device];h.OUT=a.out;h.OUT.mkdir(parents=True,exist_ok=True)
started={name:threading.Event() for name in ['slow.srt','close.srt','exit.srt']}
finished={name:threading.Event() for name in started}
server=None
owned_forward=False
owned_reverse=False
previous={}
pid=h.sh('pidof com.fongmi.android.tv').strip()
since=h.sh("date '+%m-%d %H:%M:%S.000'").strip()

def logs(name):
    text=subprocess.check_output(h.D+['logcat','-d','-T',since,'-v','threadtime']).decode('utf8','replace')
    (h.OUT/(name+'.log')).write_text(text,encoding='utf8');return text

def state():
    text=h.sh('dumpsys media_session')
    match=re.search(r'androidx\.media3\.session\.id\. com\.fongmi\.android\.tv/[^\n]+\n(.*?)(?=\n    \S|\nAudio playback|\Z)',text,re.S)
    assert match,'No primary media session'
    value=re.search(r'state=PlaybackState \{state=(\d+)',match.group(1))
    assert value;return int(value.group(1))

def wait(check,seconds,message):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        if check():return
        time.sleep(.1)
    raise AssertionError(message)

def node(root,id):return next((n for n in root.iter('node') if n.get('resource-id')=='com.fongmi.android.tv:id/'+id),None)
def key(code):h.sh('input keyevent '+str(code));time.sleep(.15)
def act(id):
    h.sh('CLASSPATH=/data/local/tmp/native-click.jar app_process /system/bin AccessibilityClickProbe com.fongmi.android.tv:id/'+id)
    time.sleep(.25)
def submit(name):
    params=urllib.parse.urlencode({'do':'refresh','type':'subtitle','path':f'http://127.0.0.1:{a.subtitle_port}/{name}'})
    assert urllib.request.urlopen(f'http://127.0.0.1:{a.action_port}/action?'+params,timeout=5).read()==b'OK'
    assert started[name].wait(5),'Subtitle HTTP not started'
def snap_image(name,caption):
    data=subprocess.check_output(h.D+['exec-out','screencap','-p']);path=h.OUT/(name+'.png');path.write_bytes(data)
    im=Image.open(path).convert('RGB')
    # Independent video ROI inside 4096x1746 FIT content, above caption/letterbox.
    video=im.crop((200,180,1720,650));caption_image=im.crop((300,760,1620,1070))
    colored=sum(1 for r,g,b in getattr(video,'get_flattened_data',video.getdata)() if max(r,g,b)>160 and min(r,g,b)<100)
    white=sum(1 for r,g,b in getattr(caption_image,'get_flattened_data',caption_image.getdata)() if min(r,g,b)>200)
    assert colored>1000,(name,'video hidden',colored)
    assert (white>500 if caption else white==0),(name,'caption pixels',white)
    print('RGB video / caption pixels',name,colored,white,flush=True)
def top():
    return next(l for l in h.sh('dumpsys activity activities').splitlines() if 'topResumedActivity=' in l)

class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        name=self.path.lstrip('/')
        if name not in started:self.send_error(404);return
        started[name].set()
        time.sleep(25 if name=='slow.srt' else 15)
        body=b'1\n00:00:00,000 --> 00:10:00,000\nASYNC APP DIALOGUE\n\n'
        try:
            self.send_response(200);self.send_header('Content-Length',str(len(body)));self.send_header('Connection','close');self.end_headers();self.wfile.write(body)
        except (BrokenPipeError,ConnectionResetError,ConnectionAbortedError):pass
        finally:finished[name].set()
    def log_message(self,*args):pass

try:
    assert '.ui.activity.VideoActivity' in top()
    initial=h.snap('preflight')
    assert node(initial,'video') is not None and node(initial,'video').get('bounds')=='[0,0][1920,1080]'
    assert state()==3 and Path(a.owned_media).name in h.sh('dumpsys media_session')
    old=h.sh('logcat -d -s mpv:V')
    # Automatic direct output is live-only; VOD subtitles render through mpv GPU/libass.
    assert 'freeze-subtitles-owned/' in old and 'VO: [gpu]' in old
    # Do not remove someone else's forwarding rules.
    forwards=subprocess.check_output(h.D+['forward','--list']).decode()
    reverses=subprocess.check_output(h.D+['reverse','--list']).decode()
    assert f'tcp:{a.action_port}' not in forwards and f'tcp:{a.subtitle_port}' not in reverses
    server=http.server.ThreadingHTTPServer(('127.0.0.1',a.subtitle_port),Handler)
    threading.Thread(target=server.serve_forever,daemon=True).start()
    for tag in ['MpvSubtitle','SubtitleResources']:
        previous[tag]=h.sh('getprop log.tag.'+tag).strip();h.sh('setprop log.tag.'+tag+' DEBUG')
    subprocess.run(h.D+['forward',f'tcp:{a.action_port}','tcp:9978'],check=True);owned_forward=True
    subprocess.run(h.D+['reverse',f'tcp:{a.subtitle_port}',f'tcp:{a.subtitle_port}'],check=True);owned_reverse=True
    root=h.snap('before-import')
    if node(root,'decode') is not None:key(4)
    submit('slow.srt')
    latency=[]
    for cycle in range(3):
        assert not finished['slow.srt'].is_set(),'Slow request ended before key probe'
        before=time.monotonic();key(23);wait(lambda:state()==2,2,'pause unresponsive')
        latency.append(time.monotonic()-before)
        root=h.snap('paused-'+str(cycle));button=node(root,'play_pause')
        assert button is not None and button.get('focused')=='true' and button.get('text') in {'播放','Play'}
        before=time.monotonic();key(23);wait(lambda:state()==3,2,'resume unresponsive')
        latency.append(time.monotonic()-before)
        root=h.snap('resumed-'+str(cycle));assert node(root,'play_pause') is None
    assert not finished['slow.srt'].is_set(),'No pending HTTP evidence'
    print('PASS installed 4K VideoActivity: three real center pause/resume cycles DURING delayed HTTP; observed seconds=',latency,flush=True)
    assert finished['slow.srt'].wait(30)
    wait(lambda:'Track added:' in logs('subtitle-ready') and "'slow.srt'" in logs('subtitle-ready'),8,'external subtitle track missing')
    root=h.snap('caption-controls')
    if node(root,'decode') is not None:key(4)
    snap_image('subtitle-visible',True)
    assert 'VO: [mediacodec_embed]' not in logs('subtitle-visible')
    print('PASS installed delayed SRT finishes / selected GPU/libass / visible caption and independent 4K video',flush=True)
    submit('close.srt')
    key(20);act('text');act('closeSubtitle')
    root=h.snap('closed')
    if node(root,'decode') is not None:key(4)
    snap_image('closed',False)
    assert finished['close.srt'].wait(18)
    snap_image('closed-after-old-response',False)
    assert 'disabled=true' in logs('closed-after-old-response')
    print('PASS installed close cancels pending import / releases captions / delayed response cannot reopen TEXT',flush=True)
    submit('exit.srt')
    before=time.monotonic()
    for _ in range(4):
        if '.ui.activity.VideoActivity' not in top():break
        key(4)
    wait(lambda:'.ui.activity.HomeActivity' in top(),3,'Back cannot exit while subtitle HTTP pending')
    elapsed=time.monotonic()-before
    assert not finished['exit.srt'].is_set(),'No pending HTTP during exit'
    assert finished['exit.srt'].wait(18)
    assert h.sh('pidof com.fongmi.android.tv').strip()==pid,'App process restarted'
    recent=logs('final-runtime')
    app_lines=[l for l in recent.splitlines() if re.search(r'\s'+re.escape(pid)+r'\s+\d+\s',l)]
    assert not any('FATAL EXCEPTION' in l or 'Fatal signal' in l for l in app_lines)
    assert not any('ANR in com.fongmi.android.tv' in l for l in recent.splitlines())
    print('PASS installed Back exit during pending HTTP / stable process / no new ANR; exit seconds=',elapsed,flush=True)
finally:
    for tag,value in previous.items():h.sh('setprop log.tag.'+tag+' '+shlex.quote(value))
    if owned_forward:subprocess.run(h.D+['forward','--remove',f'tcp:{a.action_port}'],check=False)
    if owned_reverse:subprocess.run(h.D+['reverse','--remove',f'tcp:{a.subtitle_port}'],check=False)
    if server:server.shutdown();server.server_close()
    h.sh('rm -f /sdcard/native-tv-verify.xml')
