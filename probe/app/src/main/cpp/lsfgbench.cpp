// LSFG(Lossless Scaling 帧生成)基准:和主程序完全同一条路径 ——
//   GL 把视频帧画进 AHB 输入槽 → framegen(Vulkan,独立设备)presentContext → GL 读 AHB 输出。
// 每个配置在独立进程里跑,这里不做任何"失败后自动回退",要看到驱动的原始表现。
#include "gl.hpp"

#include <cmath>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <fstream>
#include <thread>

#include "android_shader_loader.hpp"
#include "spvpatch.hpp"
#include <atomic>
#include "lsfg_3_1.hpp"
#include "lsfg_3_1p.hpp"

extern uint32_t g_probeFlags; // 定义在补丁后的 framegen/src/core/instance.cpp
namespace LSFG_3_1 { bool probeReadOutput(int32_t id, size_t idx, uint8_t* dst); bool probeWriteInput(int32_t id, size_t slot, const uint8_t* src); bool probeDumpStages(int32_t id, const char* path); }
namespace LSFG_3_1P { bool probeReadOutput(int32_t id, size_t idx, uint8_t* dst); bool probeWriteInput(int32_t id, size_t slot, const uint8_t* src); bool probeDumpStages(int32_t id, const char* path); }

namespace {

// 0.6 起的实验开关:生成完成后等 3ms + glFinish 再让 GL 读输出
constexpr bool kSyncTest = false;

struct Frames {
    int w = 0, h = 0, n = 0;
    std::vector<uint8_t> data;
    const uint8_t *at(int i) const { return data.data() + static_cast<size_t>(i % n) * w * h * 4; }
};

bool loadFrames(const std::string &path, int w, int h, int n, Frames &f, Out &o) {
    std::ifstream in(path, std::ios::binary);
    if (!in) { o.line("打不开帧文件 %s", path.c_str()); return false; }
    f.w = w; f.h = h; f.n = n;
    f.data.resize(static_cast<size_t>(w) * h * 4 * n);
    in.read(reinterpret_cast<char *>(f.data.data()), f.data.size());
    if (static_cast<size_t>(in.gcount()) != f.data.size()) { o.line("帧文件大小不对(读到 %zd 字节,需要 %zu)", (ssize_t)in.gcount(), f.data.size()); return false; }
    return true;
}

bool queryDevice(uint64_t &uuid, std::string &name, uint32_t &api, Out &o) {
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "probe";
    app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ci.pApplicationInfo = &app;
    VkInstance inst = VK_NULL_HANDLE;
    VkResult r = vkCreateInstance(&ci, nullptr, &inst);
    if (r != VK_SUCCESS) { o.line("vkCreateInstance 失败: %s", vkResultName(r)); return false; }
    volkLoadInstance(inst);
    uint32_t n = 0;
    vkEnumeratePhysicalDevices(inst, &n, nullptr);
    if (!n) { vkDestroyInstance(inst, nullptr); o.line("没有物理设备"); return false; }
    std::vector<VkPhysicalDevice> devs(n);
    vkEnumeratePhysicalDevices(inst, &n, devs.data());
    VkPhysicalDeviceProperties p{};
    vkGetPhysicalDeviceProperties(devs[0], &p);
    uuid = (static_cast<uint64_t>(p.vendorID) << 32) | p.deviceID;
    name = p.deviceName;
    api = p.apiVersion;
    vkDestroyInstance(inst, nullptr);
    return true;
}

// RGBA → 缩小(最近邻)→ I420 追加写入文件。输出视频的每个"时间步"一帧,ffmpeg 用 -f rawvideo -pix_fmt yuv420p 读。
void writeI420(std::ofstream &f, const uint8_t *rgba, int W, int H, int dw, int dh) {
    std::vector<uint8_t> buf(static_cast<size_t>(dw) * dh * 3 / 2);
    uint8_t *Y = buf.data(), *U = Y + dw * dh, *V = U + dw * dh / 4;
    for (int y = 0; y < dh; y++)
        for (int x = 0; x < dw; x++) {
            const uint8_t *p = rgba + (static_cast<size_t>(y * H / dh) * W + x * W / dw) * 4;
            Y[y * dw + x] = static_cast<uint8_t>(((66 * p[0] + 129 * p[1] + 25 * p[2] + 128) >> 8) + 16);
            if (!(x & 1) && !(y & 1)) {
                U[(y / 2) * (dw / 2) + x / 2] = static_cast<uint8_t>(((-38 * p[0] - 74 * p[1] + 112 * p[2] + 128) >> 8) + 128);
                V[(y / 2) * (dw / 2) + x / 2] = static_cast<uint8_t>(((112 * p[0] - 94 * p[1] - 18 * p[2] + 128) >> 8) + 128);
            }
        }
    f.write(reinterpret_cast<const char *>(buf.data()), buf.size());
}

double mad(const uint8_t *a, const uint8_t *b, int w, int h) {
    double s = 0;
    long n = 0;
    for (int y = 0; y < h; y += 4)
        for (int x = 0; x < w; x += 4) {
            size_t i = (static_cast<size_t>(y) * w + x) * 4;
            s += std::abs(a[i] - b[i]) + std::abs(a[i + 1] - b[i + 1]) + std::abs(a[i + 2] - b[i + 2]);
            n += 3;
        }
    return s / n;
}

struct Cmp {
    double motion = 0;     // prev 与 cur 的平均绝对差(画面运动量)
    double vsAvg = 0;      // 输出与 (prev+cur)/2 的平均绝对差
    double vsPrev = 0, vsCur = 0;
    double black = 0;      // 全黑像素比例
    double luma = 0;
};

Cmp compare(const uint8_t *prev, const uint8_t *cur, const uint8_t *out, int w, int h) {
    Cmp c;
    double sMotion = 0, sAvg = 0, sP = 0, sC = 0, sLuma = 0;
    long cnt = 0, blk = 0;
    for (int y = 0; y < h; y += 4)
        for (int x = 0; x < w; x += 4) {
            size_t i = (static_cast<size_t>(y) * w + x) * 4;
            int ob = 0;
            for (int k = 0; k < 3; k++) {
                int p = prev[i + k], q = cur[i + k], o = out[i + k];
                sMotion += std::abs(p - q);
                sAvg += std::abs(o - (p + q) / 2.0);
                sP += std::abs(o - p);
                sC += std::abs(o - q);
                sLuma += o;
                ob += o;
            }
            if (ob == 0) blk++;
            cnt++;
        }
    c.motion = sMotion / (cnt * 3);
    c.vsAvg = sAvg / (cnt * 3);
    c.vsPrev = sP / (cnt * 3);
    c.vsCur = sC / (cnt * 3);
    c.luma = sLuma / (cnt * 3);
    c.black = 100.0 * blk / cnt;
    return c;
}

} // namespace

