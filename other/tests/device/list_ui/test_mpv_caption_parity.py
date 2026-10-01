#!/usr/bin/env python3
"""TV SRT native/embed vs native/GPU screenshot parity (FIT, same font/size/position).
Requires the two shell jars used by test_mpv_native_overlay.py and native-click.jar.
Fixture: tv-native-ui-srt.mkv, 1280x720 RGB video above y=450 and BLACK below;
white English/CJK SRT 0-8 / gap8-11 /11-120. Restores original decode preference.
This checks one device/settings combination, not every style/HDR/power scenario.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import shlex
import subprocess
import time
from PIL import Image

p = argparse.ArgumentParser()
p.add_argument('--device', required=True)
p.add_argument('--out', type=Path, required=True)
p.add_argument('--media-dir', default='/sdcard')
p.add_argument('--port', type=int, default=19978)
a = p.parse_args()
s = importlib.util.spec_from_file_location('overlay_helpers', Path(__file__).with_name('test_mpv_native_overlay.py'))
h = importlib.util.module_from_spec(s)
s.loader.exec_module(h)
h.D = ['adb', '-s', a.device]
h.OUT = a.out
h.OUT.mkdir(parents=True, exist_ok=True)
h.MEDIA_DIR = a.media_dir.rstrip('/')
h.PORT = a.port

def action(id):
    h.sh('CLASSPATH=/data/local/tmp/native-click.jar app_process /system/bin AccessibilityClickProbe com.fongmi.android.tv:id/' + id)
    time.sleep(.5)

def decode_label():
    h.sh('input keyevent 20')
    return next(n.get('text') for n in h.snap('controller').iter('node') if n.get('resource-id', '').endswith('/decode'))

def hide_controls():
    h.sh('input keyevent 4')
    time.sleep(.8)

def caption_box(name):
    h.image(name)
    image = Image.open(h.OUT / (name + '.png')).convert('RGB')
    assert image.size == (1920, 1080), 'fixture/ROI requires the known 1080p TV viewport'
    # Controls must be hidden. The ROI is entirely below the RGB video bars and
    # excludes the pause/status/title widgets; only white subtitle pixels may pass.
    points = [(x, y) for y in range(720, 1080) for x in range(250, 1670)
              if min(image.getpixel((x, y))) > 200]
    assert len(points) > 1000, (name, 'caption invisible', len(points))
    box = [min(x for x, y in points), min(y for x, y in points),
           max(x for x, y in points) + 1, max(y for x, y in points) + 1]
    assert box[0] > 250 and box[2] < 1670, (name, 'controls/oversized caption contaminate ROI', box)
    return box

original = None
previous = h.sh('getprop log.tag.MpvSubtitle').strip()
h.sh('setprop log.tag.MpvSubtitle DEBUG')
subprocess.run(h.D + ['forward', f'tcp:{a.port}', 'tcp:9978'], check=True)
try:
    h.launch('srt')
    h.ctrl('pause')
    h.tap(h.snap('small'), 'video')
    time.sleep(1)
    original = decode_label()
    assert original == '性能硬解', original
    hide_controls()
    data = {'embed': caption_box('embed')}
    assert h.vo(h.logs('embed')) == 'mediacodec_embed'
    decode_label()
    action('decode')
    for _ in range(40):
        if h.vo(h.logs('gpu')) == 'gpu': break
        time.sleep(.25)
    else: raise AssertionError('No GPU route')
    h.ctrl('pause')
    hide_controls()
    data['gpu'] = caption_box('gpu')
    assert h.vo(h.logs('gpu-final')) == 'gpu'
    data['edge_deltas'] = [abs(x-y) for x, y in zip(data['embed'], data['gpu'])]
    (h.OUT / 'bounds.json').write_text(json.dumps(data, indent=2), encoding='utf8')
    assert max(data['edge_deltas']) <= 3, ('subtitle size/position mismatch', data)
    print('PASS native/embed vs native/GPU SRT caption bounds', data, flush=True)
finally:
    try:
        if original:
            for _ in range(4):
                if decode_label() == original: break
                action('decode')
            assert decode_label() == original, 'Failed to restore decode preference'
    finally:
        h.sh('setprop log.tag.MpvSubtitle ' + (previous or 'INFO'))
        if h.OWNED: h.sh('rm -f ' + ' '.join(shlex.quote(f) for f in h.OWNED))
        subprocess.run(h.D + ['forward', '--remove', f'tcp:{a.port}'], check=False)
