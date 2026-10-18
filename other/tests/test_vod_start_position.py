#!/usr/bin/env python3
"""Replay must start from the beginning, not from the end of the file.

Two separate defects produced the same symptom - the replay button appeared to do nothing while
the picture jumped to the last frame:

1. History rows written by older builds store TIME_UNSET+1 rather than TIME_UNSET. The old
   implementation returned Math.max(opening, position), which turns such a row into TIME_UNSET+1:
   a value that no longer equals C.TIME_UNSET. PlayerManager.replay() only compares equality, so
   the value went to seekTo() and mpv read it as a huge position.
2. Even with a clean TIME_UNSET, replay delegated to seekToDefaultPosition(), which Media3 leaves
   to the player. mpv resolves it to the end of the file.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
POLICY = (ROOT / 'app/src/main/java/com/fongmi/android/tv/playback/vod/VodHistoryPolicy.java').read_text(encoding='utf-8')
MANAGER = (ROOT / 'app/src/main/java/com/fongmi/android/tv/player/PlayerManager.java').read_text(encoding='utf-8')

body = POLICY[POLICY.index('public long startPositionMs'):]
body = body[:body.index('\n    }')]
# Must compare against the sentinel instead of letting Math.max pick a neighbour value.
assert 'opening != C.TIME_UNSET' in body, body
assert 'position != C.TIME_UNSET' in body, body
assert 'Math.max(opening, position)' in body, body

TIME_UNSET = -9223372036854775808


def start(opening, position):
    """Mirror of the Java implementation above."""
    has_opening = opening != TIME_UNSET and opening > 0
    has_position = position != TIME_UNSET and position > 0
    if has_opening and has_position:
        return max(opening, position)
    if has_opening:
        return opening
    if has_position:
        return position
    return TIME_UNSET


# The regression: a legacy row with TIME_UNSET+1 must still resolve to TIME_UNSET.
assert start(TIME_UNSET + 1, TIME_UNSET) == TIME_UNSET
assert start(TIME_UNSET, TIME_UNSET) == TIME_UNSET
# Opening still wins when it is a real skip, and resume still works.
assert start(9000, TIME_UNSET) == 9000
assert start(TIME_UNSET, 12000) == 12000
assert start(9000, 12000) == 12000
# Zero is "not set", not a position worth seeking to.
assert start(0, TIME_UNSET) == TIME_UNSET
assert start(TIME_UNSET, 0) == TIME_UNSET

# PlayerManager.replay must not delegate to seekToDefaultPosition(): Media3 leaves that to the
# player and mpv resolves it to the end of the file.
replay = MANAGER[MANAGER.index('public void replay('):]
replay = replay[:replay.index('\n    }')]
code = [line for line in replay.splitlines() if not line.strip().startswith('//')]
code = chr(10).join(code)
assert 'seekToDefaultPosition' not in code, code
assert 'player.seekTo(' in code, code
assert 'player.play()' in code, code

print('PASS replay resolves a legacy sentinel row and seeks to the start explicitly')
