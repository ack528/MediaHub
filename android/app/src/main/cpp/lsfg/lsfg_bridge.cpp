// LSFG(Lossless Scaling 帧生成)的 JNI 桥。
//
// 底层是 lsfg-vk(MIT)的 framegen 库:在它自己的 Vulkan 设备上跑 Lossless.dll 里的帧生成着色器。
// 本桥只做三件事:
//   1. 从 Lossless.dll 提取着色器(用参考项目 LSFG-Android 的 android_shader_loader);
//   2. 创建 framegen 上下文,输入 / 输出图像用 AHardwareBuffer(AHB)共享;
//   3. 把 AHB 绑定成 OpenGL ES 纹理(EGLImage),让本应用的 GL 渲染器直接读写,不经过 CPU。
// 调用顺序(都在 GL 渲染线程):nativeStart → nativeBind(GL 纹理)→ 每个源帧:渲染进输入纹理、glFinish、nativePresent、读输出纹理 → nativeStop。
// 帧生成顺序和参考项目一致:第 N 个真实帧写进输入槽 N%2,presentContext 后输出是 前一帧 → 当前帧 之间的 k 张中间帧。

#include <jni.h>

#include <android/hardware_buffer.h>
#include <android/log.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>

#include <volk.h>

#include <cstring>
#include <exception>
#include <string>
#include <vector>

#include "android_shader_loader.hpp"
#include "lsfg_3_1.hpp"
#include "lsfg_3_1p.hpp"

#define TAG "lsfg-bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Session {
    bool initialized = false;
    bool perf = false;
    int32_t ctx = -1;
    uint32_t w = 0, h = 0;
    AHardwareBuffer *in[2] = {nullptr, nullptr};
    std::vector<AHardwareBuffer *> out;
    std::vector<EGLImageKHR> images;
    std::string error;
};

Session S;

void setErr(const std::string &e) {
    S.error = e;
    LOGE("%s", e.c_str());
}

AHardwareBuffer *allocAhb(uint32_t w, uint32_t h) {
    AHardwareBuffer_Desc d{};
    d.width = w;
    d.height = h;
    d.layers = 1;
    d.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    d.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
    AHardwareBuffer *b = nullptr;
    if (AHardwareBuffer_allocate(&d, &b) != 0) return nullptr;
    return b;
}

// framegen 用 (vendorID << 32 | deviceID) 找设备;这里临时建一个 Vulkan 实例读第一块 GPU 的 ID。
bool queryDevice(uint64_t &uuid, std::string &name, uint32_t &apiVersion) {
    if (volkInitialize() != VK_SUCCESS) {
        setErr("没有 Vulkan 加载器(这台设备不支持 Vulkan)");
        return false;
    }
    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "localtg";
    app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo = &app;
    VkInstance inst = VK_NULL_HANDLE;
    if (vkCreateInstance(&ci, nullptr, &inst) != VK_SUCCESS) {
        setErr("创建 Vulkan 实例失败");
        return false;
    }
    volkLoadInstance(inst);
    uint32_t n = 0;
    vkEnumeratePhysicalDevices(inst, &n, nullptr);
    if (n == 0) {
        vkDestroyInstance(inst, nullptr);
        setErr("没有可用的 Vulkan 物理设备");
        return false;
    }
    std::vector<VkPhysicalDevice> devs(n);
    vkEnumeratePhysicalDevices(inst, &n, devs.data());
    VkPhysicalDeviceProperties p{};
    vkGetPhysicalDeviceProperties(devs[0], &p);
    uuid = (static_cast<uint64_t>(p.vendorID) << 32) | p.deviceID;
    name = p.deviceName;
    apiVersion = p.apiVersion;
    vkDestroyInstance(inst, nullptr);
    return true;
}

PFNEGLCREATEIMAGEKHRPROC pCreateImage = nullptr;
PFNEGLDESTROYIMAGEKHRPROC pDestroyImage = nullptr;
PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC pGetClientBuffer = nullptr;
PFNGLEGLIMAGETARGETTEXTURE2DOESPROC pImageTarget = nullptr;

bool loadEgl() {
    if (pCreateImage) return true;
    pCreateImage = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
    pDestroyImage = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
    pGetClientBuffer = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    pImageTarget = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    return pCreateImage && pDestroyImage && pGetClientBuffer && pImageTarget;
}