std::string runExtract(const std::string &dll, const std::string &cacheDir) {
    Out o;
    double t0 = nowMs();
    try {
        int rc = lsfg_android::extract_dll_to_spirv(dll, cacheDir);
        o.line("提取着色器 rc=%d 耗时 %.0f ms", rc, nowMs() - t0);
        o.line("FP16 着色器齐全=%d  FP32(SPIR-V)着色器齐全=%d", (int)lsfg_android::fp16_shaders_available(cacheDir), (int)lsfg_android::fp32_spirv_shaders_available(cacheDir));
        o.line(rc == 0 ? "RESULT: OK" : "RESULT: FAIL 提取失败");
    } catch (const std::exception &e) {
        o.line("提取异常: %s", e.what());
        o.line("RESULT: FAIL");
    }
    return o.s;
}

std::string runLsfgBench(const std::string &driver, const std::string &cacheDir, const std::string &framesPath, const std::string &thumbPath,
                         int W, int H, float flow, int variant, bool perf, int generated, int nFrames, int iterations, float paceMs,
                         const std::string &dumpPath, int dumpW, int dumpH, uint32_t flags) {
    Out o;
    const char *vname = variant == 16 ? "FP16" : variant == 32 ? "FP32" : "DXBC";
    o.line("配置: %dx%d flowScale=%.2f 着色器=%s 模式=%s 每帧生成=%d 帧数=%d 迭代=%d 节奏=%s", W, H, flow, vname, perf ? "3.1P(性能)" : "3.1", generated, nFrames, iterations, paceMs > 0 ? (std::to_string(paceMs) + "ms/帧(模拟真实播放)").c_str() : "连续(不限速)");

    if (!driverSelect(driver, o)) { o.line("RESULT: FAIL 驱动加载失败"); return o.s; }
    if (!driverVolkInit(o)) { o.line("RESULT: FAIL 没有 Vulkan"); return o.s; }
    g_probeFlags = flags;
    o.line("实验开关 flags=%u:%s%s%s%s%s%s%s%s%s%s%s", flags, (flags & 1) ? " 全屏障" : "", (flags & 2) ? " 串行提交" : "", (flags & 4) ? " 线性AHB" : "",
           (flags & 8) ? " 清零后备图像(不用空描述符)" : "", (flags & 16) ? " 不设LOW优先级" : "", (flags & 32) ? " CPU读回输出" : "", (flags & 64) ? " Vulkan读回输出" : "", (flags & 128) ? " Vulkan上传输入" : "", (flags & 512) ? " 导出中间图像" : "", (flags & 1024) ? " 去ConstOffset" : "", (flags & 2048) ? " 采样器夹边" : "");
    const uint64_t ahbExtra = (flags & 4) ? (AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN) : 0;

    Frames frames;
    if (!loadFrames(framesPath, W, H, nFrames, frames, o)) { o.line("RESULT: FAIL 帧数据不可用"); return o.s; }

    GlCtx g;
    if (!g.init(o) || !g.hasAhbInterop() || !g.initBlit(o)) { g.destroy(); o.line("RESULT: FAIL GL 环境不可用"); return o.s; }

    uint64_t uuid = 0; std::string gpu; uint32_t api = 0;
    if (!queryDevice(uuid, gpu, api, o)) { g.destroy(); o.line("RESULT: FAIL"); return o.s; }
    o.line("GPU: %s Vulkan %u.%u.%u uuid=0x%llx", gpu.c_str(), VK_VERSION_MAJOR(api), VK_VERSION_MINOR(api), VK_VERSION_PATCH(api), (unsigned long long)uuid);

    const bool fp16Ok = lsfg_android::fp16_shaders_available(cacheDir);
    const bool fp32Ok = lsfg_android::fp32_spirv_shaders_available(cacheDir);
    if ((variant == 16 && !fp16Ok) || (variant == 32 && !fp32Ok)) {
        o.line("着色器缓存缺少 %s 变体(Lossless.dll 没提取成功?)", vname);
        g.destroy();
        o.line("RESULT: FAIL 缺着色器");
        return o.s;
    }
    static std::atomic<int> spvPatched{0};
    spvPatched = 0;
    auto loader = [cacheDir, variant, flags](const std::string &name) -> std::vector<uint8_t> {
        std::vector<uint8_t> spirv;
        if (variant == 16) {
            uint32_t id = lsfg_android::shader_name_to_resource_id_fp16(name);
            if (id) spirv = lsfg_android::load_cached_spirv(cacheDir, id, lsfg_android::ShaderCache::Fp16Spirv);
        } else if (variant == 32) {
            uint32_t id = lsfg_android::shader_name_to_resource_id_fp32_spirv(name);
            if (id) spirv = lsfg_android::load_cached_spirv(cacheDir, id, lsfg_android::ShaderCache::Fp32Spirv);
        } else {
            uint32_t id = lsfg_android::shader_name_to_resource_id(name);
            if (id) spirv = lsfg_android::load_cached_spirv(cacheDir, id, lsfg_android::ShaderCache::Dxbc);
        }
        if (spirv.empty()) __android_log_print(ANDROID_LOG_ERROR, PTAG, "着色器 '%s' 缺失", name.c_str());
        if (!spirv.empty() && (flags & 1024)) { // 去掉 ConstOffset(见 spvpatch.cpp)
            int n = 0;
            spirv = spvRemoveConstOffset(spirv, &n);
            spvPatched += n;
        }
        return spirv;
    };

    int ctxId = -1;
    bool inited = false, failed = false, deviceLost = false;
    std::string failMsg;
    AHardwareBuffer *in[2] = {nullptr, nullptr};
    std::vector<AHardwareBuffer *> outs;
    std::vector<EGLImageKHR> images;
    std::vector<GLuint> tex;
    GLuint inFbo[2] = {0, 0};
    GLuint readFbo = 0;
    std::vector<GLuint> frameTex;

    auto present = [&]() {
        const std::vector<int> sems;
        if (perf) { LSFG_3_1P::presentContext(ctxId, -1, sems); LSFG_3_1P::waitIdle(); }
        else { LSFG_3_1::presentContext(ctxId, -1, sems); LSFG_3_1::waitIdle(); }
        // 同步测试(0.6):Vulkan 写完 AHB 后,GL 读之前再等一小段并做一次 GL 全局同步,排除"读到半写的旧数据"
        if (kSyncTest) { std::this_thread::sleep_for(std::chrono::milliseconds(3)); glFinish(); }
    };

    const bool dumping = !dumpPath.empty() && dumpW > 0;
    std::ofstream dumpOut;
    std::vector<double> dPrevSum(generated, 0.0), dCurSum(generated, 0.0);
    int dumpPairs = 0, nonMono = 0, dumpedFrames = 0;
    std::vector<double> genMs, glMs;
    double firstPresent = -1, tInit = 0, tCtx = 0;
    Cmp lastCmp{};
    bool haveCmp = false;
    try {
        double t0 = nowMs();
        if (perf) LSFG_3_1P::initialize(uuid, false, flow, static_cast<uint64_t>(generated), loader);
        else LSFG_3_1::initialize(uuid, false, flow, static_cast<uint64_t>(generated), loader);
        inited = true;
        tInit = nowMs() - t0;
        o.line("framegen 初始化(创建设备) %.0f ms", tInit);
        if (flags & 1024) o.line("SPIR-V 改写:去掉 ConstOffset 共 %d 处(初始化阶段加载的着色器)", spvPatched.load());

        for (int i = 0; i < 2; i++) in[i] = allocAhb(W, H, ahbExtra);
        for (int i = 0; i < generated; i++) outs.push_back(allocAhb(W, H, ahbExtra));
        if (!in[0] || !in[1]) throw std::runtime_error("分配输入 AHB 失败");
        for (auto *b : outs) if (!b) throw std::runtime_error("分配输出 AHB 失败");

        t0 = nowMs();
        const VkExtent2D ext{(uint32_t)W, (uint32_t)H};
        if (perf) ctxId = LSFG_3_1P::createContextFromAHB(in[0], in[1], outs, ext, VK_FORMAT_R8G8B8A8_UNORM);
        else ctxId = LSFG_3_1::createContextFromAHB(in[0], in[1], outs, ext, VK_FORMAT_R8G8B8A8_UNORM);
        tCtx = nowMs() - t0;
        o.line("创建帧生成上下文(编译管线、分配显存) %.0f ms", tCtx);

        // GL 侧:AHB → 纹理
        tex.resize(2 + generated);
        glGenTextures((GLsizei)tex.size(), tex.data());
        for (size_t i = 0; i < tex.size(); i++) {
            EGLImageKHR im = g.bindAhb(i < 2 ? in[i] : outs[i - 2], tex[i]);
            if (im == EGL_NO_IMAGE_KHR) throw std::runtime_error("eglCreateImageKHR 失败 0x" + std::to_string(eglGetError()));
            images.push_back(im);
        }
        glGenFramebuffers(2, inFbo);
        for (int i = 0; i < 2; i++) {
            glBindFramebuffer(GL_FRAMEBUFFER, inFbo[i]);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex[i], 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw std::runtime_error("输入 AHB 作为渲染目标不完整");
        }
        glGenFramebuffers(1, &readFbo);
        // 每个源帧一张 GL 纹理(预先上传,计时区间里不含上传)
        frameTex.resize(nFrames);
        glGenTextures(nFrames, frameTex.data());
        for (int i = 0; i < nFrames; i++) {
            glBindTexture(GL_TEXTURE_2D, frameTex[i]);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, W, H);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, frames.at(i));
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        }
        glFinish();
        o.line("上下文就绪,开始 %d 轮生成", iterations);

        if (dumping) dumpOut.open(dumpPath, std::ios::binary | std::ios::trunc);
        const int verifyAt = std::min(iterations - 1, 3);
        std::vector<uint8_t> outBuf(static_cast<size_t>(W) * H * 4);
        const double paceStart = nowMs();
        for (int i = 0; i < iterations; i++) {
            if (paceMs > 0) { // 按源帧率的节奏送帧:GPU 在帧间隙里会降频,这才是真实播放时的状态
                const double due = paceStart + i * (double)paceMs;
                const double wait = due - nowMs();
                if (wait > 0) std::this_thread::sleep_for(std::chrono::duration<double, std::milli>(wait));
            }
            const int fi = i % nFrames;
            const int slot = i % 2;
            double a = nowMs();
            if (flags & 128) {
                // Vulkan 直接上传输入帧(不经过 GL 写 AHB)
                const bool okw = perf ? LSFG_3_1P::probeWriteInput(ctxId, slot, frames.at(fi)) : LSFG_3_1::probeWriteInput(ctxId, slot, frames.at(fi));
                if (!okw && i < 2) o.line("Vulkan 上传输入失败");
            } else {
                glBindFramebuffer(GL_FRAMEBUFFER, inFbo[slot]);
                g.blit(frameTex[fi], W, H);
                glFinish();
            }
            double b = nowMs();
            glMs.push_back(b - a);
            // 第一个源帧没有"前一帧",但 presentContext 也必须调用:LSFG 内部按调用次数的奇偶决定哪个输入槽是新帧,
            // 跳过它会让奇偶错位,生成帧的时间顺序整个反过来(主程序每帧都调用,所以不受影响)。结果丢弃。
            if (i == 0) { present(); continue; }
            present();
            double c = nowMs();
            if (firstPresent < 0) firstPresent = c - b;
            genMs.push_back(c - b);

            // 逐级定位:第 12 轮生成完后,把各阶段中间图像全部导出(flags & 512,需要 256 让内部图像可拷贝)
            if ((flags & 512) && i == 12 && !dumpPath.empty()) {
                const std::string sp = dumpPath + ".stages";
                const bool oks = perf ? LSFG_3_1P::probeDumpStages(ctxId, sp.c_str()) : LSFG_3_1::probeDumpStages(ctxId, sp.c_str());
                o.line("导出各阶段中间图像 %s → %s", oks ? "成功" : "失败", sp.c_str());
            }
            // 导出:时间线 = 前一帧, 生成帧1..k(下一对再从"当前帧"开始),同时检查生成帧的时间顺序:
            // 第 j 张离前一帧的距离应该递增、离当前帧的距离应该递减;顺序错了(或重复)画面就会"抽动"
            if (dumping && fi > 0) {
                std::vector<uint8_t> ob(static_cast<size_t>(W) * H * 4);
                const uint8_t *prevF = frames.at(fi - 1), *curF = frames.at(fi);
                writeI420(dumpOut, prevF, W, H, dumpW, dumpH);
                dumpedFrames++;
                std::vector<double> dp(generated), dc(generated);
                for (int oi = 0; oi < generated; oi++) {
                    if (flags & 64) {
                        // Vulkan 自己把输出图像拷到主机内存(不经过 AHB 映射 / GL),模拟器上也能读到真实输出
                        const bool okr = perf ? LSFG_3_1P::probeReadOutput(ctxId, oi, ob.data()) : LSFG_3_1::probeReadOutput(ctxId, oi, ob.data());
                        if (!okr && oi == 0 && dumpPairs == 0) o.line("Vulkan 读回输出失败");
                    } else if (flags & 32) {
                        // CPU 直接锁定输出 AHB 读回(不经过 GL;模拟器上 GL 读不到 Vulkan 写的共享缓冲)
                        void *mp = nullptr;
                        AHardwareBuffer_Desc dd{};
                        AHardwareBuffer_describe(outs[oi], &dd);
                        if (AHardwareBuffer_lock(outs[oi], AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &mp) == 0 && mp) {
                            for (int y = 0; y < H; y++)
                                memcpy(ob.data() + static_cast<size_t>(y) * W * 4, static_cast<uint8_t *>(mp) + static_cast<size_t>(y) * dd.stride * 4, static_cast<size_t>(W) * 4);
                            AHardwareBuffer_unlock(outs[oi], nullptr);
                        } else if (oi == 0 && dumpPairs == 0) o.line("AHardwareBuffer_lock 读输出失败");
                    } else {
                        glBindFramebuffer(GL_FRAMEBUFFER, readFbo);
                        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex[2 + oi], 0);
                        glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, ob.data());
                    }
                    dp[oi] = mad(ob.data(), prevF, W, H);
                    dc[oi] = mad(ob.data(), curF, W, H);
                    dPrevSum[oi] += dp[oi]; dCurSum[oi] += dc[oi];
                    writeI420(dumpOut, ob.data(), W, H, dumpW, dumpH);
                    dumpedFrames++;
                }
                dumpPairs++;
                bool mono = true;
                for (int oi = 1; oi < generated; oi++) if (dp[oi] < dp[oi - 1] - 0.02 || dc[oi] > dc[oi - 1] + 0.02) mono = false;
                if (generated == 1) mono = true;
                if (!mono) { nonMono++; if (nonMono <= 3) o.line("  时间顺序异常:第%d对 离前一帧[%.2f %.2f %.2f %.2f] 离当前帧[%.2f %.2f %.2f %.2f]", i, dp[0], generated > 1 ? dp[1] : 0, generated > 2 ? dp[2] : 0, generated > 3 ? dp[3] : 0, dc[0], generated > 1 ? dc[1] : 0, generated > 2 ? dc[2] : 0, generated > 3 ? dc[3] : 0); }
            }
            // 校验:帧序列没回绕(fi>0)时,检查第 1 张输出
            if (i == verifyAt || i == iterations - 1) {
                if (fi == 0) continue;
                glBindFramebuffer(GL_FRAMEBUFFER, readFbo);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex[2], 0);
                glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, outBuf.data());
                const GLenum ge = glGetError();
                if (ge != GL_NO_ERROR) o.line("读回输出 AHB 的 GL 错误 0x%x", ge);
                const uint8_t *prev = frames.at(fi - 1), *cur = frames.at(fi);
                lastCmp = compare(prev, cur, outBuf.data(), W, H);
                haveCmp = true;
                o.line("校验(第%d轮,第1张输出): 画面运动量=%.2f 与均值差=%.2f 与前帧差=%.2f 与当前帧差=%.2f 平均亮度=%.1f 全黑像素=%.1f%%", i, lastCmp.motion,
                       lastCmp.vsAvg, lastCmp.vsPrev, lastCmp.vsCur, lastCmp.luma, lastCmp.black);
                if (i == verifyAt && !thumbPath.empty()) {
                    // [前一帧 | 输出 | 当前帧] 并排缩略图,写成 raw RGBA(前 8 字节是宽高)
                    const int tw = 320, th = std::max(1, H * tw / W);
                    std::vector<uint8_t> th3(static_cast<size_t>(tw) * 3 * th * 4);
                    const uint8_t *srcs[3] = {prev, outBuf.data(), cur};
                    for (int s = 0; s < 3; s++)
                        for (int y = 0; y < th; y++)
                            for (int x = 0; x < tw; x++)
                                memcpy(&th3[(static_cast<size_t>(y) * tw * 3 + s * tw + x) * 4], srcs[s] + (static_cast<size_t>(y * H / th) * W + x * W / tw) * 4, 4);
                    std::ofstream tf(thumbPath, std::ios::binary);
                    int32_t hdr[2] = {tw * 3, th};
                    tf.write(reinterpret_cast<char *>(hdr), 8);
                    tf.write(reinterpret_cast<char *>(th3.data()), th3.size());
                }
            }
        }
    } catch (const std::exception &e) {
        failed = true;
        failMsg = e.what();
        const char *w = e.what();
        deviceLost = std::strstr(w, "error -4") || std::strstr(w, "DEVICE_LOST");
        o.line("异常: %s%s", w, deviceLost ? "  ← 设备丢失(GPU 复位 / 驱动崩溃)" : "");
    }

    // 统计
    if (!genMs.empty()) {
        std::vector<double> steady(genMs.begin() + std::min<size_t>(2, genMs.size() - 1), genMs.end());
        Stats s = stats(steady);
        o.line("首次 presentContext %.1f ms(含驱动惰性编译 / 预热)", firstPresent);
        o.line("生成耗时(%zu 次,不含前2次): 中位 %.2f ms  均值 %.2f  p95 %.2f  最小 %.2f  最大 %.2f", steady.size(), s.median, s.mean, s.p95, s.mn, s.mx);
        if (paceMs > 0) {
            size_t over = 0;
            for (double x : steady) if (x > paceMs) over++;
            o.line("节奏预算 %.1f ms:超预算 %zu/%zu 次 (%.1f%%)——这些帧会来不及上屏", paceMs, over, steady.size(), 100.0 * over / steady.size());
        }
        Stats gs = stats(glMs);
        o.line("GL 写入输入槽 中位 %.2f ms (p95 %.2f)", gs.median, gs.p95);
        o.line("按中位计: 每秒最多 %.1f 次生成;30fps 源 →预算 33.3ms,余量 %.1f ms;60fps 源 →预算 16.7ms,余量 %.1f ms", 1000.0 / std::max(s.median, 0.01),
               33.3 - s.median, 16.7 - s.median);
        if (haveCmp) {
            const char *verdict = "正常";
            if (lastCmp.black > 50) verdict = "输出全黑";
            else if (lastCmp.vsAvg > std::max(lastCmp.motion * 1.5, 25.0)) verdict = "输出疑似损坏(与前后帧均值差太大)";
            else if (lastCmp.vsPrev < 0.05 && lastCmp.vsCur > 1) verdict = "输出等于前帧(没生成新内容)";
            o.line("输出判定: %s", verdict);
        }
    }

    if (dumping) {
        dumpOut.close();
        o.line("导出:%d 个时间步(%d 对),%dx%d I420 → %s", dumpedFrames, dumpPairs, dumpW, dumpH, dumpPath.c_str());
        for (int oi = 0; oi < generated && dumpPairs > 0; oi++)
            o.line("  生成帧%d:离前一帧 %.2f  离当前帧 %.2f(平均绝对差,应当随序号:前一帧距离递增 / 当前帧距离递减)", oi + 1, dPrevSum[oi] / dumpPairs, dCurSum[oi] / dumpPairs);
        o.line("时间顺序检查: %d / %d 对不单调 %s", nonMono, dumpPairs, nonMono == 0 ? "(正常)" : "(生成帧顺序错乱 → 抽动)");
    }

    // 清理(顺序:GL 资源 → framegen → AHB)
    try {
        if (!frameTex.empty()) glDeleteTextures((GLsizei)frameTex.size(), frameTex.data());
        if (readFbo) glDeleteFramebuffers(1, &readFbo);
        if (inFbo[0]) glDeleteFramebuffers(2, inFbo);
        if (!tex.empty()) glDeleteTextures((GLsizei)tex.size(), tex.data());
        for (auto im : images) g.destroyImage(g.dpy, im);
        if (inited && !deviceLost) {
            if (ctxId >= 0) { if (perf) LSFG_3_1P::deleteContext(ctxId); else LSFG_3_1::deleteContext(ctxId); }
            if (perf) LSFG_3_1P::finalize(); else LSFG_3_1::finalize();
        }
    } catch (const std::exception &e) {
        o.line("清理时异常: %s", e.what());
    }
    for (auto *b : in) if (b) AHardwareBuffer_release(b);
    for (auto *b : outs) if (b) AHardwareBuffer_release(b);
    g.destroy();

    if (failed) o.line("RESULT: FAIL %s", failMsg.c_str());
    else if (genMs.empty()) o.line("RESULT: FAIL 没有成功的生成");
    else o.line("RESULT: OK");
    return o.s;
}
