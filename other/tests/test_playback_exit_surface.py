#!/usr/bin/env python3
"""Stop playback before the home screen replaces the playback window.

mpv answers a Surface disappearing with a video-reconfig. If the decoder is still running it has
no window to attach and tears gpu-next down with "Missing surface pointer", which surfaced as a
decoding error (4003) on the home screen after pressing back.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

for flavour in ('leanback', 'mobile'):
    src = (ROOT / f'app/src/{flavour}/java/com/fongmi/android/tv/ui/activity/VideoActivity.java').read_text(encoding='utf-8')
    back = src[src.index('protected void onBackInvoked()'):]
    back = back[:back.index('\n    }')]
    assert 'suspend()' in back, (flavour, back)
    assert back.index('suspend()') < back.index('startActivity('), (flavour, back)

PLAYBACK = (ROOT / 'app/src/main/java/com/fongmi/android/tv/ui/activity/PlaybackActivity.java').read_text(encoding='utf-8')
# Detaching the view is what destroys the Surface underneath mpv, so teardown must not do it.
destroy = PLAYBACK[PLAYBACK.index('protected void onDestroy()'):]
destroy = destroy[:destroy.index('\n    }')]
assert 'detachSurface()' not in destroy, destroy

print('PASS playback stops before the home screen replaces the window')
