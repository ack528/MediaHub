// OpenGL ES ↔ AHardwareBuffer 互通测试:LSFG 的输入 / 输出就是通过这条路径在 GL 渲染器和 Vulkan 之间交换帧。
#include "gl.hpp"

#include <cstring>

std::string runGlInterop() {
    Out o;
    GlCtx g;
    if (!g.init(o)) { o.line("RESULT: FAIL EGL/GL 初始化失败"); return o.s; }
    auto gs = [](GLenum n) { const char *s = reinterpret_cast<const char *>(glGetString(n)); return s ? s : "?"; };
    o.line("GL_VENDOR=%s", gs(GL_VENDOR));
    o.line("GL_RENDERER=%s", gs(GL_RENDERER));
    o.line("GL_VERSION=%s", gs(GL_VERSION));
    const std::string ext = gs(GL_EXTENSIONS);
    const char *want[] = {"GL_OES_EGL_image", "GL_OES_EGL_image_external", "GL_EXT_color_buffer_float", "GL_EXT_color_buffer_half_float",
                          "GL_EXT_texture_norm16", "GL_ARM_shader_framebuffer_fetch", "GL_EXT_shader_framebuffer_fetch"};
    for (auto w : want) o.line("  %s: %s", w, ext.find(w) != std::string::npos ? "有" : "无");
    const char *ee = eglQueryString(g.dpy, EGL_EXTENSIONS);
    std::string eext = ee ? ee : "";
    for (auto w : {"EGL_ANDROID_image_native_buffer", "EGL_KHR_image_base", "EGL_ANDROID_get_native_client_buffer", "EGL_KHR_fence_sync", "EGL_ANDROID_native_fence_sync"})
        o.line("  %s: %s", w, eext.find(w) != std::string::npos ? "有" : "无");
    if (!g.hasAhbInterop()) { o.line("缺少 AHB↔GL 互通函数(eglCreateImageKHR / eglGetNativeClientBufferANDROID / glEGLImageTargetTexture2DOES)"); g.destroy(); o.line("RESULT: FAIL"); return o.s; }
    if (!g.initBlit(o)) { g.destroy(); o.line("RESULT: FAIL"); return o.s; }

    bool failed = false;
    // AHB 支持矩阵
    struct Case { const char *name; uint32_t fmt; uint64_t usage; };
    const Case cases[] = {
        {"RGBA8 采样+颜色输出", AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM, AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT},
        {"RGBA8 +CPU读写", AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM, AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN},
        {"RGBA8 +GPU存储图像", AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM, AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER},
        {"RGBA16F 采样+颜色输出", AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT, AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT},
        {"A2B10G10R10 采样+颜色输出", AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM, AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT},
    };
    for (auto &c : cases) {
        AHardwareBuffer_Desc d{};
        d.width = 1920; d.height = 1080; d.layers = 1; d.format = c.fmt; d.usage = c.usage;
        int sup = AHardwareBuffer_isSupported(&d);
        AHardwareBuffer *b = nullptr;
        int rc = AHardwareBuffer_allocate(&d, &b);
        uint32_t stride = 0;
        if (rc == 0 && b) { AHardwareBuffer_Desc got{}; AHardwareBuffer_describe(b, &got); stride = got.stride; AHardwareBuffer_release(b); }
        o.line("AHB %s 1920x1080: isSupported=%d 分配=%s stride=%u", c.name, sup, rc == 0 ? "成功" : "失败", stride);
    }

    // 正确性 + 速度:CPU 写入测试图 → 作为纹理画进 AHB → CPU 读回比对
    const int W = 1920, H = 1080;
    AHardwareBuffer *ahb = allocAhb(W, H, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN);
    if (!ahb) { o.line("分配带 CPU 读写的 AHB 失败"); g.destroy(); o.line("RESULT: FAIL"); return o.s; }
    GLuint tex[2];
    glGenTextures(2, tex);
    EGLImageKHR img = g.bindAhb(ahb, tex[0]);
    if (img == EGL_NO_IMAGE_KHR) {
        o.line("eglCreateImageKHR(AHB) 失败 0x%x", eglGetError());
        AHardwareBuffer_release(ahb); g.destroy(); o.line("RESULT: FAIL"); return o.s;
    }
    GLuint fbo;
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex[0], 0);
    GLenum st = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    o.line("AHB 作为 GL 渲染目标: FBO 状态 0x%x (%s)", st, st == GL_FRAMEBUFFER_COMPLETE ? "完整" : "不完整");
    if (st != GL_FRAMEBUFFER_COMPLETE) failed = true;

    std::vector<uint8_t> src(W * H * 4);
    for (int y = 0; y < H; y++)
        for (int x = 0; x < W; x++) {
            uint8_t *p = &src[(y * W + x) * 4];
            p[0] = x * 255 / (W - 1); p[1] = y * 255 / (H - 1); p[2] = (x ^ y) & 255; p[3] = 255;
        }
    glBindTexture(GL_TEXTURE_2D, tex[1]);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, W, H);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, src.data());
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    g.blit(tex[1], W, H);
    glFinish();
    void *mapped = nullptr;
    AHardwareBuffer_Desc dd{};
    AHardwareBuffer_describe(ahb, &dd);
    int lr = AHardwareBuffer_lock(ahb, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &mapped);
    if (lr != 0 || !mapped) { o.line("AHardwareBuffer_lock 失败 %d", lr); failed = true; }
    else {
        long bad = 0;
        long maxd = 0;
        for (int y = 0; y < H; y += 3)
            for (int x = 0; x < W; x += 3) {
                const uint8_t *a = &src[(y * W + x) * 4];
                const uint8_t *b = reinterpret_cast<uint8_t *>(mapped) + (static_cast<size_t>(y) * dd.stride + x) * 4;
                for (int k = 0; k < 3; k++) { long df = std::abs((int)a[k] - (int)b[k]); if (df > maxd) maxd = df; if (df > 1) bad++; }
            }
        AHardwareBuffer_unlock(ahb, nullptr);
        o.line("GL 写入 AHB → CPU 读回比对: 偏差>1 的采样通道数=%ld 最大偏差=%ld %s", bad, maxd, bad == 0 ? "(正确)" : "(内容不对!)");
        if (bad) failed = true;
    }

    // 速度:模拟主程序每帧 上传 + 渲染进 AHB + glFinish
    std::vector<double> ts, ts2;
    for (int i = 0; i < 40; i++) {
        double t0 = nowMs();
        glBindTexture(GL_TEXTURE_2D, tex[1]);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, src.data());
        double t1 = nowMs();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        g.blit(tex[1], W, H);
        glFinish();
        double t2 = nowMs();
        if (i >= 5) { ts.push_back(t1 - t0); ts2.push_back(t2 - t1); }
    }
    Stats a = stats(ts), b = stats(ts2);
    o.line("1080p 纹理上传 中位 %.2f ms;渲染进 AHB + glFinish 中位 %.2f ms (p95 %.2f)", a.median, b.median, b.p95);

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteFramebuffers(1, &fbo);
    glDeleteTextures(2, tex);
    g.destroyImage(g.dpy, img);
    AHardwareBuffer_release(ahb);
    g.destroy();
    o.line(failed ? "RESULT: FAIL" : "RESULT: OK");
    return o.s;
}
