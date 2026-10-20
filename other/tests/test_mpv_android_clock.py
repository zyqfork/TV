"""Compile the pinned clock-selection patch; reject the old Android RAW clock.
Usage: python3 other/tests/test_mpv_android_clock.py /path/to/mpv/osdep/timer-linux.c
The supplied source may be patched or pristine. No Android device is needed.
"""
import ast
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[2]
overlay = (root / 'media3compat/scripts/apply_subtitle_overlay.py').read_text(encoding='utf8')
call = next(n for n in ast.walk(ast.parse(overlay))
            if isinstance(n, ast.Call) and isinstance(n.func, ast.Name)
            and n.func.id == 'patch' and len(n.args) == 4
            and isinstance(n.args[3], ast.Constant)
            and n.args[3].value == 'FONGMI_ANDROID_MONOTONIC')
old, new = (n.value for n in call.args[1:3])
source = Path(sys.argv[1]).read_text(encoding='utf8').replace(new, old)
assert source.count(old) == 1
# Compile the real pinned clock-selection function, with deterministic clock readings.
body = source[source.index('void mp_raw_time_init(void)'):]
assert body.rstrip().endswith('}')
harness = r'''
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
typedef int clockid_t;
struct timespec { long tv_sec, tv_nsec; };
#define CLOCK_MONOTONIC_RAW 4
#define CLOCK_MONOTONIC 1
#define MP_ARRAY_SIZE(a) ((int)(sizeof(a) / sizeof((a)[0])))
static clockid_t clk_id;
static int clock_gettime(clockid_t id, struct timespec *tp) {
    tp->tv_sec = 100;
    tp->tv_nsec = 0;
#ifdef RAW_UNAVAILABLE
    if (id == CLOCK_MONOTONIC_RAW) return -1;
#else
    (void)id;
#endif
    return 0;
}
BODY
int main(void) {
    mp_raw_time_init();
    if (clk_id != EXPECTED) return 10;
#ifdef __ANDROID__
    // The actual AO extrapolates AudioTimestamp.framePosition using now - nanoTime.
    // A 1.345676s RAW/MONOTONIC offset must not become fictitious queued audio.
    double now = clk_id == CLOCK_MONOTONIC ? 100.0 : 98.654324;
    uint32_t played = 441000;
    played += (now - 100.0) * 44100;
    assert(played == 441000);
#endif
    return 0;
}
'''
cc = os.environ.get('CC') or shutil.which('cc') or shutil.which('gcc')
if not cc:
    raise RuntimeError('Set CC to a host C compiler; no mock pass is permitted')
with tempfile.TemporaryDirectory() as tmp:
    c = Path(tmp) / 'clock.c'
    exe = Path(tmp) / ('clock.exe' if os.name == 'nt' else 'clock')
    cases = [(['-D__ANDROID__', '-DEXPECTED=1'], True),
             (['-DEXPECTED=4'], True),
             (['-DRAW_UNAVAILABLE', '-DEXPECTED=1'], True),
             (['-D__ANDROID__', '-DEXPECTED=1'], False)]
    for flags, patched in cases:
        c.write_text(harness.replace('BODY', body.replace(old, new) if patched else body),
                     encoding='utf8')
        subprocess.run([cc, '-std=c11', '-Wall', '-Wextra', *flags,
                        str(c), '-o', str(exe)], check=True)
        result = subprocess.run([str(exe)], check=False)
        assert result.returncode == (0 if patched else 10), (flags, patched, result.returncode)
print('PASS: Android MONOTONIC, unchanged Linux RAW/fallback, and pre-fix Android regression rejected')
