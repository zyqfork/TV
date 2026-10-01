"""Physical-key probe on an already playing, fullscreen TV CastActivity/VideoActivity.

Temporarily pauses/resumes and opens/dismisses the player-choice dialog. Does not
select an engine, seek, launch media, clear history or modify settings. Must run
without concurrent remote/scrcpy input and with explicit --allow-control.
Requires home-pointer.jar. This script is NOT an installation or fixture setup.
"""
import argparse
import importlib.util
import re
import subprocess
import time
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--device', required=True)
parser.add_argument('--out', type=Path, required=True)
parser.add_argument('--allow-control', action='store_true')
parser.add_argument('--activity', choices=['cast', 'vod'], required=True)
a = parser.parse_args()
assert a.allow_control, 'Refusing to inject keys without --allow-control'
spec = importlib.util.spec_from_file_location('h', Path(__file__).with_name('test_mpv_native_overlay.py'))
h = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)
h.D = ['adb', '-s', a.device]
h.OUT = a.out
h.OUT.mkdir(parents=True, exist_ok=True)

def key(code):
    h.sh('input keyevent ' + str(code))
    time.sleep(.15)

def state():
    text = h.sh('dumpsys media_session')
    session = re.search(r'(?m)^\s*androidx\.media3\.session\.id\. com\.fongmi\.android\.tv/[^\n]+\n(.*?)(?=\n    \S|\nAudio playback|\Z)', text, re.S)
    assert session, 'No application playback session'
    value = re.search(r'state=PlaybackState \{state=(\d+)', session.group(1))
    assert value, 'No playback state'
    return int(value.group(1))

def wait_state(expected):
    for _ in range(40):
        current = state()
        if current == expected:
            return
        time.sleep(.15)
    raise AssertionError(('session state', expected, current))

def node(root, id):
    return next((n for n in root.iter('node') if n.get('resource-id') == 'com.fongmi.android.tv:id/' + id), None)

def focus(root):
    return next((n for n in root.iter('node') if n.get('focused') == 'true'), None)

def screenshot(name):
    (h.OUT / (name + '.png')).write_bytes(subprocess.check_output(h.D + ['exec-out', 'screencap', '-p']))

started = False
try:
    activity = h.sh('dumpsys activity activities')
    resumed = next(l for l in activity.splitlines() if 'topResumedActivity=' in l)
    expected_activity = 'CastActivity' if a.activity == 'cast' else 'VideoActivity'
    assert re.search(r'\.ui\.activity\.' + expected_activity + r'\b', resumed), resumed
    root = h.snap('initial')
    video = node(root, 'video')
    assert video is not None and video.get('bounds') == '[0,0][1920,1080]', 'Fullscreen is required'
    assert state() == 3, 'Start with playback RUNNING; do not change a user-paused session'
    started = True
    if node(root, 'decode') is None:
        key(20)
        root = h.snap('preflight-menu')
    assert node(root, 'play_pause') is not None, 'New control APK is required'
    key(4)  # Hide only the verified menu, not leave playback.
    for cycle in range(3):
        key(23)
        wait_state(2)
        root = h.snap('paused-' + str(cycle))
        button = node(root, 'play_pause')
        assert button is not None and button.get('text') in {'播放', 'Play'}
        assert button.get('focused') == 'true', ('pause must focus Resume', focus(root).attrib if focus(root) is not None else None)
        screenshot('paused-' + str(cycle))
        key(23)  # No Back, menu timeout, navigation, ACTION_CLICK or remote HTTP play.
        wait_state(3)
        root = h.snap('resumed-' + str(cycle))
        assert node(root, 'play_pause') is None, 'Resume must hide the menu'
        screenshot('resumed-' + str(cycle))
    print('PASS', a.activity, 'three single-OK pause / default Resume focus / immediate single-OK resume cycles', flush=True)
    key(20)
    root = h.snap('menu-playing')
    assert node(root, 'play_pause').get('text') in {'暂停', '暫停', 'Pause'}
    for _ in range(12):
        root = h.snap('menu-navigation')
        selected = focus(root)
        if selected is not None and selected.get('resource-id') == 'com.fongmi.android.tv:id/player':
            break
        key(22)
    else:
        raise AssertionError('Cannot navigate from pause/play to engine button')
    key(23)
    wait_state(3)
    root = h.snap('engine-dialog')
    assert node(root, 'exo') is not None and node(root, 'mpv') is not None, 'Center was stolen from focused engine action'
    screenshot('engine-dialog')
    key(4)  # Dismiss without choosing/changing engine.
    wait_state(3)
    print('PASS', a.activity, 'menu navigation / center activates focused engine button rather than toggling playback', flush=True)
finally:
    if started:
        # Restore only play/pause if the probe stopped early; never select a menu button blindly.
        try:
            if state() == 2:
                root = h.snap('cleanup')
                button = node(root, 'play_pause')
                if button is not None and button.get('focused') == 'true':
                    key(23)
                elif node(root, 'decode') is None:
                    key(23)
                else:
                    print('WARNING playback remains paused: refusing to activate an unrelated focused menu button', flush=True)
        finally:
            root = h.snap('cleanup-menu')
            if node(root, 'exo') is not None and node(root, 'mpv') is not None:
                key(4)
            elif node(root, 'decode') is not None:
                key(4)
            h.sh('rm -f /sdcard/native-tv-verify.xml')
