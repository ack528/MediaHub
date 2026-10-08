#include <jni.h>

#include "common.hpp"

namespace {
std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return "";
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}
jstring ret(JNIEnv *env, const std::string &s) { return env->NewStringUTF(s.c_str()); }
} // namespace

extern "C" {

JNIEXPORT jstring JNICALL Java_com_localtg_probe_Native_vkInfo(JNIEnv *env, jclass, jstring driver) {
    return ret(env, runVkInfo(jstr(env, driver)));
}

JNIEXPORT jstring JNICALL Java_com_localtg_probe_Native_vkCompute(JNIEnv *env, jclass, jstring driver) {
    return ret(env, runVkCompute(jstr(env, driver)));
}

JNIEXPORT jstring JNICALL Java_com_localtg_probe_Native_glInterop(JNIEnv *env, jclass) {
    return ret(env, runGlInterop());
}

JNIEXPORT jstring JNICALL Java_com_localtg_probe_Native_extract(JNIEnv *env, jclass, jstring dll, jstring cache) {
    return ret(env, runExtract(jstr(env, dll), jstr(env, cache)));
}

JNIEXPORT jstring JNICALL Java_com_localtg_probe_Native_lsfgBench(JNIEnv *env, jclass, jstring driver, jstring cache, jstring frames, jstring thumb, jint w, jint h,
                                                                    jfloat flow, jint variant, jboolean perf, jint generated, jint nFrames, jint iterations, jfloat paceMs, jstring dumpPath, jint dumpW, jint dumpH, jint flags) {
    return ret(env, runLsfgBench(jstr(env, driver), jstr(env, cache), jstr(env, frames), jstr(env, thumb), w, h, flow, variant, perf, generated, nFrames, iterations, paceMs, jstr(env, dumpPath), dumpW, dumpH, static_cast<uint32_t>(flags)));
}

} // extern "C"
