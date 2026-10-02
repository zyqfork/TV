#!/usr/bin/env python3
"""Apply only async command/reply and post-open cancellation in an OWNED native tree.

The filename is retained for Gradle's pinned native-input contract. The old
subtitle bitmap/JNI and VO redraw patches are intentionally no longer installed:
automatic direct output is restricted to subtitle-free live playback, and mpv's
normal GPU/libass path handles every selected subtitle.
"""
import argparse
import shutil
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--root', type=Path, required=True)
p.add_argument('--native', type=Path, required=True)
a = p.parse_args()
mpv = a.root / 'buildscripts/deps/mpv'
jni = a.root / 'app/src/main/jni'

def patch(path, old, new, marker):
    value = path.read_text()
    if marker in value:
        return
    if value.count(old) != 1:
        raise RuntimeError(f'Pinned native patch mismatch: {path}: {old[:80]}')
    path.write_text(value.replace(old, new), newline='\n')

for name in ('android_command_jni.cpp', 'android_command_jni.h'):
    shutil.copyfile(a.native / name, jni / name)
patch(jni / 'event.cpp', '#include "log.h"',
      '#include "log.h"\n#include "android_command_jni.h"', 'android_command_jni.h')
patch(jni / 'event.cpp', '        case MPV_EVENT_PROPERTY_CHANGE:',
      '''        case MPV_EVENT_COMMAND_REPLY:
            android_command_reply(env, mp_event);
            break;
        case MPV_EVENT_PROPERTY_CHANGE:''', 'case MPV_EVENT_COMMAND_REPLY:')
# sub-add temporarily unlocks the core while opening a URL: cancellation must
# still prevent a completed old request from publishing into the replacement file.
patch(mpv / 'player/loadfile.c',
      '''    // The command could have overlapped with playback exiting. (We don't care
    // if playback has started again meanwhile - weird, but not a problem.)
    if (mpctx->stop_play)
        goto err_out;''',
      '''    // FONGMI_SUBTITLE_ABORT_PUBLISH: cancellation also applies after demux open.
    if (mpctx->stop_play || mp_cancel_test(cancel))
        goto err_out;''', 'FONGMI_SUBTITLE_ABORT_PUBLISH')
patch(jni / 'Android.mk', '\tthumbnail.cpp',
      '\tthumbnail.cpp \\\n\tandroid_command_jni.cpp', 'android_command_jni.cpp')
print('PASS pinned async command JNI and cancellation guard; no subtitle bitmap bridge')
