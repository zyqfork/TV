/* FongMi Android subtitle bridge; LGPL-2.1-or-later, matching mpv. */
#ifndef MPV_ANDROID_SUBTITLE_OVERLAY_H
#define MPV_ANDROID_SUBTITLE_OVERLAY_H
#include <stdint.h>
#include <stdbool.h>
#include <mpv/client.h>
#ifdef __cplusplus
extern "C" {
#endif
struct vo;
/* Core subtitle decoder uses this to redraw only the active embed overlay on pause. */
bool android_subtitle_active_for(struct vo *vo);
struct mpv_android_subtitle_frame {
    int width, height, left, top, canvas_width, canvas_height;
    int error;
    int64_t epoch, revision;
    double pts;
};
typedef void (*mpv_android_subtitle_notify)(void *ctx);
typedef void (*mpv_android_subtitle_copy)(void *ctx,
        const struct mpv_android_subtitle_frame *frame,
        const unsigned char *bgra, int stride);
/* Process-global, just like the mpv-android JNI bridge. Caller owns one mpv instance. */
MPV_EXPORT void mpv_android_subtitle_configure(int width, int height, int enabled,
                                             int64_t epoch);
/* Unregister before terminating mpv; registration serializes with pending callbacks. */
MPV_EXPORT void mpv_android_subtitle_set_notify(mpv_android_subtitle_notify cb, void *ctx);
/* Callback is synchronous, data valid only during this call; never call mpv from it. */
MPV_EXPORT int mpv_android_subtitle_read(int64_t revision,
                                      mpv_android_subtitle_copy cb, void *ctx);
#ifdef __cplusplus
}
#endif
#endif
