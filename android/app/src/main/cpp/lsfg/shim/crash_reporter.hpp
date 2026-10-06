#pragma once
// 参考项目里的 android_shader_loader.cpp 依赖它的崩溃报告器只为了一个日志函数 ring_logf;
// 本应用有自己的日志系统,这里用一个只转发到 logcat 的最小实现顶替,不引入它的信号处理 / 崩溃报告。

#include <android/log.h>

#include <cstdarg>

namespace lsfg_android {

inline void ring_logf(const char *tag, int level, const char *fmt, ...) __attribute__((format(printf, 3, 4)));

inline void ring_logf(const char *tag, int level, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    __android_log_vprint(level, tag, fmt, ap);
    va_end(ap);
}

} // namespace lsfg_android
