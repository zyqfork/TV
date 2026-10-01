/* FongMi Android subtitle bridge; LGPL-2.1-or-later, matching mpv.
 * Included only by Android's mediacodec_embed VO. Video frames never enter this
 * renderer: mpv/libass/bitmap decoders supply the subtitle overlay independently.
 */
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include "android_subtitle_overlay.h"
#include "common/common.h"
#include "sub/osd.h"
#include "sub/draw_bmp.h"
#include "video/mp_image.h"

#define ANDROID_SUBTITLE_MAX_PIXELS (4096 * 2160)
static pthread_mutex_t subtitle_mutex = PTHREAD_MUTEX_INITIALIZER;
static struct {
    bool enabled;
    struct vo *vo;
    int width, height;
    int64_t epoch, config_revision;
    struct mpv_android_subtitle_frame frame;
    unsigned char *pixels;
    size_t capacity;
    mpv_android_subtitle_notify notify;
    void *notify_ctx;
} subtitle;

bool android_subtitle_active_for(struct vo *vo)
{
    pthread_mutex_lock(&subtitle_mutex);
    bool active = subtitle.enabled && subtitle.vo == vo;
    pthread_mutex_unlock(&subtitle_mutex);
    return active;
}

void mpv_android_subtitle_set_notify(mpv_android_subtitle_notify cb, void *ctx)
{
    pthread_mutex_lock(&subtitle_mutex);
    subtitle.notify = cb;
    subtitle.notify_ctx = ctx;
    pthread_mutex_unlock(&subtitle_mutex);
}

void mpv_android_subtitle_configure(int width, int height, int enabled, int64_t epoch)
{
    pthread_mutex_lock(&subtitle_mutex);
    bool valid = width > 0 && height > 0 && width <= 4096 && height <= 4096 &&
                 (int64_t)width * height <= ANDROID_SUBTITLE_MAX_PIXELS;
    if (subtitle.width != width || subtitle.height != height ||
        subtitle.enabled != (enabled && valid) || subtitle.epoch != epoch) {
        subtitle.width = valid ? width : 0;
        subtitle.height = valid ? height : 0;
        subtitle.enabled = enabled && valid;
        subtitle.epoch = epoch;
        subtitle.config_revision++;
        subtitle.frame = (struct mpv_android_subtitle_frame){
            .canvas_width = subtitle.width, .canvas_height = subtitle.height,
            .epoch = epoch, .revision = subtitle.frame.revision + 1,
            .pts = MP_NOPTS_VALUE, .error = enabled && !valid ? 1 : 0,
        };
        if (!subtitle.enabled) {
            free(subtitle.pixels);
            subtitle.pixels = NULL;
            subtitle.capacity = 0;
        }
        if (subtitle.notify) subtitle.notify(subtitle.notify_ctx);
        // A paused MediaCodec Surface has no new video frame to drive caption resize.
        // vo_redraw is thread-safe; retain ownership until uninit unregisters below.
        if (subtitle.vo) vo_redraw(subtitle.vo);
    }
    pthread_mutex_unlock(&subtitle_mutex);
}

static void android_subtitle_bind_vo(struct vo *vo)
{
    pthread_mutex_lock(&subtitle_mutex);
    subtitle.vo = vo;
    pthread_mutex_unlock(&subtitle_mutex);
}

static void android_subtitle_unbind_vo(struct vo *vo)
{
    pthread_mutex_lock(&subtitle_mutex);
    if (subtitle.vo == vo) subtitle.vo = NULL;
    pthread_mutex_unlock(&subtitle_mutex);
}

int mpv_android_subtitle_read(int64_t revision, mpv_android_subtitle_copy cb, void *ctx)
{
    pthread_mutex_lock(&subtitle_mutex);
    int changed = subtitle.frame.revision != revision;
    if (changed && cb) cb(ctx, &subtitle.frame, subtitle.pixels,
                         subtitle.frame.width * 4);
    pthread_mutex_unlock(&subtitle_mutex);
    return changed;
}

struct android_subtitle_renderer {
    struct mp_draw_sub_cache *cache;
    int64_t config_revision;
    int64_t bitmap_revision;
    double pts;
};

static void android_subtitle_clear(struct android_subtitle_renderer *r)
{
    pthread_mutex_lock(&subtitle_mutex);
    if (subtitle.frame.width || subtitle.frame.height || subtitle.frame.error) {
        subtitle.frame.width = subtitle.frame.height = 0;
        subtitle.frame.error = 0;
        subtitle.frame.revision++;
        if (subtitle.notify) subtitle.notify(subtitle.notify_ctx);
    }
    pthread_mutex_unlock(&subtitle_mutex);
    r->bitmap_revision = -1;
}

