#pragma once
// 检测程序的公共小工具:文本输出、计时、Vulkan 辅助。
#include <android/log.h>
#include <volk.h>

#include <algorithm>
#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <string>
#include <vector>

#define PTAG "probe"
#define PLOGI(...) __android_log_print(ANDROID_LOG_INFO, PTAG, __VA_ARGS__)
#define PLOGE(...) __android_log_print(ANDROID_LOG_ERROR, PTAG, __VA_ARGS__)

// 每个测试把结果写成多行文本返回给 Java;同时写进 logcat,崩溃时 logcat 里还能看到做到哪一步
struct Out {
    std::string s;
    void line(const char *fmt, ...) __attribute__((format(printf, 2, 3))) {
        char buf[2048];
        va_list ap;
        va_start(ap, fmt);
        vsnprintf(buf, sizeof buf, fmt, ap);
        va_end(ap);
        s += buf;
        s += '\n';
        __android_log_print(ANDROID_LOG_INFO, PTAG, "%s", buf);
    }
};

inline double nowMs() {
    using namespace std::chrono;
    return duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
}

struct Stats {
    double mean = 0, median = 0, p95 = 0, mn = 0, mx = 0;
};
inline Stats stats(std::vector<double> v) {
    Stats r;
    if (v.empty()) return r;
    std::sort(v.begin(), v.end());
    double sum = 0;
    for (double x : v) sum += x;
    r.mean = sum / v.size();
    r.median = v[v.size() / 2];
    r.p95 = v[std::min(v.size() - 1, static_cast<size_t>(v.size() * 0.95))];
    r.mn = v.front();
    r.mx = v.back();
    return r;
}

const char *vkResultName(VkResult r);

// ---- 驱动选择(driver.cpp)----
// path 为空 = 系统 Vulkan 加载器;否则 dlopen 这个 .so,直接用它的 vk_icdGetInstanceProcAddr / vkGetInstanceProcAddr。
bool driverSelect(const std::string &path, Out &out);
// 初始化 volk 函数指针(按当前选择)
bool driverVolkInit(Out &out);

// ---- 各测试(返回文本)----
std::string runVkInfo(const std::string &driver);
std::string runVkCompute(const std::string &driver);
std::string runGlInterop();
std::string runLsfgBench(const std::string &driver, const std::string &cacheDir, const std::string &framesPath,
                         const std::string &thumbPath, int w, int h, float flow, int variant, bool perf, int generated,
                         int nFrames, int iterations, float paceMs,
                         const std::string &dumpPath, int dumpW, int dumpH, uint32_t flags);
std::string runExtract(const std::string &dll, const std::string &cacheDir);
