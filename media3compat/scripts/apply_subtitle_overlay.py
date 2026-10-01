#!/usr/bin/env python3
"""Apply the pinned Android subtitle-only VO/JNI bridge in an OWNED dependency tree.
Repeated execution is idempotent; upstream source drift fails rather than silently patching.
"""
import argparse,shutil
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--native',type=Path,required=True);a=p.parse_args()
mpv=a.root/'buildscripts/deps/mpv';jni=a.root/'app/src/main/jni'
def patch(path,old,new,marker):
 text=path.read_text()
 if marker in text:return
 if text.count(old)!=1:raise RuntimeError(f'Pinned native patch mismatch: {path}: {old[:80]}')
 path.write_text(text.replace(old,new),newline='\n')
for name in ['android_subtitle_overlay.c','android_subtitle_overlay.h']:
 shutil.copyfile(a.native/name,mpv/'video/out'/name)
shutil.copyfile(a.native/'android_subtitle_overlay.h',jni/'android_subtitle_overlay.h')
shutil.copyfile(a.native/'android_subtitle_jni.cpp',jni/'android_subtitle_jni.cpp')
v=mpv/'video/out/vo_mediacodec_embed.c'
patch(v,'struct priv {','// FONGMI_SUBTITLE_BRIDGE\n#include "android_subtitle_overlay.c"\n\nstruct priv {\n    struct android_subtitle_renderer subtitles;', 'FONGMI_SUBTITLE_BRIDGE')
patch(v,'    talloc_free(p->next_image);','    if (frame->current) p->subtitles.pts = frame->current->pts;\n    android_subtitle_draw(vo, &p->subtitles, p->subtitles.pts);\n    talloc_free(p->next_image);','android_subtitle_draw(vo, &p->subtitles, p->subtitles.pts);\n    talloc_free')
patch(v,'    return VO_NOTIMPL;','    struct priv *p = vo->priv;\n    if (request == VOCTRL_REDRAW) {\n        android_subtitle_draw(vo, &p->subtitles, p->subtitles.pts);\n        return VO_TRUE;\n    }\n    if (request == VOCTRL_RESET) {\n        android_subtitle_clear(&p->subtitles);\n        p->subtitles.pts = MP_NOPTS_VALUE;\n        return VO_TRUE;\n    }\n    return VO_NOTIMPL;','request == VOCTRL_REDRAW')
patch(v,'    hwdec_devices_remove(vo->hwdec_devs, &p->hwctx);','    android_subtitle_clear(&p->subtitles);\n    talloc_free(p->subtitles.cache);\n    hwdec_devices_remove(vo->hwdec_devs, &p->hwctx);','talloc_free(p->subtitles.cache)')
patch(v,'    hwdec_devices_add(vo->hwdec_devs, &p->hwctx);','    hwdec_devices_add(vo->hwdec_devs, &p->hwctx);\n    p->subtitles.pts = MP_NOPTS_VALUE;\n    android_subtitle_bind_vo(vo);','android_subtitle_bind_vo(vo)')
patch(v,'    android_subtitle_clear(&p->subtitles);\n    talloc_free(p->subtitles.cache);','    android_subtitle_unbind_vo(vo);\n    android_subtitle_clear(&p->subtitles);\n    talloc_free(p->subtitles.cache);','android_subtitle_unbind_vo(vo)')
# Keep NORETAIN for MediaCodec. A redraw can update captions without retaining or
# re-releasing the video frame; existing NORETAIN drivers may simply return NOTIMPL.
patch(mpv/'video/out/vo.c',
'''    if (vo->driver->caps & (VO_CAP_NORETAIN | VO_CAP_UNTIMED)) {
        mp_mutex_unlock(&in->lock);
        return;
    }''',
'''    if (vo->driver->caps & (VO_CAP_NORETAIN | VO_CAP_UNTIMED)) {
        mp_mutex_unlock(&in->lock);
        // FONGMI_SUBTITLE_REDRAW: update overlays without a retained video frame.
        if (vo->driver->caps & VO_CAP_NORETAIN)
            vo->driver->control(vo, VOCTRL_REDRAW, NULL);
        return;
    }''','FONGMI_SUBTITLE_REDRAW')
# Re-selecting SRT while paused can decode its packet AFTER the initial OSD/config
# redraw. Wake the embed overlay when that packet is actually ready, not on a timer,
# and do not retain/re-release video or affect other video output drivers.
patch(mpv/'player/sub.c', '#include "video/mp_image.h"',
      '#include "video/mp_image.h"\n#include "video/out/android_subtitle_overlay.h"',
      'video/out/android_subtitle_overlay.h')
patch(mpv/'player/sub.c', '    track->redraw_subs = false;',
'''    // FONGMI_SUBTITLE_READY_REDRAW: paused track re-selection finishes asynchronously.
    if (sub_updated && mpctx->paused && mpctx->video_out &&
        android_subtitle_active_for(mpctx->video_out))
        vo_redraw(mpctx->video_out);

    track->redraw_subs = false;''', 'FONGMI_SUBTITLE_READY_REDRAW')
patch(mpv/'player/playloop.c', '#include "video/out/vo.h"',
      '#include "video/out/vo.h"\n#include "video/out/android_subtitle_overlay.h"',
      'video/out/android_subtitle_overlay.h')
patch(mpv/'player/playloop.c',
      'static void handle_update_subtitles(struct MPContext *mpctx)\n{',
'''static void handle_update_subtitles(struct MPContext *mpctx)
{
    // FONGMI_SUBTITLE_PAUSED_PACKETS: a demux refresh can report ready before
    // the current subtitle packet arrives. Consume it on existing core/demux
    // wakeups while paused; update_subtitle only redraws actual state changes.
    // No added timer, Java polling, seek, or video frame advancement.
    if (mpctx->paused && mpctx->video_out &&
        android_subtitle_active_for(mpctx->video_out)) {
        update_subtitles(mpctx, mpctx->playback_pts);
        return;
    }''', 'FONGMI_SUBTITLE_PAUSED_PACKETS')
patch(jni/'Android.mk','\tthumbnail.cpp','\tthumbnail.cpp \\\n\tandroid_subtitle_jni.cpp','\tandroid_subtitle_jni.cpp')
patch(jni/'Android.mk','LOCAL_LDLIBS    := -llog -latomic','LOCAL_LDLIBS    := -llog -latomic -ljnigraphics','-ljnigraphics')
patch(jni/'main.cpp','#include "event.h"','#include "event.h"\n#include "android_subtitle_overlay.h"','android_subtitle_overlay.h')
patch(jni/'main.cpp','    mpv_terminate_destroy(g_mpv);','    // Wait for any pending VO notification before dropping Java/JNI ownership.\n    mpv_android_subtitle_set_notify(nullptr, nullptr);\n    mpv_android_subtitle_configure(0, 0, 0, 0);\n    mpv_terminate_destroy(g_mpv);','mpv_android_subtitle_set_notify(nullptr, nullptr)')
print('PASS pinned subtitle-only VO, redraw and JNI patches')
