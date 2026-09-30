#!/usr/bin/env python3
"""TV home navigation regression (Chinese UI; at least two existing history cards).

Requires the same shell-only probe jar as test_pointer.py. Exercises native D-pad
focus, a real held center key, cancelling deletion mode without deleting records,
a single mouse click on settings, and returning home. Injection is not physical
mouse acceptance testing.
"""
import argparse
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--device', required=True)
parser.add_argument('--out', type=Path, required=True)
args = parser.parse_args()
args.out.mkdir(parents=True, exist_ok=True)

def adb(*parts):
    return subprocess.check_output(['adb', '-s', args.device, *parts], stderr=subprocess.STDOUT).decode('utf-8')

def key(code, held=False):
    if held:
        # Android's input --longpress uses a synthetic repeat; hold the actual DOWN instead.
        adb('shell', f'CLASSPATH=/data/local/tmp/home-pointer.jar app_process /system/bin HomePointerProbe keyhold {code} 1200')
    else:
        adb('shell', f'input keyevent {code}')
    time.sleep(0.6)

def snapshot(name):
    remote = '/sdcard/home-navigation-' + name + '.xml'
    message = adb('shell', 'CLASSPATH=/data/local/tmp/home-pointer.jar app_process /system/bin HomeHierarchyProbe ' + remote)
    assert 'Snapshot written:' in message, message
    xml = adb('shell', 'cat ' + remote)
    (args.out / (name + '.xml')).write_text(xml, encoding='utf-8')
    return ET.fromstring(xml)

def bounds(node):
    return list(map(int, re.findall(r'-?\d+', node.get('bounds'))))

def focused(root):
    node = next(n for n in root.iter('node') if n.get('focused') == 'true')
    labels = [n.get('text') for n in node.iter('node') if n.get('text')]
    return node, labels

adb('shell', 'am force-stop com.fongmi.android.tv')
adb('shell', 'am start -n com.fongmi.android.tv/.ui.activity.HomeActivity')
time.sleep(4)
assert '点播' in focused(snapshot('initial-focus'))[1]
key(20)
assert '最近观看' in focused(snapshot('down-header'))[1]
key(20)
first, labels = focused(snapshot('down-history'))
assert labels and '最近观看' not in labels
key(22)
second, labels = focused(snapshot('right-history'))
assert labels and bounds(second)[0] > bounds(first)[0]
key(23, held=True)
assert any(n.get('resource-id', '').endswith('/delete') for n in snapshot('delete-mode').iter('node'))
key(4)
assert not any(n.get('resource-id', '').endswith('/delete') for n in snapshot('delete-cancel').iter('node'))
key(4)
root = snapshot('back-top')
assert '点播' in focused(root)[1]
parents = {child: parent for parent in root.iter('node') for child in parent}
node = next(n for n in root.iter('node') if n.get('text') == '设置')
while node.get('clickable') != 'true':
    node = parents[node]
left, top, right, bottom = bounds(node)
x, y = (left + right) // 2, (top + bottom) // 2
adb('shell', f'CLASSPATH=/data/local/tmp/home-pointer.jar app_process /system/bin HomePointerProbe drag {x} {y} {y} 1 0 0')
time.sleep(1)
activities = adb('shell', 'dumpsys activity activities')
assert re.search(r'(topResumedActivity|mResumedActivity).*SettingActivity', activities), 'first mouse click did not open settings'
key(4)
assert any(n.get('text') == '最近观看' for n in snapshot('settings-return').iter('node'))
print('PASS dpad / history hold / cancel / back-top / first mouse click / return', flush=True)
