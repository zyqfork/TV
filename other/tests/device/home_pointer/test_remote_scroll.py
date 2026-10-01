#!/usr/bin/env python3
"""TV Home viewport-based D-pad regression. Requires the shell-only home-pointer.jar."""
import argparse,re,subprocess,time,xml.etree.ElementTree as E
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--device',required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();a.out.mkdir(parents=True,exist_ok=True)
def adb(*args):return subprocess.check_output(['adb','-s',a.device,*args],stderr=subprocess.STDOUT).decode('utf8')
def shell(s):return adb('shell',s)
def snapshot(name):
 path='/sdcard/remote-scroll.xml';assert 'Snapshot written' in shell('CLASSPATH=/data/local/tmp/home-pointer.jar app_process /system/bin HomeHierarchyProbe '+path)
 text=shell('cat '+path);(a.out/(name+'.xml')).write_text(text,encoding='utf8');return E.fromstring(text)
def key(code,count=1):
 for _ in range(count):shell('input keyevent '+str(code))
 time.sleep(.4)
def toolbar(root):
 # Title text deliberately scales on focus; compare the untransformed real toolbar item.
 title=next(n for n in root.iter('node') if n.get('resource-id','').endswith('/title'))
 return next(n for n in root.iter('node') if title in list(n))
shell('am force-stop com.fongmi.android.tv');shell('am start -n com.fongmi.android.tv/.ui.activity.HomeActivity')
for attempt in range(20):
 time.sleep(.6);base=snapshot('initial')
 try: baseline=toolbar(base).get('bounds');break
 except StopIteration:
  if attempt==19:raise
shell('setprop log.tag.HomePointer DEBUG');assert baseline
adb('logcat','-c');key(20,3);snapshot('down')
log=adb('logcat','-d','-s','HomePointer:D');(a.out/'down.log').write_text(log,encoding='utf8')
deltas=[int(x) for x in re.findall(r'scroll dy=(-?\d+)',log)];assert len(deltas)>=3 and len(set(deltas))==1 and deltas[0]>0,('focus alignment altered viewport step',deltas)
key(19,30);root=snapshot('top');assert toolbar(root).get('bounds')==baseline, 'D-pad stopped at title text instead of true viewport top'
log=adb('logcat','-d','-s','HomePointer:D');assert 'first=0@0 atTop=true' in log;print('PASS fixed viewport steps / unfocusable top padding reachable')
# Switch from a pointer-scrolled viewport back to D-pad without needing an off-screen selected item.
shell('CLASSPATH=/data/local/tmp/home-pointer.jar app_process /system/bin HomePointerProbe wheel -1 10');key(19,40);root=snapshot('pointer-to-remote');assert toolbar(root).get('bounds')==baseline
# Bottom clamps instead of rebounding to a focused item; a held Up must continue scrolling.
key(20,35);snapshot('bottom');shell('CLASSPATH=/data/local/tmp/home-pointer.jar app_process /system/bin HomePointerProbe keyhold 19 1800');snapshot('held-up');key(19,40)
assert toolbar(snapshot('held-top')).get('bounds')==baseline
key(20,3);key(4);root=snapshot('back-top');assert toolbar(root).get('bounds')==baseline
assert any(n.get('focused')=='true' for n in root.iter('node'));print('PASS pointer transition / bottom / held Up / back-top focus')
shell('setprop log.tag.HomePointer INFO')
