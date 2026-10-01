"""TV subtitle-close smoke (1920x1080, Chinese UI, MPV/performance initially).

Requires native-click.jar, home-pointer.jar and <media-dir>/tv-native-ui-srt.mkv
with visible RGB video, black caption background and a white SRT cue at 0-8s.
Interrupts playback; removes only its unique media copies, never user history.
Exo legacy text renders a reselected cue on resume, not while paused.
"""
import argparse
import importlib.util
import shlex
import subprocess
import time
from pathlib import Path
from PIL import Image

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--device', required=True)
parser.add_argument('--out', type=Path, required=True)
parser.add_argument('--media-dir', default='/sdcard')
parser.add_argument('--port', type=int, default=19978)
parser.add_argument('--modes', nargs='+', choices=['performance', 'compatible', 'software', 'exo'],
                    default=['performance', 'compatible', 'software', 'exo'])
a = parser.parse_args()
spec = importlib.util.spec_from_file_location('h', Path(__file__).with_name('test_mpv_native_overlay.py'))
h = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)
h.D = ['adb', '-s', a.device]
h.OUT = a.out
h.OUT.mkdir(parents=True, exist_ok=True)
h.MEDIA_DIR = a.media_dir.rstrip('/')
h.PORT = a.port
sh = h.sh
previous = {tag: sh('getprop log.tag.' + tag).strip() for tag in ['MpvSubtitle', 'SubtitleResources']}

def action(id, text=None):
    cmd = 'CLASSPATH=/data/local/tmp/native-click.jar app_process /system/bin AccessibilityClickProbe '
    cmd += 'android:id/text1' if id == 'text1' else 'com.fongmi.android.tv:id/' + id
    if text is not None:
        cmd += ' ' + shlex.quote(text)
    sh(cmd)
    time.sleep(.6)

def ensure_full():
    root = h.snap('window')
    video = next(n for n in root.iter('node') if n.get('resource-id', '').endswith('/video'))
    if video.get('bounds') != '[0,0][1920,1080]':
        h.tap(root, 'video')
        time.sleep(.5)

def labels(name):
    ensure_full()
    sh('input keyevent 20')
    root = h.snap(name)
    return {n.get('resource-id').split('/')[-1]: n.get('text') for n in root.iter('node')
            if n.get('resource-id', '').endswith(('/decode', '/player')) and n.get('text')}

def engine(target):
    lab = labels('engine-before')
    if lab['player'] != target:
        action('player', lab['player'])
        action(target.lower())
        time.sleep(1.2)
        h.ctrl('pause')

def decode(target):
    for _ in range(4):
        lab = labels('decode-before')
        if lab['decode'] == target:
            return
        action('decode')
        time.sleep(1.2)
        h.ctrl('pause')
    raise AssertionError(('decode unavailable', target, lab))

def hide():
    ensure_full()
    root = h.snap('hide-before')
    if any(n.get('resource-id', '').endswith('/decode') for n in root.iter('node')):
        sh('input keyevent 4')
    time.sleep(.6)

def resource_logs(name):
    text = subprocess.check_output(h.D + ['logcat', '-d', '-s', 'SubtitleResources:D']).decode('utf8')
    (h.OUT / (name + '-resources.log')).write_text(text, encoding='utf8')
    return text

def image(name, expected):
    h.image(name)  # Also asserts visible RGB video independently of subtitles.
    im = Image.open(h.OUT / (name + '.png')).convert('RGB')
    pixels = im.crop((250, 720, 1670, 1080)).getdata()
    count = sum(1 for r, g, b in pixels if min(r, g, b) > 200)
    if not (count > 500 if expected else count == 0):
        resource_logs(name + '-failed')
        raise AssertionError((name, 'caption visible mismatch', expected, count))

started = False
mode_names = {'performance': '性能硬解', 'compatible': '兼容硬解', 'software': '软解'}
try:
    for tag in previous:
        sh('setprop log.tag.' + tag + ' DEBUG')
    subprocess.run(h.D + ['forward', 'tcp:' + str(h.PORT), 'tcp:9978'], check=True)
    h.launch('srt')
    started = True
    h.ctrl('pause')
    h.tap(h.snap('initial-small'), 'video')
    time.sleep(.8)
    for mode in a.modes:
        engine('MPV')
        decode('性能硬解')
        h.launch('srt')
        h.ctrl('pause')
        h.tap(h.snap('small'), 'video')
        time.sleep(.8)
        if mode == 'exo':
            engine('EXO')
        else:
            decode(mode_names[mode])
        labels(mode + '-before')
        hide()
        image(mode + '-on', True)
        labels(mode + '-controls')
        action('text')
        root = h.snap(mode + '-dialog')
        assert any(n.get('resource-id', '').endswith('/closeSubtitle') and n.get('text') == '关闭字幕'
                   for n in root.iter('node'))
        resources_before = resource_logs(mode + '-before-close')
        action('closeSubtitle')
        hide()
        image(mode + '-off', False)
        before = h.cue(h.logs(mode + '-off'))
        time.sleep(2)
        assert h.cue(h.logs(mode + '-off-later')) == before, (mode, 'closed bridge still publishes')
        released = resource_logs(mode)
        assert 'released canvas painters=1' in released[len(resources_before):], (mode, 'painters retained')
        assert 'text disabled=true' in released[len(resources_before):], (mode, 'text type not disabled')
        if mode == 'performance':
            assert 'Android subtitle overlay renderer released' in h.logs(mode + '-off')
            decode('兼容硬解')
            hide()
            image(mode + '-closed-rebuild-compatible', False)
            decode('性能硬解')
            hide()
            image(mode + '-closed-rebuild-performance', False)
            print('PASS close persists across compatible/performance rebuild', flush=True)
        labels(mode + '-off-controls')
        action('text')
        h.snap(mode + '-off-tracks')
        action('text')
        time.sleep(.5)
        hide()
        if mode == 'exo':
            image(mode + '-reselected-paused', False)
            h.ctrl('play')
            time.sleep(.7)
            h.ctrl('pause')
            hide()
        image(mode + '-on-again', True)
        if mode != 'exo':
            assert h.vo(h.logs(mode + '-restored')) == ('mediacodec_embed' if mode == 'performance' else 'gpu')
        print('PASS', mode, 'explicit Close / no caption / renderer and painter release / reselect', flush=True)
    print('PASS subtitle-close suite:', ','.join(a.modes), flush=True)
finally:
    try:
        if started:
            engine('MPV')
            decode('性能硬解')
    finally:
        for tag, value in previous.items():
            sh('setprop log.tag.' + tag + ' ' + (value or 'INFO'))
        if h.OWNED:
            sh('rm -f ' + ' '.join(shlex.quote(f) for f in h.OWNED))
        subprocess.run(h.D + ['forward', '--remove', 'tcp:' + str(h.PORT)], check=False)