static void android_subtitle_draw(struct vo *vo,
                                 struct android_subtitle_renderer *r, double pts)
{
    pthread_mutex_lock(&subtitle_mutex);
    if (!subtitle.enabled) {
        pthread_mutex_unlock(&subtitle_mutex);
        if (r->cache) {
            talloc_free(r->cache);
            r->cache = NULL;
            r->config_revision = -1;
            r->bitmap_revision = -1;
            MP_VERBOSE(vo, "Android subtitle overlay renderer released\n");
        }
        return;
    }
    if (!vo->params || !vo->osd) {
        pthread_mutex_unlock(&subtitle_mutex);
        return;
    }
    int width = subtitle.width, height = subtitle.height;
    int64_t config = subtitle.config_revision, epoch = subtitle.epoch;
    pthread_mutex_unlock(&subtitle_mutex);

    if (!r->cache || r->config_revision != config) {
        talloc_free(r->cache);
        r->cache = mp_draw_sub_alloc(vo, vo->global);
        r->config_revision = config;
        r->bitmap_revision = -1;
    }
    // Caption canvas includes letterboxing, unlike the video decoder's buffer.
    int dw, dh;
    mp_image_params_get_dsize(vo->params, &dw, &dh);
    if (dw <= 0 || dh <= 0) return;
    double aspect = (double)dw / dh;
    int content_w = width, content_h = height;
    if ((double)width / height > aspect) content_w = MPMAX(1, height * aspect);
    else content_h = MPMAX(1, width / aspect);
    struct mp_osd_res res = {
        .w = width, .h = height, .display_par = 1,
        .ml = (width - content_w) / 2, .mr = (width - content_w + 1) / 2,
        .mt = (height - content_h) / 2, .mb = (height - content_h + 1) / 2,
    };
    // Native primary + secondary subtitles, never mpv's OSD/control bars.
    struct sub_bitmap_list *list = osd_render(vo->osd, res, pts,
                                  OSD_DRAW_SUB_ONLY, mp_draw_sub_formats);
    if (!list || list->change_id == r->bitmap_revision) {
        talloc_free(list);
        return;
    }
    struct mp_rect active[1], modified[1];
    int num_active = 0, num_modified = 0;
    struct mp_image *overlay = mp_draw_sub_overlay(r->cache, list,
                   active, 1, &num_active, modified, 1, &num_modified);
    int64_t bitmap_revision = list->change_id;
    talloc_free(list);

    pthread_mutex_lock(&subtitle_mutex);
    // Ignore a render which overlapped a file/viewport/config transition.
    if (!subtitle.enabled || config != subtitle.config_revision || epoch != subtitle.epoch) {
        pthread_mutex_unlock(&subtitle_mutex);
        return;
    }
    struct mp_rect rc = {0};
    if (overlay && num_active) rc = active[0];
    int w = rc.x1 - rc.x0, h = rc.y1 - rc.y0;
    size_t bytes = (size_t)w * h * 4;
    bool failed = !overlay;
    if (bytes > subtitle.capacity) {
        unsigned char *new_pixels = realloc(subtitle.pixels, bytes);
        if (new_pixels) {
            subtitle.pixels = new_pixels;
            subtitle.capacity = bytes;
        } else {
            failed = true;
        }
    }
    if (!failed && w && h) {
        // Copy only the cropped transparent subtitle image, NEVER the video.
        for (int y = 0; y < h; y++)
            memcpy(subtitle.pixels + (size_t)y * w * 4,
                   overlay->planes[0] + (rc.y0 + y) * overlay->stride[0] + rc.x0 * 4,
                   w * 4);
    }
    subtitle.frame = (struct mpv_android_subtitle_frame){
        .width = failed ? 0 : w, .height = failed ? 0 : h,
        .left = rc.x0, .top = rc.y0,
        .canvas_width = width, .canvas_height = height,
        .epoch = epoch, .revision = subtitle.frame.revision + 1,
        .pts = pts, .error = failed ? 2 : 0,
    };
    r->bitmap_revision = bitmap_revision;
    r->pts = pts;
    if (subtitle.notify) subtitle.notify(subtitle.notify_ctx);
    pthread_mutex_unlock(&subtitle_mutex);
}
