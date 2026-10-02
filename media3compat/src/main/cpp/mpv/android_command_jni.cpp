/* FongMi asynchronous command bridge, MIT (same license as mpv-android JNI). */
#include <jni.h>
#include <stdint.h>
#include <string>
#include <vector>
#include <mpv/client.h>
#include "jni_utils.h"
#include "globals.h"
#include "android_command_jni.h"

void android_command_reply(JNIEnv *env, const mpv_event *event)
{
    static jmethodID reply;
    if (!reply) reply = env->GetStaticMethodID(mpv_MPVLib, "eventCommandReply", "(JI)V");
    if (!env->ExceptionCheck() && reply)
        env->CallStaticVoidMethod(mpv_MPVLib, reply,
                                 static_cast<jlong>(event->reply_userdata), event->error);
    // Optional subtitle notifications must not terminate the native event loop.
    if (env->ExceptionCheck()) env->ExceptionClear();
}

extern "C" {
    jni_func(jint, nativeCommandAsync, jlong id, jobjectArray commands);
    jni_func(void, nativeAbortAsyncCommand, jlong id);
}

jni_func(jint, nativeCommandAsync, jlong id, jobjectArray commands)
{
    // Java owns the global handle on its application thread, including stop/release.
    if (!g_mpv) return MPV_ERROR_UNINITIALIZED;
    if (!commands || id <= 0) return MPV_ERROR_INVALID_PARAMETER;
    jsize count = env->GetArrayLength(commands);
    if (count <= 0 || count >= 128) return MPV_ERROR_INVALID_PARAMETER;
    std::vector<std::string> values;
    values.reserve(count);
    for (jsize i = 0; i < count; ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(commands, i));
        if (env->ExceptionCheck()) return MPV_ERROR_INVALID_PARAMETER;
        if (!value) return MPV_ERROR_INVALID_PARAMETER;
        const char *utf = env->GetStringUTFChars(value, nullptr);
        if (!utf) {
            env->DeleteLocalRef(value);
            return MPV_ERROR_NOMEM;
        }
        values.emplace_back(utf);
        env->ReleaseStringUTFChars(value, utf);
        env->DeleteLocalRef(value);
    }
    std::vector<const char *> args;
    args.reserve(count + 1);
    for (const auto &value : values) args.push_back(value.c_str());
    args.push_back(nullptr);
    // libmpv copies arguments before returning. HTTP/demux work runs on its worker thread.
    return mpv_command_async(g_mpv, static_cast<uint64_t>(id), args.data());
}

jni_func(void, nativeAbortAsyncCommand, jlong id)
{
    if (g_mpv && id > 0) mpv_abort_async_command(g_mpv, static_cast<uint64_t>(id));
}
