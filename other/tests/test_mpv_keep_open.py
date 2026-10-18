#!/usr/bin/env python3
"""mpv must keep the last frame at EOF, or the player page goes black with no way back.

libmpv closes the file at EOF by default, which clears the rendered frame. The page then shows
a black surface while the app still believes it is on the finished item: back does not return to
the detail page and replay is not reachable. keep-open only pins the picture; mpv still reports
END_FILE(EOF), so auto-advance and STATE_ENDED keep working.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MPVUTIL = (ROOT / 'app/src/main/java/com/fongmi/android/tv/player/mpv/MpvUtil.java').read_text(encoding='utf-8')

assert 'keep-open' in MPVUTIL, 'MpvUtil does not pin keep-open'
# A user's mpv.conf must still win, like every other option here.
assert 'if (!userOptions.containsKey("keep-open"))' in MPVUTIL
assert 'addPreInitStringOption("keep-open", "yes")' in MPVUTIL

print('PASS mpv keeps the last frame at EOF')
