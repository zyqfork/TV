#!/usr/bin/env python3
"""Apply the pinned native patches to an OWNED mpv/ffmpeg tree.

The filename is retained for Gradle's pinned native-input contract. The old
subtitle bitmap/JNI and VO redraw patches are intentionally no longer installed:
automatic direct output is restricted to subtitle-free live playback, and mpv's
normal GPU/libass path handles every selected subtitle.

Four independent patches remain. None of them is a workaround for the RK3588
C2/MPP buffer-supply stall; that defect lives in the container's
/vendor/lib/libcodec2_rk_component.so and is fixed there:

  1. async command/property replies      - libmpv's async API has no reply routing
  2. post-open cancellation guard        - a cancelled sub-add must not publish
  3. video-frame-info/pts                - a real VO frame signal for first-frame
  4. MediaCodec inclusive crop origin    - top-edge padding row fix (white line)
  5. real texture storage dimensions     - GPU sampling must use the imported size
"""
import argparse
import shutil
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--root', type=Path, required=True)
p.add_argument('--native', type=Path, required=True)
a = p.parse_args()
mpv = a.root / 'buildscripts/deps/mpv'
ffmpeg = a.root / 'buildscripts/deps/ffmpeg'
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
      '''        case MPV_EVENT_SET_PROPERTY_REPLY:
        case MPV_EVENT_COMMAND_REPLY:
            android_command_reply(env, mp_event);
            break;
        case MPV_EVENT_PROPERTY_CHANGE:''', 'case MPV_EVENT_COMMAND_REPLY:')
# Upgrade already-patched owned trees as well as fresh ones.
patch(jni / 'event.cpp', '        case MPV_EVENT_COMMAND_REPLY:',
      '        case MPV_EVENT_SET_PROPERTY_REPLY:\n        case MPV_EVENT_COMMAND_REPLY:',
      'case MPV_EVENT_SET_PROPERTY_REPLY:')
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
# Expose the VO's actual current frame timestamp, not audio-led time-pos or an
# unchanged width/height. This is a native VO-frame signal, not a physical display measurement.
patch(mpv / 'player/command.c',
      '        {"picture-type",    SUB_PROP_STR(pict_type), .unavailable = !pict_type},',
      '''        // FONGMI_VO_FRAME_PTS: force-window placeholders are NOT decoded video frames.
        {"pts", SUB_PROP_DOUBLE(f->pts),
            .unavailable = f->params.force_window || f->pts == MP_NOPTS_VALUE},
        {"picture-type",    SUB_PROP_STR(pict_type), .unavailable = !pict_type},''',
      'FONGMI_VO_FRAME_PTS')
# Preserve the decoder's declared visible rectangle on hardware frames. Unlike the
# software copy path, the hardware wrapper used to retain only cropped dimensions
# and lose nonzero origins. ImageReader can report a different/default crop, so the
# downstream GPU needs the per-frame MediaFormat origin, not a guessed border inset.
patch(ffmpeg / 'libavcodec/mediacodecdec_common.c',
      '''    AVMediaCodecBuffer *buffer = NULL;

    frame->buf[0] = NULL;
    frame->width = avctx->width;
    frame->height = avctx->height;
    frame->format = avctx->pix_fmt;''',
      '''    AVMediaCodecBuffer *buffer = NULL;

    frame->buf[0] = NULL;
    frame->width = avctx->width;
    frame->height = avctx->height;
    // FONGMI_MEDIACODEC_FRAME_CROP: inclusive MediaFormat -> AVFrame crop origin.
    // Keep ALL visible pixels, including the last source row/column. Represent
    // prefix padding in frame dimensions; the actual allocation may be larger.
    if (s->crop_left >= 0 && s->crop_top >= 0 &&
        (int64_t)s->crop_right + 1 - s->crop_left == frame->width &&
        (int64_t)s->crop_bottom + 1 - s->crop_top == frame->height &&
        frame->width > 0 && frame->height > 0 &&
        frame->width <= INT_MAX - s->crop_left &&
        frame->height <= INT_MAX - s->crop_top) {
        frame->width += s->crop_left;
        frame->height += s->crop_top;
        frame->crop_left = s->crop_left;
        frame->crop_top = s->crop_top;
    }
    frame->format = avctx->pix_fmt;''', 'FONGMI_MEDIACODEC_FRAME_CROP')
# The EGLImage covers the whole imported AHardwareBuffer, which is usually taller or
# wider than the decoder's visible rectangle. Normalize texture coordinates against
# that real storage size; mpv applies AVFrame params.crop on top of it.
patch(mpv / 'video/out/hwdec/hwdec_aimagereader.c',
      '''    gl->BindTexture(GL_TEXTURE_EXTERNAL_OES, p->gl_texture);
    p->EGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, p->egl_image);
    gl->BindTexture(GL_TEXTURE_EXTERNAL_OES, 0);

    return 0;
}''',
      '''    gl->BindTexture(GL_TEXTURE_EXTERNAL_OES, p->gl_texture);
    p->EGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, p->egl_image);
    gl->BindTexture(GL_TEXTURE_EXTERNAL_OES, 0);

    // FONGMI_IMAGE_STORAGE_SIZE: expose real storage dimensions to GPU sampling.
    mapper->tex[0]->params.w = (int)d.width;
    mapper->tex[0]->params.h = (int)d.height;

    return 0;
}''', 'FONGMI_IMAGE_STORAGE_SIZE')
print('PASS pinned async JNI, cancellation, VO-frame signal, crop origin and real texture storage; no bitmap bridge')