void destroyImages() {
    EGLDisplay dpy = eglGetCurrentDisplay();
    if (dpy != EGL_NO_DISPLAY && pDestroyImage)
        for (auto im : S.images) if (im != EGL_NO_IMAGE_KHR) pDestroyImage(dpy, im);
    S.images.clear();
}

void teardown() {
    destroyImages();
    if (S.initialized) {
        try {
            if (S.ctx >= 0) {
                if (S.perf) LSFG_3_1P::deleteContext(S.ctx); else LSFG_3_1::deleteContext(S.ctx);
            }
            if (S.perf) LSFG_3_1P::finalize(); else LSFG_3_1::finalize();
        } catch (const std::exception &e) {
            LOGW("释放 framegen 时出错: %s", e.what());
        }
    }
    for (auto *b : S.in) if (b) AHardwareBuffer_release(b);
    for (auto *b : S.out) if (b) AHardwareBuffer_release(b);
    S.in[0] = S.in[1] = nullptr;
    S.out.clear();
    S.initialized = false;
    S.ctx = -1;
}

std::string jstr(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

} // namespace

extern "C" {

// 提取着色器:0 = 成功
JNIEXPORT jint JNICALL Java_com_localtg_render_LsfgNative_nativeExtract(JNIEnv *env, jclass, jstring dll, jstring cache) {
    try {
        return lsfg_android::extract_dll_to_spirv(jstr(env, dll), jstr(env, cache));
    } catch (const std::exception &e) {
        setErr(std::string("提取着色器时异常: ") + e.what());
        return -100;
    }
}

// 创建 framegen 上下文和 AHB:0 = 成功;非 0 = 失败(nativeLastError 里有原因)
JNIEXPORT jint JNICALL Java_com_localtg_render_LsfgNative_nativeStart(
        JNIEnv *env, jclass, jstring cacheJ, jint width, jint height, jint generated, jfloat flowScale, jboolean perf) {
    teardown();
    S.error.clear();
    const std::string cache = jstr(env, cacheJ);
    if (width < 64 || height < 64 || generated < 1 || generated > 7) { setErr("参数无效"); return 1; }
    S.w = static_cast<uint32_t>(width);
    S.h = static_cast<uint32_t>(height);
    S.perf = perf;

    uint64_t uuid = 0; std::string gpu; uint32_t api = 0;
    if (!queryDevice(uuid, gpu, api)) return 2;
    LOGI("GPU: %s (Vulkan %u.%u) uuid=0x%llx", gpu.c_str(), VK_VERSION_MAJOR(api), VK_VERSION_MINOR(api), (unsigned long long)uuid);

    // 着色器来源:优先 FP32 SPIR-V(DLL 里现成的,绕开 DXBC 翻译器,不需要 vulkanMemoryModel,Mali 也能用),否则 DXBC 翻译结果
    const bool useFp32 = lsfg_android::fp32_spirv_shaders_available(cache);
    auto loader = [cache, useFp32](const std::string &name) -> std::vector<uint8_t> {
        std::vector<uint8_t> spirv;
        if (useFp32) {
            const uint32_t id = lsfg_android::shader_name_to_resource_id_fp32_spirv(name);
            if (id) spirv = lsfg_android::load_cached_spirv(cache, id, lsfg_android::ShaderCache::Fp32Spirv);
        }
        if (spirv.empty()) {
            const uint32_t id = lsfg_android::shader_name_to_resource_id(name);
            if (id) spirv = lsfg_android::load_cached_spirv(cache, id, lsfg_android::ShaderCache::Dxbc);
        }
        if (spirv.empty()) LOGE("着色器 '%s' 缺失", name.c_str());
        return spirv;
    };

    try {
        if (S.perf) LSFG_3_1P::initialize(uuid, false, flowScale, static_cast<uint64_t>(generated), loader);
        else LSFG_3_1::initialize(uuid, false, flowScale, static_cast<uint64_t>(generated), loader);
        S.initialized = true;
    } catch (const std::exception &e) {
        setErr(std::string("framegen 初始化失败: ") + e.what());
        return 3;
    }

    for (int i = 0; i < 2; i++) {
        S.in[i] = allocAhb(S.w, S.h);
        if (!S.in[i]) { setErr("分配输入缓冲失败"); teardown(); return 4; }
    }
    for (int i = 0; i < generated; i++) {
        AHardwareBuffer *b = allocAhb(S.w, S.h);
        if (!b) { setErr("分配输出缓冲失败"); teardown(); return 4; }
        S.out.push_back(b);
    }
    try {
        const VkExtent2D ext{S.w, S.h};
        if (S.perf) S.ctx = LSFG_3_1P::createContextFromAHB(S.in[0], S.in[1], S.out, ext, VK_FORMAT_R8G8B8A8_UNORM);
        else S.ctx = LSFG_3_1::createContextFromAHB(S.in[0], S.in[1], S.out, ext, VK_FORMAT_R8G8B8A8_UNORM);
    } catch (const std::exception &e) {
        setErr(std::string("创建帧生成上下文失败: ") + e.what());
        teardown();
        return 5;
    }
    LOGI("帧生成已就绪 %ux%u 每帧生成 %d 张 flowScale=%.2f %s", S.w, S.h, generated, flowScale, S.perf ? "(3.1P 性能模式)" : "(3.1)");
    return 0;
}

// 把 2 个输入 + k 个输出 AHB 依次绑定到传入的 GL 纹理(GL_TEXTURE_2D)上。必须在持有 GL 上下文的线程调用。
JNIEXPORT jint JNICALL Java_com_localtg_render_LsfgNative_nativeBind(JNIEnv *env, jclass, jintArray texIds) {
    if (!S.initialized || S.ctx < 0) { setErr("还没有 start"); return 1; }
    if (!loadEgl()) { setErr("缺少 EGL_ANDROID_image_native_buffer / OES_EGL_image 扩展"); return 2; }
    const jsize n = env->GetArrayLength(texIds);
    if (static_cast<size_t>(n) != 2 + S.out.size()) { setErr("纹理数量不对"); return 3; }
    std::vector<jint> ids(n);
    env->GetIntArrayRegion(texIds, 0, n, ids.data());
    EGLDisplay dpy = eglGetCurrentDisplay();
    destroyImages();
    for (jsize i = 0; i < n; i++) {
        AHardwareBuffer *b = i < 2 ? S.in[i] : S.out[i - 2];
        EGLClientBuffer cb = pGetClientBuffer(b);
        const EGLint attrs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
        EGLImageKHR img = pCreateImage(dpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, cb, attrs);
        if (img == EGL_NO_IMAGE_KHR) { setErr("eglCreateImageKHR 失败(0x" + std::to_string(eglGetError()) + ")"); destroyImages(); return 4; }
        S.images.push_back(img);
        glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(ids[i]));
        pImageTarget(GL_TEXTURE_2D, reinterpret_cast<GLeglImageOES>(img));
    }
    return 0;
}

