#!/usr/bin/env python3
"""mpv must pin the last frame at EOF without losing the end-of-file signal.

Two halves that only work together:

- libmpv closes the file at EOF by default, which clears the rendered frame. The player page
  then shows a black surface while the app still believes it is on the finished item, so back
  does not return to the detail page and replay is unreachable.
- keep-open=yes fixes the picture but stops mpv from sending END_FILE, so the player would sit
  on PLAYING forever: no auto-advance, no STATE_ENDED, and replay never re-arms.

eof-reached is the signal that survives keep-open, so it has to be mapped back onto the
END_FILE(EOF) path.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MPVUTIL = (ROOT / 'app/src/main/java/com/fongmi/android/tv/player/mpv/MpvUtil.java').read_text(encoding='utf-8')
MPVPLAYER = (ROOT / 'media3compat/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java').read_text(encoding='utf-8')

# Picture is pinned at EOF, and an explicit mpv.conf value still wins.
assert 'if (!userOptions.containsKey("keep-open"))' in MPVUTIL
assert 'addPreInitStringOption("keep-open", "yes")' in MPVUTIL

# keep-open suppresses END_FILE, so the property observation has to carry the signal.
assert '"eof-reached"' in MPVPLAYER, 'eof-reached is not observed'
observed = MPVPLAYER[MPVPLAYER.index('OBSERVED_FLAG'):]
observed = observed[:observed.index(';')]
assert 'eof-reached' in observed, observed

# The property handler must feed the same end-of-file path END_FILE would have used.
handler = MPVPLAYER[MPVPLAYER.index('case "eof-reached"'):]
handler = handler[:handler.index('\n                }')]
assert 'handleEndFile(' in handler, handler
assert 'MpvEndFileReason.EOF' in handler, handler
# Guarded, or a stale value arriving after a new file is loaded would end the new item.
assert 'fileLoaded' in handler, handler

print('PASS mpv keeps the last frame and still reports end of file')
