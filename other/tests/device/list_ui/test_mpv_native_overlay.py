#!/usr/bin/env python3
"""Native bitmap overlay TV smoke; real decoding + RGB video screenshots, not a physical pointer test.
Requires home-pointer.jar and <media-dir>/tv-native-ui-{ass,pgs}.mkv fixtures:
1280x720 RGB bars for 120s, subtitles 0-8 / gap 8-11 / 11-120.
ASS also changes its cue at 22s. Evidence is device-specific, not an HDR/power benchmark.
"""
import argparse,subprocess,time,re,urllib.request,xml.etree.ElementTree as E,shlex
from pathlib import Path
from PIL import Image
D=[];OUT=None;PORT=19978;MEDIA_DIR='/sdcard';OWNED=[]
def sh(s):return subprocess.check_output(D+['shell',s],stderr=subprocess.STDOUT).decode('utf8')
def logs(name):
 s=subprocess.check_output(D+['logcat','-d','-v','time','-s','mpv:V','MpvPlayer:I','MpvSubtitle:D']).decode('utf8');(OUT/(name+'.log')).write_text(s,encoding='utf8');return s
def cue(s):return [(int(n),float(t),int(ep)) for n,t,ep in re.findall(r'native bitmap cues=(\d+) pts=([^ ]+) epoch=(\d+)',s)]
def vo(s):return re.findall(r'VO: \[([^]]+)\]',s)[-1]
def ctrl(t):assert urllib.request.urlopen(f'http://127.0.0.1:{PORT}/action?do=control&type='+t,timeout=5).read()==b'OK'
def snap(name):
 sh('CLASSPATH=/data/local/tmp/home-pointer.jar app_process /system/bin HomeHierarchyProbe /sdcard/native-tv-verify.xml');s=sh('cat /sdcard/native-tv-verify.xml');(OUT/(name+'.xml')).write_text(s,encoding='utf8');return E.fromstring(s)
def tap(root,id):
 n=next(n for n in root.iter('node') if n.get('resource-id','').endswith('/'+id));l,t,r,b=map(int,re.findall(r'\d+',n.get('bounds')));sh(f'input tap {(l+r)//2} {(t+b)//2}');time.sleep(.4)
def image(name):
 sh('screencap -p /sdcard/native-tv-verify.png');p=OUT/(name+'.png');subprocess.run(D+['pull','/sdcard/native-tv-verify.png',str(p)],check=True,stdout=subprocess.DEVNULL)
 rgb=Image.open(p).convert('RGB');count=sum(1 for r,g,b in rgb.getdata() if (r>160 and g<100 and b<100) or (g>160 and r<100 and b<100));assert count>1000,(name,'video hidden',count)
 # PGS alone is red/green too; test a known video stripe OUTSIDE all caption regions.
 samples=[sum(1 for r,g,b in rgb.crop(box).getdata() if g>160 and r<100 and b<100) for box in [(450,60,600,120),(230,60,290,100)]]
 assert max(samples)>500,(name,'only subtitle pixels, video hidden',samples)
def launch(case):
 f='/sdcard/tv-native-verify-'+case+'-'+str(time.time_ns())+'.mkv';OWNED.append(f);sh('cp '+shlex.quote(MEDIA_DIR+'/tv-native-ui-'+case+'.mkv')+' '+shlex.quote(f));sh('am force-stop com.fongmi.android.tv');subprocess.run(D+['logcat','-c'],check=True)
 sh('am start -n com.fongmi.android.tv/.ui.activity.HomeActivity -a android.intent.action.SEND --es android.intent.extra.TEXT file://'+f)
 for i in range(120):
  s=logs(case+'-start')
  if cue(s) and cue(s)[-1][0]==1:break
  time.sleep(.25)
 else:raise AssertionError((case,'no native bitmap cue'))
 assert vo(s)=='mediacodec_embed',vo(s);return s
def run(cases=('ass','pgs')):
 for case in cases:
  launch(case);ctrl('pause');image(case+'-small');before=cue(logs(case+'-pause-before'));time.sleep(2);assert cue(logs(case+'-pause-after'))==before
  print('PASS',case,'real app native bitmap, visible RGB video, embed and pause',flush=True)
  tap(snap(case+'-before-full'),'video');time.sleep(1);s=logs(case+'-full-paused');image(case+'-full-paused')
  assert cue(s)[-1][0]==1,(case,'paused fullscreen lost caption',cue(s))
  print('PASS',case,'paused fullscreen retains bitmap',flush=True)
  ctrl('play');time.sleep(9);ctrl('pause');s=logs(case+'-gap');assert any(n==0 and 7<=t<=11 for n,t,e in cue(s)),(case,'gap did not clear',cue(s));image(case+'-gap')
  ctrl('play');time.sleep(4);ctrl('pause');s=logs(case+'-b');assert cue(s)[-1][0]==1;image(case+'-b')
  print('PASS',case,'native gap clears / boundary restores',flush=True)
  sh('input keyevent 20');snap(case+'-before-seek');sh('input keyevent 4');sh('input keyevent 22');time.sleep(2);ctrl('pause');s=logs(case+'-seek');image(case+'-seek');assert vo(s)=='mediacodec_embed' and cue(s)[-1][0]==1 and cue(s)[-1][1]>=22,(case,'seek',cue(s))
  print('PASS',case,'paused remote seek clears and restores native bitmap',flush=True)
 print('PASS full application native bitmap basic suite:', ','.join(cases),flush=True)

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--device',required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--media-dir',default='/sdcard');p.add_argument('--port',type=int,default=19978);p.add_argument('--cases',nargs='+',choices=['srt','ass','pgs'],default=['ass','pgs']);a=p.parse_args()
 D=['adb','-s',a.device];OUT=a.out;OUT.mkdir(parents=True,exist_ok=True);PORT=a.port;MEDIA_DIR=a.media_dir.rstrip('/')
 previous=sh('getprop log.tag.MpvSubtitle').strip();sh('setprop log.tag.MpvSubtitle DEBUG');subprocess.run(D+['forward',f'tcp:{PORT}','tcp:9978'],check=True)
 try:run(a.cases)
 finally:
  sh('setprop log.tag.MpvSubtitle '+(previous or 'INFO'))
  if OWNED:sh('rm -f '+' '.join(shlex.quote(f) for f in OWNED))
  subprocess.run(D+['forward','--remove',f'tcp:{PORT}'],check=False)
