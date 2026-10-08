#include "common.hpp"

#include <cstring>
#include <set>

namespace {

std::string decodeDriverVersion(uint32_t vendor, uint32_t v) {
    char b[64];
    if (vendor == 0x10DE) snprintf(b, sizeof b, "%u.%u.%u.%u", v >> 22, (v >> 14) & 0xff, (v >> 6) & 0xff, v & 0x3f);
    else snprintf(b, sizeof b, "%u.%u.%u", VK_VERSION_MAJOR(v), VK_VERSION_MINOR(v), VK_VERSION_PATCH(v));
    return b;
}

std::string flagsStr(VkFormatFeatureFlags f) {
    std::string s;
    auto add = [&](VkFormatFeatureFlags bit, const char *n) { if (f & bit) { if (!s.empty()) s += '|'; s += n; } };
    add(VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT, "sampled");
    add(VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT, "linear");
    add(VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT, "storage");
    add(VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT, "color");
    add(VK_FORMAT_FEATURE_TRANSFER_SRC_BIT, "src");
    add(VK_FORMAT_FEATURE_TRANSFER_DST_BIT, "dst");
    return s.empty() ? "无" : s;
}

} // namespace

std::string runVkInfo(const std::string &driver) {
    Out o;
    if (!driverSelect(driver, o)) { o.line("RESULT: FAIL 驱动加载失败"); return o.s; }
    if (!driverVolkInit(o)) { o.line("RESULT: FAIL 没有 Vulkan"); return o.s; }

    uint32_t instVer = VK_API_VERSION_1_0;
    if (vkEnumerateInstanceVersion) vkEnumerateInstanceVersion(&instVer);
    o.line("Vulkan 实例版本: %u.%u.%u", VK_VERSION_MAJOR(instVer), VK_VERSION_MINOR(instVer), VK_VERSION_PATCH(instVer));

    uint32_t n = 0;
    vkEnumerateInstanceExtensionProperties(nullptr, &n, nullptr);
    std::vector<VkExtensionProperties> iext(n);
    vkEnumerateInstanceExtensionProperties(nullptr, &n, iext.data());
    std::string s;
    for (auto &e : iext) { s += e.extensionName; s += ' '; }
    o.line("实例扩展(%u): %s", n, s.c_str());

    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "probe";
    app.apiVersion = instVer >= VK_API_VERSION_1_3 ? VK_API_VERSION_1_3 : VK_API_VERSION_1_1;
    VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ci.pApplicationInfo = &app;
    VkInstance inst = VK_NULL_HANDLE;
    VkResult r = vkCreateInstance(&ci, nullptr, &inst);
    if (r != VK_SUCCESS) { o.line("vkCreateInstance 失败: %s", vkResultName(r)); o.line("RESULT: FAIL"); return o.s; }
    volkLoadInstance(inst);

    uint32_t dc = 0;
    vkEnumeratePhysicalDevices(inst, &dc, nullptr);
    o.line("物理设备数: %u", dc);
    std::vector<VkPhysicalDevice> devs(dc);
    vkEnumeratePhysicalDevices(inst, &dc, devs.data());

    for (uint32_t di = 0; di < dc; di++) {
        VkPhysicalDevice pd = devs[di];
        VkPhysicalDeviceProperties p{};
        vkGetPhysicalDeviceProperties(pd, &p);
        o.line("---- 设备 %u: %s ----", di, p.deviceName);
        o.line("类型=%d vendorID=0x%x deviceID=0x%x apiVersion=%u.%u.%u 驱动版本=0x%x (%s)", (int)p.deviceType, p.vendorID, p.deviceID,
               VK_VERSION_MAJOR(p.apiVersion), VK_VERSION_MINOR(p.apiVersion), VK_VERSION_PATCH(p.apiVersion), p.driverVersion,
               decodeDriverVersion(p.vendorID, p.driverVersion).c_str());
        const bool api11 = p.apiVersion >= VK_API_VERSION_1_1;
        const bool api12 = p.apiVersion >= VK_API_VERSION_1_2;
        const bool api13 = p.apiVersion >= VK_API_VERSION_1_3;

        uint32_t en = 0;
        vkEnumerateDeviceExtensionProperties(pd, nullptr, &en, nullptr);
        std::vector<VkExtensionProperties> dext(en);
        vkEnumerateDeviceExtensionProperties(pd, nullptr, &en, dext.data());
        std::set<std::string> have;
        for (auto &e : dext) have.insert(e.extensionName);
        std::string all;
        for (auto &e : have) { all += e; all += ' '; }
        o.line("设备扩展(%u): %s", en, all.c_str());
        static const char *key[] = {
            "VK_ANDROID_external_memory_android_hardware_buffer", "VK_KHR_external_memory", "VK_KHR_sampler_ycbcr_conversion",
            "VK_KHR_dedicated_allocation", "VK_KHR_get_memory_requirements2", "VK_KHR_bind_memory2", "VK_KHR_maintenance1",
            "VK_EXT_robustness2", "VK_KHR_global_priority", "VK_EXT_global_priority", "VK_KHR_synchronization2",
            "VK_KHR_timeline_semaphore", "VK_KHR_vulkan_memory_model", "VK_KHR_shader_float16_int8", "VK_KHR_16bit_storage",
            "VK_KHR_shader_subgroup_extended_types", "VK_EXT_subgroup_size_control", "VK_KHR_cooperative_matrix",
            "VK_EXT_external_memory_dma_buf", "VK_EXT_image_drm_format_modifier", "VK_KHR_external_semaphore_fd",
            "VK_KHR_external_memory_fd", "VK_EXT_queue_family_foreign", "VK_KHR_shader_float_controls",
            "VK_EXT_shader_demote_to_helper_invocation", "VK_ARM_scheduling_controls", "VK_ARM_shader_core_properties",
            "VK_ARM_shader_core_builtins", "VK_EXT_astc_decode_mode", "VK_KHR_shader_integer_dot_product",
        };
        std::string miss, present;
        for (auto k : key) (have.count(k) ? present : miss) += std::string(k) + ' ';
        o.line("关键扩展 有: %s", present.c_str());
        o.line("关键扩展 缺: %s", miss.c_str());

        // 属性链
        VkPhysicalDeviceDriverProperties drv{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES};
        VkPhysicalDeviceSubgroupProperties sg{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_PROPERTIES};
        VkPhysicalDeviceFloatControlsProperties fc{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FLOAT_CONTROLS_PROPERTIES};
        VkPhysicalDeviceSubgroupSizeControlProperties sgc{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_SIZE_CONTROL_PROPERTIES};
        VkPhysicalDeviceProperties2 p2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
        if (api12) {
            p2.pNext = &drv; drv.pNext = &fc; fc.pNext = &sg;
            if (api13) sg.pNext = &sgc;
        } else if (api11) {
            p2.pNext = &sg;
        }
        if (api11) vkGetPhysicalDeviceProperties2(pd, &p2);
        if (api12) {
            o.line("驱动: id=%d name=\"%s\" info=\"%s\" 一致性=%u.%u.%u.%u", (int)drv.driverID, drv.driverName, drv.driverInfo,
                   drv.conformanceVersion.major, drv.conformanceVersion.minor, drv.conformanceVersion.subminor, drv.conformanceVersion.patch);
            o.line("浮点控制: denormPreserve16=%d denormFlush16=%d RTE16=%d RTZ16=%d signedZeroInfNan16=%d | denormFlush32=%d RTE32=%d",
                   fc.shaderDenormPreserveFloat16, fc.shaderDenormFlushToZeroFloat16, fc.shaderRoundingModeRTEFloat16,
                   fc.shaderRoundingModeRTZFloat16, fc.shaderSignedZeroInfNanPreserveFloat16, fc.shaderDenormFlushToZeroFloat32,
                   fc.shaderRoundingModeRTEFloat32);
        }
        if (api11) o.line("子组: size=%u stages=0x%x ops=0x%x(basic=1 vote=2 arith=4 ballot=8 shuffle=16 shufRel=32 clustered=64 quad=128) quadAllStages=%d",
                          sg.subgroupSize, sg.supportedStages, sg.supportedOperations, sg.quadOperationsInAllStages);
        if (api13) o.line("子组大小可控: min=%u max=%u maxComputeWorkgroupSubgroups=%u", sgc.minSubgroupSize, sgc.maxSubgroupSize, sgc.maxComputeWorkgroupSubgroups);

        const auto &L = p.limits;
        o.line("限制: 共享内存=%u 工作组调用=%u 工作组=%ux%ux%u 派发=%u 存储缓冲=%u 图像2D=%u 存储图像/阶段=%u 采样器/阶段=%u 描述符集=%u 时间戳周期=%.3fns 时间戳(计算和图形)=%d",
               L.maxComputeSharedMemorySize, L.maxComputeWorkGroupInvocations, L.maxComputeWorkGroupSize[0], L.maxComputeWorkGroupSize[1],
               L.maxComputeWorkGroupSize[2], L.maxComputeWorkGroupCount[0], L.maxStorageBufferRange, L.maxImageDimension2D,
               L.maxPerStageDescriptorStorageImages, L.maxPerStageDescriptorSamplers, L.maxBoundDescriptorSets, L.timestampPeriod,
               L.timestampComputeAndGraphics);

        // 特性
        VkPhysicalDeviceVulkan11Features f11{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES};
        VkPhysicalDeviceVulkan12Features f12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
        VkPhysicalDeviceVulkan13Features f13{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES};
        VkPhysicalDeviceFeatures2 f2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
        if (api12) { f2.pNext = &f11; f11.pNext = &f12; if (api13) f12.pNext = &f13; }
        if (api11) vkGetPhysicalDeviceFeatures2(pd, &f2);
        else vkGetPhysicalDeviceFeatures(pd, &f2.features);
        const auto &F = f2.features;
        o.line("特性(核心): storageImageExtendedFormats=%d readWithoutFormat=%d writeWithoutFormat=%d shaderInt16=%d shaderInt64=%d shaderFloat64=%d robustBufferAccess=%d",
               F.shaderStorageImageExtendedFormats, F.shaderStorageImageReadWithoutFormat, F.shaderStorageImageWriteWithoutFormat,
               F.shaderInt16, F.shaderInt64, F.shaderFloat64, F.robustBufferAccess);
        if (api12) {
            o.line("特性(1.1): storageBuffer16BitAccess=%d uniformAndStorageBuffer16Bit=%d storagePushConstant16=%d storageInputOutput16=%d multiview=%d",
                   f11.storageBuffer16BitAccess, f11.uniformAndStorageBuffer16BitAccess, f11.storagePushConstant16, f11.storageInputOutput16, f11.multiview);
            o.line("特性(1.2): shaderFloat16=%d shaderInt8=%d storageBuffer8Bit=%d vulkanMemoryModel=%d vulkanMemoryModelDeviceScope=%d timelineSemaphore=%d bufferDeviceAddress=%d scalarBlockLayout=%d shaderSubgroupExtendedTypes=%d descriptorIndexing=%d",
                   f12.shaderFloat16, f12.shaderInt8, f12.storageBuffer8BitAccess, f12.vulkanMemoryModel, f12.vulkanMemoryModelDeviceScope,
                   f12.timelineSemaphore, f12.bufferDeviceAddress, f12.scalarBlockLayout, f12.shaderSubgroupExtendedTypes, f12.descriptorIndexing);
        }
        if (api13) o.line("特性(1.3): synchronization2=%d subgroupSizeControl=%d computeFullSubgroups=%d maintenance4=%d shaderIntegerDotProduct=%d dynamicRendering=%d",
                          f13.synchronization2, f13.subgroupSizeControl, f13.computeFullSubgroups, f13.maintenance4, f13.shaderIntegerDotProduct, f13.dynamicRendering);

        // 队列
        uint32_t qn = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(pd, &qn, nullptr);
        std::vector<VkQueueFamilyProperties> qf(qn);
        vkGetPhysicalDeviceQueueFamilyProperties(pd, &qn, qf.data());
        for (uint32_t i = 0; i < qn; i++)
            o.line("队列族 %u: flags=0x%x(图形=1 计算=2 传输=4) 数量=%u 时间戳位=%u", i, qf[i].queueFlags, qf[i].queueCount, qf[i].timestampValidBits);

        // 内存
        VkPhysicalDeviceMemoryProperties mp{};
        vkGetPhysicalDeviceMemoryProperties(pd, &mp);
        for (uint32_t i = 0; i < mp.memoryHeapCount; i++)
            o.line("内存堆 %u: %.0f MB flags=0x%x(本地=1)", i, mp.memoryHeaps[i].size / 1048576.0, mp.memoryHeaps[i].flags);
        for (uint32_t i = 0; i < mp.memoryTypeCount; i++)
            o.line("内存类型 %u: 堆=%u flags=0x%x(本地=1 可映射=2 一致=4 缓存=8 惰性=16 保护=32)", i, mp.memoryTypes[i].heapIndex, mp.memoryTypes[i].propertyFlags);

        // 格式
        struct Fmt { VkFormat f; const char *n; };
        static const Fmt fmts[] = {
            {VK_FORMAT_R8G8B8A8_UNORM, "R8G8B8A8_UNORM"}, {VK_FORMAT_R16G16B16A16_SFLOAT, "R16G16B16A16_SFLOAT"},
            {VK_FORMAT_R16G16_SFLOAT, "R16G16_SFLOAT"}, {VK_FORMAT_R16_SFLOAT, "R16_SFLOAT"}, {VK_FORMAT_R8_UNORM, "R8_UNORM"},
            {VK_FORMAT_R8G8_UNORM, "R8G8_UNORM"}, {VK_FORMAT_R32_SFLOAT, "R32_SFLOAT"}, {VK_FORMAT_R16G16B16A16_UNORM, "R16G16B16A16_UNORM"},
            {VK_FORMAT_A2B10G10R10_UNORM_PACK32, "A2B10G10R10"}, {VK_FORMAT_R32G32B32A32_SFLOAT, "R32G32B32A32_SFLOAT"},
        };
        for (auto &f : fmts) {
            VkFormatProperties fp{};
            vkGetPhysicalDeviceFormatProperties(pd, f.f, &fp);
            o.line("格式 %s: 最优平铺=[%s] 线性平铺=[%s]", f.n, flagsStr(fp.optimalTilingFeatures).c_str(), flagsStr(fp.linearTilingFeatures).c_str());
        }

        // 协作矩阵(G1-Ultra 宣传 FP16 矩阵乘)
        auto cmFn = reinterpret_cast<PFN_vkGetPhysicalDeviceCooperativeMatrixPropertiesKHR>(
            vkGetInstanceProcAddr(inst, "vkGetPhysicalDeviceCooperativeMatrixPropertiesKHR"));
        if (have.count("VK_KHR_cooperative_matrix") && cmFn) {
            uint32_t cn = 0;
            cmFn(pd, &cn, nullptr);
            std::vector<VkCooperativeMatrixPropertiesKHR> cm(cn, {VK_STRUCTURE_TYPE_COOPERATIVE_MATRIX_PROPERTIES_KHR});
            cmFn(pd, &cn, cm.data());
            for (auto &c : cm) o.line("协作矩阵: M=%u N=%u K=%u A=%d B=%d C=%d R=%d scope=%d", c.MSize, c.NSize, c.KSize, (int)c.AType, (int)c.BType, (int)c.CType, (int)c.ResultType, (int)c.scope);
        }
    }
    vkDestroyInstance(inst, nullptr);
    o.line("RESULT: OK");
    return o.s;
}
