#include "common.hpp"

#include <dlfcn.h>

extern PFN_vkGetInstanceProcAddr g_probeGipa; // 定义在补丁后的 framegen/src/core/instance.cpp

const char *vkResultName(VkResult r) {
    switch (r) {
#define C(x) case x: return #x;
        C(VK_SUCCESS) C(VK_NOT_READY) C(VK_TIMEOUT) C(VK_INCOMPLETE)
        C(VK_ERROR_OUT_OF_HOST_MEMORY) C(VK_ERROR_OUT_OF_DEVICE_MEMORY) C(VK_ERROR_INITIALIZATION_FAILED)
        C(VK_ERROR_DEVICE_LOST) C(VK_ERROR_MEMORY_MAP_FAILED) C(VK_ERROR_LAYER_NOT_PRESENT)
        C(VK_ERROR_EXTENSION_NOT_PRESENT) C(VK_ERROR_FEATURE_NOT_PRESENT) C(VK_ERROR_INCOMPATIBLE_DRIVER)
        C(VK_ERROR_TOO_MANY_OBJECTS) C(VK_ERROR_FORMAT_NOT_SUPPORTED) C(VK_ERROR_FRAGMENTED_POOL)
        C(VK_ERROR_UNKNOWN) C(VK_ERROR_INVALID_EXTERNAL_HANDLE)
#undef C
        default: return "VK_RESULT_?";
    }
}

static bool g_custom = false;

bool driverSelect(const std::string &path, Out &out) {
    g_probeGipa = nullptr;
    g_custom = false;
    if (path.empty()) {
        out.line("驱动: 系统 Vulkan 加载器 (libvulkan.so → 厂商驱动)");
        return true;
    }
    out.line("驱动: 自定义 %s", path.c_str());
    void *h = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!h) {
        out.line("  dlopen 失败: %s", dlerror());
        return false;
    }
    void *sym = dlsym(h, "vk_icdGetInstanceProcAddr");
    const char *which = "vk_icdGetInstanceProcAddr";
    if (!sym) { sym = dlsym(h, "vkGetInstanceProcAddr"); which = "vkGetInstanceProcAddr"; }
    if (!sym) {
        out.line("  找不到 vk_icdGetInstanceProcAddr / vkGetInstanceProcAddr(这不是 Vulkan 驱动,或符号被隐藏)");
        return false;
    }
    out.line("  入口: %s", which);
    g_probeGipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(sym);
    g_custom = true;
    return true;
}

bool driverVolkInit(Out &out) {
    if (g_custom && g_probeGipa) {
        volkInitializeCustom(g_probeGipa);
        return true;
    }
    VkResult r = volkInitialize();
    if (r != VK_SUCCESS) {
        out.line("volkInitialize 失败: %s(没有 libvulkan.so?)", vkResultName(r));
        return false;
    }
    return true;
}
