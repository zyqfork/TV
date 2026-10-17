#!/usr/bin/env python3
"""Notification churn and spider-log flooding must not come back.

1. Media3 creates a fresh PendingIntent for every setMediaButtonPreferences call, so rebuilding
   the media-button row on every repeat callback piles up pending intents until the platform
   rate-limits the app ("Too many PendingIntent created for uid", "Package enqueue rate is ...").
2. A spider that logs the same line every frame floods logcat and buries real errors.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SERVICE = (ROOT / 'app/src/main/java/com/fongmi/android/tv/service/PlaybackService.java').read_text(encoding='utf-8')
DEBUG = (ROOT / 'catvod/src/main/java/com/github/catvod/crawler/SpiderDebug.java').read_text(encoding='utf-8')

# The repeat listener must go through the guarded helper, never rebuild unconditionally.
listener = SERVICE[SERVICE.index('public void onRepeatModeChanged'):]
listener = listener[:listener.index('}', listener.index('{'))]
assert 'updateRepeatButton()' in listener, listener
assert 'setMediaButtonPreferences' not in listener

helper = SERVICE[SERVICE.index('private void updateRepeatButton'):]
helper = helper[:helper.index('\n    }')]
assert 'repeatOne == lastRepeatOne' in helper, helper
# The cached value must be seeded, or the first real toggle would be swallowed.
assert 'lastRepeatOne = player.isRepeatOne();' in SERVICE
assert SERVICE.index('lastRepeatOne = player.isRepeatOne();') < SERVICE.index('private void updateRepeatButton')

# SpiderDebug: the single-argument overload (third-party spiders) is throttled; the formatted
# overload is not, because equal format strings can carry different arguments.
assert 'REPEAT_WINDOW_MS' in DEBUG
assert 'accept(msg)' in DEBUG
formatted = DEBUG[DEBUG.index('public static void log(String tag, String msg'):]
formatted = formatted[:formatted.index('\n    }')]
assert 'accept' not in formatted, formatted

# Behavioural check of the throttle itself, mirroring the Java logic.
WINDOW = 5000
state = {'msg': None, 'at': -WINDOW}

def accept(msg, now):
    if msg == state['msg'] and now - state['at'] < WINDOW:
        return False
    state['msg'], state['at'] = msg, now
    return True

# 1968 identical frames over 16 minutes: only the first survives inside each 5s window.
kept = sum(accept('VideoActivity', t) for t in range(0, 960_000, 500))
assert kept == 192, kept
# A changing message is never suppressed.
state.update(msg=None, at=-WINDOW)
assert all(accept(f'line {i}', i * 1000) for i in range(20))

print('PASS notification rebuild is de-duplicated; spider log repeats are throttled')
