/* FongMi asynchronous command bridge, MIT. */
#pragma once
#include <jni.h>
#include <mpv/client.h>

void android_command_reply(JNIEnv *env, const mpv_event *event);
