// JNI wrapper around the needle2 engine (libneedle.a).
//
// Mirrors the package com.cactus.needle.NeedleJNI on the Kotlin side. The C API is
// synchronous and session-global (one engine instance per process), so the Kotlin
// side serializes all calls through a mutex and dispatches on Dispatchers.IO.
//
// Return-value semantics of the needle API are undocumented; this wrapper stays
// defensive: it forwards status codes verbatim and always copies whatever the
// engine wrote into the output buffer, so the Kotlin side can parse the JSON
// even if a status code is ambiguous.

#include <jni.h>
#include <string.h>
#include "needle.h"

extern "C" JNIEXPORT jint JNICALL
Java_com_cactus_needle_NeedleJNI_nativeLoad(JNIEnv* env, jobject, jbyteArray cact) {
    jsize len = env->GetArrayLength(cact);
    if (len <= 0) return -1;
    jbyte* bytes = env->GetByteArrayElements(cact, nullptr);
    if (bytes == nullptr) return -1;
    int rc = needle_load(reinterpret_cast<const unsigned char*>(bytes),
                         static_cast<unsigned long long>(len));
    env->ReleaseByteArrayElements(cact, bytes, JNI_ABORT);
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_cactus_needle_NeedleJNI_nativeInit(JNIEnv* env, jobject,
                                            jstring systemFacts, jstring toolsJson) {
    const char* system_facts = systemFacts ? env->GetStringUTFChars(systemFacts, nullptr) : nullptr;
    const char* tools_json = toolsJson ? env->GetStringUTFChars(toolsJson, nullptr) : nullptr;
    int rc = needle_init(system_facts, tools_json, nullptr);
    if (system_facts) env->ReleaseStringUTFChars(systemFacts, system_facts);
    if (toolsJson) env->ReleaseStringUTFChars(toolsJson, tools_json);
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_cactus_needle_NeedleJNI_nativeComplete(JNIEnv* env, jobject,
                                                jstring input, jint maxNewTokens,
                                                jbyteArray out) {
    const char* input_str = env->GetStringUTFChars(input, nullptr);
    jsize out_capacity = env->GetArrayLength(out);
    jbyte* out_buf = env->GetByteArrayElements(out, nullptr);
    if (input_str == nullptr || out_buf == nullptr) {
        if (input_str) env->ReleaseStringUTFChars(input, input_str);
        if (out_buf) env->ReleaseByteArrayElements(out, out_buf, JNI_ABORT);
        return -1;
    }
    int rc = needle_complete(input_str, maxNewTokens,
                             reinterpret_cast<char*>(out_buf), out_capacity);
    env->ReleaseStringUTFChars(input, input_str);
    env->ReleaseByteArrayElements(out, out_buf, 0);
    return rc;
}

extern "C" JNIEXPORT void JNICALL
Java_com_cactus_needle_NeedleJNI_nativeReset(JNIEnv*, jobject) {
    needle_reset();
}
