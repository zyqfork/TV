#!/usr/bin/env python3
"""Regression probe for TV mouse scrolling (requires the shell-only HomePointerProbe jar).

Build both HomePointerProbe.java and HomeHierarchyProbe.java with javac / android.jar, then d8:
  d8 --lib <android.jar> --output home-pointer.jar HomePointerProbe.class HomeHierarchyProbe.class
Push home-pointer.jar to /data/local/tmp/home-pointer.jar and run:
  python test_pointer.py --device home-rk:5555 --out .pi/home-pointer-tests
The installed app must use HomeRecyclerView's HomePointer DEBUG tracing. No root/data reset is needed.
Injection coverage is not a substitute for testing a physical mouse.
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
parser.add_argument('--cases', nargs='+', help='Optionally run only the named cases')
args = parser.parse_args()
args.out.mkdir(parents=True, exist_ok=True)

def adb(*parts):
    return subprocess.check_output(['adb', '-s', args.device, *parts], stderr=subprocess.STDOUT).decode('utf-8')

def probe(*parts):
    return adb('shell', 'CLASSPATH=/data/local/tmp/home-pointer.jar app_process /system/bin HomePointerProbe ' + ' '.join(map(str, parts)))

def snapshot(name):
    remote = '/sdcard/home-pointer-' + name + '.xml'
    # A ticking clock can prevent uiautomator's idle wait from succeeding. Never read an
    # old dump after a failed command; use a fresh, shell-only UiAutomation snapshot.
    message = adb('shell', 'CLASSPATH=/data/local/tmp/home-pointer.jar app_process /system/bin HomeHierarchyProbe ' + remote)
    assert 'Snapshot written:' in message, f'{name}: snapshot failed: {message}'
    xml = adb('shell', 'cat ' + remote)
    (args.out / (name + '.xml')).write_text(xml, encoding='utf-8')
    root = ET.fromstring(xml)
    grid = next(n for n in root.iter('node') if n.get('resource-id', '').endswith('/recycler'))
    origin = list(map(int, re.findall(r'-?\d+', grid.get('bounds'))))[1]
    markers = {n.get('text'): list(map(int, re.findall(r'-?\d+', n.get('bounds'))))[1] - origin
               for n in root.iter('node') if n.get('text') in ('最近观看', '更新推荐')
               and n.get('resource-id', '').endswith('/text')}
    logo = next((n for n in root.iter('node') if n.get('resource-id', '').endswith('/logo')), None)
    if logo is not None:
        left, top, right, bottom = map(int, re.findall(r'-?\d+', logo.get('bounds')))
        # Toolbar top padding = half the logo height, independent of device density.
        markers['__toolbar_offset__'] = top - origin - (bottom - top) // 2
    return markers

cases = [
    ('return_top', 5, ('drag', 960, 350, 1050, 100, 16, 0), 0),
    ('slow_return', 3, ('drag', 960, 300, 1000, 350, 8, 0), 0),
    ('horizontal_then_vertical', 3, ('turn', 960, 300, 1000, 120, 8, 0), 0),
    ('child_disallows_intercept', 1, ('turnleft', 960, 400, 1000, 100, 8, 0), 0),
    ('hold_at_top', 3, ('drag', 960, 350, 1050, 100, 8, 4000), 0),
    ('edge_reverse', 3, ('bounce', 960, 350, 1050, 100, 8, 0), -200),
    ('drag_towards_bottom', 0, ('drag', 960, 900, 250, 100, 8, 0), None),
]
previous_property = adb('shell', 'getprop log.tag.HomePointer').strip()
try:
    adb('shell', 'setprop log.tag.HomePointer DEBUG')
    for name, wheel, command, final_top in cases:
        if args.cases and name not in args.cases:
            continue
        adb('shell', 'am force-stop com.fongmi.android.tv')
        adb('shell', 'am start -n com.fongmi.android.tv/.ui.activity.HomeActivity')
        time.sleep(4)
        baseline = snapshot(name + '-top')
        if abs(baseline.get('__toolbar_offset__', 9999)) > 1:
            # Native RecyclerView may restore task view state after a force-stop. Start each
            # test from an explicitly verified top, not an assumed launch position.
            adb('shell', 'input keyevent 4')
            time.sleep(1)
            baseline = snapshot(name + '-top-reset')
        assert abs(baseline.get('__toolbar_offset__', 9999)) <= 1, f'{name}: could not establish top baseline {baseline}'
        adb('logcat', '-c')
        if wheel:
            probe('wheel', -1, wheel)
        probe(*command)
        log = adb('logcat', '-d', '-s', 'HomePointer:D')
        (args.out / (name + '.log')).write_text(log, encoding='utf-8')
        records = re.findall(r'(scroll|layout) dy=(-?\d+).*?first=(\d+)@(-?\d+)', log)
        assert any(r[0] == 'scroll' for r in records), f'{name}: no native scroll captured'
        previous = None
        for kind, dy, position, top in records:
            dy, position, top = map(int, (dy, position, top))
            if previous is not None and position == previous[0]:
                movement = top - previous[1]
                assert abs(movement) <= abs(dy) + 1, f'{name}: uncommanded jump {movement} vs input {dy}'
                assert movement == 0 or movement * dy <= 0, f'{name}: viewport reversed direction'
            previous = position, top
        before = snapshot(name + '-idle')
        if final_top is not None:
            release = re.findall(r'release.*?atTop=(true|false)', log)
            assert release, f'{name}: no release'
            assert release[-1] == ('true' if final_top == 0 else 'false'), f'{name}: wrong edge state'
            # The toolbar is now an actual adapter item and gets recycled offscreen. Check a
            # stable section's screen position rather than assuming item 0 remains attached.
            common = before.keys() & baseline.keys()
            assert common, f'{name}: no shared section marker'
            for marker in common:
                assert abs(before[marker] - baseline[marker] - final_top) <= 1, f'{name}: wrong marker offset {marker}: {baseline[marker]} -> {before[marker]}'
        time.sleep(3)
        after = snapshot(name + '-later')
        assert before and before == after, f'{name}: idle layout changed viewport {before} -> {after}'
        print('PASS', name, flush=True)
finally:
    adb('shell', f'setprop log.tag.HomePointer "{previous_property}"')