// 生成一次:0 = 成功;-1 = 失败;-2 = 设备丢失(之后不要再用,请 stop)
JNIEXPORT jint JNICALL Java_com_localtg_render_LsfgNative_nativePresent(JNIEnv *, jclass) {
    if (!S.initialized || S.ctx < 0) return -1;
    try {
        const std::vector<int> outSems; // 不用信号量:presentContext 之后 waitIdle 同步
        if (S.perf) LSFG_3_1P::presentContext(S.ctx, -1, outSems); else LSFG_3_1::presentContext(S.ctx, -1, outSems);
        if (S.perf) LSFG_3_1P::waitIdle(); else LSFG_3_1::waitIdle();
        return 0;
    } catch (const std::exception &e) {
        const char *w = e.what() ? e.what() : "";
        setErr(std::string("presentContext 失败: ") + w);
        if (std::strstr(w, "error -4") || std::strstr(w, "DEVICE_LOST")) return -2;
        return -1;
    }
}

JNIEXPORT void JNICALL Java_com_localtg_render_LsfgNative_nativeStop(JNIEnv *, jclass) {
    teardown();
}

JNIEXPORT jstring JNICALL Java_com_localtg_render_LsfgNative_nativeLastError(JNIEnv *env, jclass) {
    return env->NewStringUTF(S.error.c_str());
}

} // extern "C"
