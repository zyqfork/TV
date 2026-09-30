#!/usr/bin/env python3
"""Device smoke checks: TV Douban-style source with 热播电影/电影筛选 tabs;
phone resource source with 动作片 tab, landscape 2340x1080. Requires home-pointer.jar
at /data/local/tmp. Does not delete history or change configuration. Not real-mouse acceptance.
"""
import argparse,subprocess,time,re,xml.etree.ElementTree as E
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--device',required=True);p.add_argument('--phone',action='store_true');p.add_argument('--out',type=Path,required=True);a=p.parse_args()
D=a.device; out=a.out;out.mkdir(parents=True,exist_ok=True)
def adb(*s):return subprocess.check_output(['adb','-s',D,*s],stderr=subprocess.STDOUT).decode('utf-8')
def snap(name):
 remote='/sdcard/pages-'+name+'.xml'; msg=adb('shell','CLASSPATH=/data/local/tmp/home-pointer.jar app_process /system/bin HomeHierarchyProbe '+remote);assert 'Snapshot written:' in msg
 text=adb('shell','cat '+remote);(out/(name+'.xml')).write_text(text,encoding='utf-8');return E.fromstring(text)
def box(n):return list(map(int,re.findall(r'-?\d+',n.get('bounds'))))
def click(root,label,mouse=False):
 parents={c:p for p in root.iter('node') for c in p};n=next(n for n in root.iter('node') if n.get('text')==label)
 while n.get('clickable')!='true':n=parents[n]
 l,t,r,b=box(n);x=(l+r)//2;y=(t+b)//2
 if mouse:adb('shell',f'CLASSPATH=/data/local/tmp/home-pointer.jar app_process /system/bin HomePointerProbe drag {x} {y} {y} 1 0 0')
 else:adb('shell',f'input tap {x} {y}')
 time.sleep(1)
def key(k):adb('shell',f'input keyevent {k}');time.sleep(.6)
def wheel(count,axis=-1):adb('shell',f'CLASSPATH=/data/local/tmp/home-pointer.jar app_process /system/bin HomePointerProbe wheel {axis} {count}')
adb('shell','am force-stop com.fongmi.android.tv');adb('shell','am start -n com.fongmi.android.tv/.ui.activity.HomeActivity');time.sleep(4)
root=snap('start')
if not a.phone:
 key(23);root=snap('category');assert any(n.get('text')=='热播电影' for n in root.iter('node'))
 key(20); key(22); root=snap('card-focus');assert any(n.get('focused')=='true' and n.get('long-clickable')=='true' for n in root.iter('node'))
 wheel(8);after=snap('wheel'); assert any(n.get('text')=='热播电影' for n in after.iter('node')), 'category header disappeared'
 l,t,r,b=box([n for n in after.iter('node') if n.get('resource-id','').endswith('/recycler')][-1]);
 adb('shell',f'CLASSPATH=/data/local/tmp/home-pointer.jar app_process /system/bin HomePointerProbe drag {(l+r)//2} {t+100} {b-40} 80 8 0');snap('drag')
 wheel(8);key(4);top=snap('back-top');assert any(n.get('text')=='热播电影' and n.get('focused')=='true' for n in top.iter('node'))
 click(top,'电影筛选',True);time.sleep(2);root=snap('filter-category');key(82);root=snap('filters-open')
 # Filter options are text controls below the category strip, not movie names.
 options=[n for n in root.iter('node') if n.get('resource-id','').endswith('/text') and box(n)[1]>123 and n.get('clickable')=='true']
 if options:
  l,t,r,b=box(options[-1]);adb('shell',f'input tap {(l+r)//2} {(t+b)//2}');time.sleep(2);snap('filtered')
  key(82);snap('filters-close')
 else: print('SKIP filter selection: current source did not supply filter rows')
 key(4)
 root=snap('back-category')
 if not any(n.get('resource-id','').endswith('/title') for n in root.iter('node')): key(4)
 # Source dialog shares the refactored constrained list.
 root=snap('home');title=next(n for n in root.iter('node') if n.get('resource-id','').endswith('/title'));l,t,r,b=box(title);adb('shell',f'input tap {(l+r)//2} {(t+b)//2}');snap('source-dialog');key(4)
else:
 wheel(5); root=snap('wheel'); assert any(n.get('resource-id','').endswith('/recycler') for n in root.iter('node'))
 wheel(25);time.sleep(2);snap('wheel-paginated')
 wheel(40,1);root=snap('wheel-top')
 # Native touch scrolling / CoordinatorLayout header expansion.
 grids=[n for n in root.iter('node') if n.get('resource-id','').endswith('/recycler')];l,t,r,b=box(grids[-1]);
 adb('shell',f'input swipe {(l+r)//2} {b-30} {(l+r)//2} {t+40} 650');time.sleep(1);snap('touch')
 wheel(40,1);root=snap('touch-top')
 click(root,'动作片');time.sleep(2);root=snap('category')
 for attempt in range(10):
  if any(n.get('resource-id','').endswith('/recycler') for n in root.iter('node')): break
  time.sleep(1);root=snap('category-ready-'+str(attempt))
 grid=[n for n in root.iter('node') if n.get('resource-id','').endswith('/recycler')][-1]
 before=int(grid.get('row-count','0'))
 wheel(80);time.sleep(2);more=snap('category-more')
 rows=[int(n.get('row-count','0')) for n in more.iter('node') if n.get('resource-id','').endswith('/recycler')]
 print('Category rows before/after',before,rows)
 assert before>0 and max(rows)>before, 'category wheel reached bottom without appending page'
 wheel(100,1);snap('category-restored')
 # Open history without deleting records (logo button), then scroll and dismiss.
 wheel(40,1);root=snap('category-top');logo=next(n for n in root.iter('node') if n.get('resource-id','').endswith('/logo'));l,t,r,b=box(logo);adb('shell',f'input tap {(l+r)//2} {(t+b)//2}');snap('history-dialog');key(4)
 root=snap('return');click(root,'设置');snap('settings');key(4)
print('PASS',D,'category / wheel / drag or touch / dialogs / return')
