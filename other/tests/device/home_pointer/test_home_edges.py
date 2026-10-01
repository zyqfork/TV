#!/usr/bin/env python3
"""Real TV logical-row navigation / single OK / bottom-row regression.
Needs the shell-only home-pointer.jar, existing history and loaded recommendations.
Does not delete history, change source, or claim physical mouse acceptance.
"""
import argparse
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as E

p = argparse.ArgumentParser()
p.add_argument('--device', required=True)
p.add_argument('--out', type=Path, required=True)
a = p.parse_args()
a.out.mkdir(parents=True, exist_ok=True)
D = ['adb', '-s', a.device]
def sh(command):
    return subprocess.check_output(D + ['shell', command], stderr=subprocess.STDOUT).decode('utf8')
def key(code, count=1):
    for _ in range(count): sh('input keyevent ' + str(code))
    time.sleep(.35)
def snap(name):
    path = '/sdcard/home-edges.xml'
    assert 'Snapshot written' in sh('CLASSPATH=/data/local/tmp/home-pointer.jar app_process /system/bin HomeHierarchyProbe ' + path)
    text = sh('cat ' + path)
    (a.out / (name + '.xml')).write_text(text, encoding='utf8')
    return E.fromstring(text)
def focus(root):
    nodes = [n for n in root.iter('node') if n.get('focused') == 'true']
    assert len(nodes) == 1, ('missing/ambiguous focus', len(nodes))
    node = nodes[0]
    assert node.get('clickable') == 'true', ('focus stranded on container', node.attrib)
    return node, [n.get('text') for n in node.iter('node') if n.get('text')]
def shot(name):
    sh('screencap -p /sdcard/home-edges.png')
    subprocess.run(D + ['pull', '/sdcard/home-edges.png', str(a.out / (name + '.png'))], check=True, stdout=subprocess.DEVNULL)

sh('am force-stop com.fongmi.android.tv')
sh('am start -n com.fongmi.android.tv/.ui.activity.HomeActivity')
try:
    for _ in range(40):
        time.sleep(.4)
        root = snap('initial')
        if any(n.get('text') == '点播' for n in root.iter('node')): break
    assert '点播' in focus(root)[1]
    key(22, 7)
    assert '设置' in focus(snap('rightmost-function'))[1]
    key(19)
    node, labels = focus(snap('source-focus'))
    assert node.get('resource-id', '').endswith('/title'), ('rightmost entry cannot reach source', labels)
    original = node.get('text')
    shot('source-focus')
    key(23)  # ONE real down/up pair, not Accessibility ACTION_CLICK.
    root = snap('source-dialog')
    assert not any(n.get('resource-id', '').endswith('/title') for n in root.iter('node')), 'single OK did not open source dialog'
    _, labels = focus(root)
    assert original in labels, ('dialog focused unfiltered/hidden-site index', original, labels)
    shot('source-dialog')
    key(23)  # Reselect the already-current source; do not change the user preference.
    root = snap('source-selected')
    assert any(n.get('resource-id', '').endswith('/title') for n in root.iter('node')), 'single OK did not select current source'
    assert any(n.get('text') == original for n in root.iter('node'))
    print('PASS far-right entry -> source / single OK opens and selects current source', flush=True)
    key(20)  # functions
    key(20)  # history heading
    assert '最近观看' in focus(snap('history-heading'))[1]
    shot('history-heading')
    key(20)
    assert any(n.get('resource-id', '').endswith('/name') for n in focus(snap('history-card'))[0].iter('node'))
    key(22, 6)
    key(19)
    assert '最近观看' in focus(snap('right-history-up'))[1], 'far-right history cannot reach left-aligned heading'
    key(20)
    key(20)
    assert '更新推荐' in focus(snap('recommend-heading'))[1]
    shot('recommend-heading')
    key(20)
    key(22, 6)
    key(20, 35)
    root = snap('bottom-right')
    node, labels = focus(root)
    assert any(n.get('resource-id', '').endswith('/name') for n in node.iter('node')), ('bottom not a recommendation', labels)
    shot('bottom-right')
    key(21, 6)
    left, left_labels = focus(snap('bottom-left'))
    assert left_labels != labels, 'last recommendation row not navigable horizontally'
    key(22, 6)
    assert focus(snap('bottom-right-again'))[1] == labels, 'last card not reachable again'
    key(19, 50)
    node, _ = focus(snap('top-again'))
    assert node.get('resource-id', '').endswith('/title'), 'Up cannot return to source/title'
    print('PASS right history -> heading / recommendation heading / final row / true top', flush=True)
finally:
    key(4)
    sh('rm -f /sdcard/home-edges.xml /sdcard/home-edges.png')
