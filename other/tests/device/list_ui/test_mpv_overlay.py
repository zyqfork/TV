#!/usr/bin/env python3
"""TV native MPV SRT overlay smoke. Fixtures must be 120s video with cues 0-8 / gap 8-11 / 11-22 / 22-32 / 32-120.
Needs HomeHierarchyProbe in home-pointer.jar. Does not prove HDR, power or other subtitle formats.
"""
import argparse,re,subprocess,time,urllib.request,xml.etree.ElementTree as E
from pathlib import Path
from PIL import Image
p=argparse.ArgumentParser();p.add_argument('--device',required=True);p.add_argument('--video',required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--port',type=int,default=19978);a=p.parse_args();a.out.mkdir(parents=True,exist_ok=True)
D=['adb','-s',a.device]
def adb(*x):return subprocess.check_output(D+list(x),stderr=subprocess.STDOUT).decode('utf8')
def shell(s):return adb('shell',s)
def control(t):assert urllib.request.urlopen(f'http://127.0.0.1:{a.port}/action?do=control&type={t}',timeout=5).read()==b'OK'
def logs(name):
 text=adb('logcat','-d','-v','time','-s','mpv:V','MpvPlayer:I','MpvSubtitle:D');(a.out/(name+'.log')).write_text(text,encoding='utf8');return text
def snapshot(name):
 shell('CLASSPATH=/data/local/tmp/home-pointer.jar app_process /system/bin HomeHierarchyProbe /sdcard/mpv-overlay-ui.xml');text=shell('cat /sdcard/mpv-overlay-ui.xml');(a.out/(name+'.xml')).write_text(text,encoding='utf8');return E.fromstring(text)
def image(name):
 shell('screencap -p /sdcard/mpv-overlay-test.png');path=a.out/(name+'.png');subprocess.run(D+['pull','/sdcard/mpv-overlay-test.png',str(path)],check=True,stdout=subprocess.DEVNULL)
 # The fixture is a moving RGB pattern. Logs/cues alone must not pass a black video shutter.
 colors=sum(1 for r,g,b in Image.open(path).convert('RGB').getdata() if (r>160 and g<100 and b<100) or (g>160 and r<100 and b<100))
 assert colors>1000,('video hidden/black',name,colors)
def tap_node(root,id):
 if id!='video':
  shell('CLASSPATH=/data/local/tmp/native-click.jar app_process /system/bin AccessibilityClickProbe com.fongmi.android.tv:id/'+id);return
 n=next(n for n in root.iter('node') if n.get('resource-id','').endswith('/'+id));l,t,r,b=map(int,re.findall(r'\d+',n.get('bounds')));shell(f'input tap {(l+r)//2} {(t+b)//2}')
def cue(log):
 result=[]
 for line in log.splitlines():
  native=re.search(r'native bitmap cues=(\d+) pts=([^ ]+) epoch=',line)
  plain=re.search(r'cues=(\d+) chars=(\d+) positionMs=(\d+) direct=true',line)
  if native:result.append((int(native[1]),0,int(float(native[2])*1000)))
  elif plain:result.append(tuple(map(int,plain.groups())))
 return result
def last_vo(log):return re.findall(r'VO: \[([^]]+)\]',log)[-1]
adb('forward',f'tcp:{a.port}','tcp:9978');previous=shell('getprop log.tag.MpvSubtitle').strip();shell('setprop log.tag.MpvSubtitle DEBUG')
try:
 # New unique fixture ID avoids resuming an earlier smoke's history position.
 target='/sdcard/tv-overlay-smoke-'+str(time.time_ns())+'.mkv';shell(f'cp {a.video} {target}');shell('am force-stop com.fongmi.android.tv');adb('logcat','-c')
 shell(f'am start -n com.fongmi.android.tv/.ui.activity.HomeActivity -a android.intent.action.SEND --es android.intent.extra.TEXT file://{target}')
 for attempt in range(120):
  text=logs('start')
  if ('SRT overlay active' in text or 'Native bitmap overlay active' in text) and cue(text) and cue(text)[-1][0]==1:break
  time.sleep(.25)
 else:raise AssertionError('Start in MPV performance mode; no native direct SRT cues observed')
 control('pause');image('paused-a');before=cue(logs('paused-before'));time.sleep(4);after=cue(logs('paused-after'));assert before==after,'subtitle advanced while paused';assert last_vo(text)=='mediacodec_embed'
 print('PASS native SRT over embed + pause retains cues',flush=True)
 control('play');time.sleep(9);control('pause');text=logs('gap');assert any(c==0 and 7000<=t<=11000 for c,n,t in cue(text));image('gap')
 control('play');time.sleep(4);control('pause');text=logs('b');assert cue(text)[-1][0]==1 and 10000<=cue(text)[-1][2]<22000;image('b')
 print('PASS native boundary updates + empty gap clears',flush=True)
 # Fullscreen, hide controls, seek forward by remote while paused.
 root=snapshot('before-full');tap_node(root,'video');shell('input keyevent 20');snapshot('controller');shell('input keyevent 4');shell('input keyevent 22');time.sleep(2);control('pause');text=logs('seek');image('seek')
 assert last_vo(text)=='mediacodec_embed';assert any(c==0 for c,n,t in cue(text));assert cue(text)[-1][0]==1,'seek did not republish native subtitle'
 assert cue(text)[-1][2]>=22000,('seek boundary',cue(text)[-1])
 print('PASS paused seek clears old text and repopulates native cue',flush=True)
 # Track dialog must classify native text tracks and support disable/re-enable without GPU.
 shell('input keyevent 20');root=snapshot('before-track')
 for attempt in range(3):
  tap_node(root,'text');time.sleep(.6);root=snapshot('tracks')
  if any(n.get('resource-id','').endswith('/offset') for n in root.iter('node')):break
 else:raise AssertionError('Native text-track dialog was not opened')
 assert any('字幕' in n.get('text','') and n.get('resource-id','').endswith('/title') for n in root.iter('node'))
 # Clicking the selected native track disables it (empty Media3 override).
 for attempt in range(3):
  tap_node(root,'text');time.sleep(.6);root=snapshot('track-off')
  if not any(n.get('resource-id','').endswith('/offset') for n in root.iter('node')):break
 text=logs('track-off');assert ('SRT overlay inactive' in text or 'Native bitmap overlay inactive' in text) and cue(text)[-1][0]==0,'track-off retained subtitle'
 shell('input keyevent 20');root=snapshot('off-controller')
 for attempt in range(3):
  tap_node(root,'text');time.sleep(.6);root=snapshot('tracks-off')
  if any(n.get('resource-id','').endswith('/offset') for n in root.iter('node')):break
 for attempt in range(3):
  tap_node(root,'text');time.sleep(.6);root=snapshot('track-on')
  if not any(n.get('resource-id','').endswith('/offset') for n in root.iter('node')):break
 text=logs('track-on');assert cue(text)[-1][0]==1 and last_vo(text)=='mediacodec_embed','track-on did not restore direct subtitle'
 image('track-on');print('PASS real text-track dialog / disable / re-enable / no GPU route',flush=True)
 (a.out/'result.txt').write_text('PASS embedded native direct, pause, gap, seek, actual dialog, disable/re-enable. Delay/ASS/perf comparison are separate checks.\n')
finally:
 shell('setprop log.tag.MpvSubtitle '+(previous or 'INFO'))
 if 'target' in globals():shell('rm -f '+target)
 adb('forward','--remove',f'tcp:{a.port}')
