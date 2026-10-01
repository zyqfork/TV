/* FongMi bridge, MIT (same license as mpv-android's JNI). */
#include <android/bitmap.h>
#include <jni.h>
#include <stdint.h>
#include "jni_utils.h"
#include "android_subtitle_overlay.h"
#include "globals.h"

static jclass subtitle_frame_class;
static jmethodID subtitle_frame_init, subtitle_changed, bitmap_create;

static void subtitle_notify(void *)
{
    JNIEnv *env = nullptr;
    bool attached = g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED;
    if (!acquire_jni_env(g_vm, &env)) return;
    env->CallStaticVoidMethod(mpv_MPVLib, subtitle_changed);
    // A failed Java notification must never abort the native video-output thread.
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) g_vm->DetachCurrentThread();
}

extern "C" {
    jni_func(jboolean, nativeHasSubtitleOverlay);
    jni_func(void, nativeConfigureSubtitleOverlay, jint w, jint h, jboolean enabled, jlong epoch);
    jni_func(jobject, nativeReadSubtitleOverlay, jlong revision);
}

static void subtitle_bridge_init(JNIEnv *env)
{
    if (subtitle_frame_class) return;
    jclass c = env->FindClass("is/xyz/mpv/MPVLib$SubtitleOverlayFrame");
    if (!c) return;
    subtitle_frame_class = reinterpret_cast<jclass>(env->NewGlobalRef(c));
    env->DeleteLocalRef(c);
    subtitle_frame_init = env->GetMethodID(subtitle_frame_class, "<init>",
                                         "(Landroid/graphics/Bitmap;IIIIIJJD)V");
    subtitle_changed = env->GetStaticMethodID(mpv_MPVLib, "eventSubtitleOverlay", "()V");
    bitmap_create = env->GetStaticMethodID(android_graphics_Bitmap, "createBitmap",
                            "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
}

jni_func(jboolean, nativeHasSubtitleOverlay) { return JNI_TRUE; }

jni_func(void, nativeConfigureSubtitleOverlay, jint w, jint h, jboolean enabled, jlong epoch)
{
    subtitle_bridge_init(env);
    if (env->ExceptionCheck() || !subtitle_frame_init || !subtitle_changed || !bitmap_create) return;
    mpv_android_subtitle_set_notify(subtitle_notify, nullptr);
    mpv_android_subtitle_configure(w, h, enabled, epoch);
}

struct subtitle_read_context { JNIEnv *env; jobject result; };
static void subtitle_copy(void *ctx, const struct mpv_android_subtitle_frame *f,
                          const unsigned char *bgra, int stride)
{
    auto c = static_cast<subtitle_read_context *>(ctx);
    JNIEnv *env = c->env;
    jobject bitmap = nullptr;
    int error = f->error;
    if (!error && f->width > 0 && f->height > 0 && bgra) {
        jobject config = env->GetStaticObjectField(android_graphics_Bitmap_Config,
                                                   android_graphics_Bitmap_Config_ARGB_8888);
        bitmap = env->CallStaticObjectMethod(android_graphics_Bitmap, bitmap_create,
                                             f->width, f->height, config);
        env->DeleteLocalRef(config);
        if (env->ExceptionCheck()) return;
        AndroidBitmapInfo info;
        void *pixels = nullptr;
        if (!bitmap || AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
            AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
            error = 3;
        } else {
            // mpv supplies premultiplied BGRA; Android's native ARGB_8888 buffer is RGBA.
            for (int y = 0; y < f->height; y++) {
                const unsigned char *src = bgra + (size_t)y * stride;
                unsigned char *dst = static_cast<unsigned char *>(pixels) + (size_t)y * info.stride;
                for (int x = 0; x < f->width; x++, src += 4, dst += 4) {
                    dst[0] = src[2]; dst[1] = src[1]; dst[2] = src[0]; dst[3] = src[3];
                }
            }
            AndroidBitmap_unlockPixels(env, bitmap);
        }
    }
    c->result = env->NewObject(subtitle_frame_class, subtitle_frame_init, bitmap,
           f->canvas_width, f->canvas_height, f->left, f->top, error,
           static_cast<jlong>(f->epoch), static_cast<jlong>(f->revision), f->pts);
    if (bitmap) env->DeleteLocalRef(bitmap);
}

jni_func(jobject, nativeReadSubtitleOverlay, jlong revision)
{
    if (!subtitle_frame_class) return nullptr;
    subtitle_read_context c{env, nullptr};
    mpv_android_subtitle_read(revision, subtitle_copy, &c);
    return c.result;
}
