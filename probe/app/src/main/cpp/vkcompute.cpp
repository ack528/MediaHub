// 独立于 LSFG 的 Vulkan 计算微基准:FP32 / FP16 算力、显存带宽、RGBA16F 存储图像吞吐、提交与屏障开销。
// 目的:分清"GPU 本身慢"还是"LSFG 的着色器 / 提交模式在这个驱动上特别慢"。
#include "common.hpp"

#include <cstring>

namespace {

const uint32_t kFma32[] =
#include "shaders/fma32.inc"
;
const uint32_t kFma16[] =
#include "shaders/fma16.inc"
;
const uint32_t kBw[] =
#include "shaders/bw.inc"
;
const uint32_t kImg[] =
#include "shaders/img.inc"
;

struct Ctx {
    VkInstance inst = VK_NULL_HANDLE;
    VkPhysicalDevice pd = VK_NULL_HANDLE;
    VkDevice dev = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t qf = 0;
    VkCommandPool pool = VK_NULL_HANDLE;
    VkPhysicalDeviceMemoryProperties mp{};
    bool fp16 = false;
    float tsPeriod = 0;
};

uint32_t findMem(const Ctx &c, uint32_t bits, VkMemoryPropertyFlags want) {
    for (uint32_t i = 0; i < c.mp.memoryTypeCount; i++)
        if ((bits & (1u << i)) && (c.mp.memoryTypes[i].propertyFlags & want) == want) return i;
    return UINT32_MAX;
}

struct Buf {
    VkBuffer b = VK_NULL_HANDLE;
    VkDeviceMemory m = VK_NULL_HANDLE;
};

bool makeBuf(const Ctx &c, VkDeviceSize size, Buf &out, Out &o) {
    VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bi.size = size;
    bi.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    if (vkCreateBuffer(c.dev, &bi, nullptr, &out.b) != VK_SUCCESS) { o.line("  创建缓冲失败"); return false; }
    VkMemoryRequirements mr;
    vkGetBufferMemoryRequirements(c.dev, out.b, &mr);
    uint32_t t = findMem(c, mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (t == UINT32_MAX) t = findMem(c, mr.memoryTypeBits, 0);
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = mr.size;
    ai.memoryTypeIndex = t;
    VkResult r = vkAllocateMemory(c.dev, &ai, nullptr, &out.m);
    if (r != VK_SUCCESS) { o.line("  分配显存 %.0fMB 失败: %s", size / 1048576.0, vkResultName(r)); return false; }
    vkBindBufferMemory(c.dev, out.b, out.m, 0);
    return true;
}

struct Pipe {
    VkDescriptorSetLayout dsl = VK_NULL_HANDLE;
    VkPipelineLayout pl = VK_NULL_HANDLE;
    VkPipeline p = VK_NULL_HANDLE;
    VkShaderModule sm = VK_NULL_HANDLE;
    VkDescriptorPool dp = VK_NULL_HANDLE;
    VkDescriptorSet ds = VK_NULL_HANDLE;
};

// kinds: 每个绑定的描述符类型
bool makePipe(const Ctx &c, const uint32_t *code, size_t bytes, std::vector<VkDescriptorType> kinds, uint32_t pushBytes, Pipe &p, Out &o) {
    VkShaderModuleCreateInfo si{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    si.codeSize = bytes;
    si.pCode = code;
    VkResult r = vkCreateShaderModule(c.dev, &si, nullptr, &p.sm);
    if (r != VK_SUCCESS) { o.line("  创建着色器模块失败: %s", vkResultName(r)); return false; }
    std::vector<VkDescriptorSetLayoutBinding> bs;
    for (uint32_t i = 0; i < kinds.size(); i++) bs.push_back({i, kinds[i], 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr});
    VkDescriptorSetLayoutCreateInfo li{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    li.bindingCount = (uint32_t)bs.size();
    li.pBindings = bs.data();
    vkCreateDescriptorSetLayout(c.dev, &li, nullptr, &p.dsl);
    VkPushConstantRange pcr{VK_SHADER_STAGE_COMPUTE_BIT, 0, pushBytes};
    VkPipelineLayoutCreateInfo pli{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pli.setLayoutCount = 1;
    pli.pSetLayouts = &p.dsl;
    if (pushBytes) { pli.pushConstantRangeCount = 1; pli.pPushConstantRanges = &pcr; }
    vkCreatePipelineLayout(c.dev, &pli, nullptr, &p.pl);
    VkComputePipelineCreateInfo pi{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    pi.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_COMPUTE_BIT, p.sm, "main", nullptr};
    pi.layout = p.pl;
    double t0 = nowMs();
    r = vkCreateComputePipelines(c.dev, VK_NULL_HANDLE, 1, &pi, nullptr, &p.p);
    if (r != VK_SUCCESS) { o.line("  创建计算管线失败: %s", vkResultName(r)); return false; }
    o.line("  管线创建 %.1f ms", nowMs() - t0);
    std::vector<VkDescriptorPoolSize> sz;
    for (auto k : kinds) sz.push_back({k, 1});
    VkDescriptorPoolCreateInfo dpi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    dpi.maxSets = 1;
    dpi.poolSizeCount = (uint32_t)sz.size();
    dpi.pPoolSizes = sz.data();
    vkCreateDescriptorPool(c.dev, &dpi, nullptr, &p.dp);
    VkDescriptorSetAllocateInfo dai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    dai.descriptorPool = p.dp;
    dai.descriptorSetCount = 1;
    dai.pSetLayouts = &p.dsl;
    vkAllocateDescriptorSets(c.dev, &dai, &p.ds);
    return true;
}

void destroyPipe(const Ctx &c, Pipe &p) {
    if (p.dp) vkDestroyDescriptorPool(c.dev, p.dp, nullptr);
    if (p.p) vkDestroyPipeline(c.dev, p.p, nullptr);
    if (p.pl) vkDestroyPipelineLayout(c.dev, p.pl, nullptr);
    if (p.dsl) vkDestroyDescriptorSetLayout(c.dev, p.dsl, nullptr);
    if (p.sm) vkDestroyShaderModule(c.dev, p.sm, nullptr);
    p = {};
}

void writeBuf(const Ctx &c, Pipe &p, uint32_t binding, const Buf &b) {
    VkDescriptorBufferInfo bi{b.b, 0, VK_WHOLE_SIZE};
    VkWriteDescriptorSet w{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    w.dstSet = p.ds;
    w.dstBinding = binding;
    w.descriptorCount = 1;
    w.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    w.pBufferInfo = &bi;
    vkUpdateDescriptorSets(c.dev, 1, &w, 0, nullptr);
}

// 录制 + 提交 + 等待;返回毫秒,失败返回 -1
template <class F>
double timeSubmit(const Ctx &c, F record, Out &o) {
    VkCommandBuffer cb;
    VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    ai.commandPool = c.pool;
    ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    ai.commandBufferCount = 1;
    vkAllocateCommandBuffers(c.dev, &ai, &cb);
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    vkBeginCommandBuffer(cb, &bi);
    record(cb);
    vkEndCommandBuffer(cb);
    VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    VkFence fence;
    vkCreateFence(c.dev, &fi, nullptr, &fence);
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cb;
    double t0 = nowMs();
    VkResult r = vkQueueSubmit(c.queue, 1, &si, fence);
    if (r == VK_SUCCESS) r = vkWaitForFences(c.dev, 1, &fence, VK_TRUE, 20ull * 1000000000ull);
    double t = nowMs() - t0;
    vkDestroyFence(c.dev, fence, nullptr);
    vkFreeCommandBuffers(c.dev, c.pool, 1, &cb);
    if (r != VK_SUCCESS) { o.line("  提交/等待失败: %s", vkResultName(r)); return -1; }
    return t;
}

void barrierCompute(VkCommandBuffer cb) {
    VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT};
    vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
}

} // namespace

std::string runVkCompute(const std::string &driver) {
    Out o;
    Ctx c;
    if (!driverSelect(driver, o)) { o.line("RESULT: FAIL 驱动加载失败"); return o.s; }
    if (!driverVolkInit(o)) { o.line("RESULT: FAIL 没有 Vulkan"); return o.s; }

    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "probe";
    app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ci.pApplicationInfo = &app;
    VkResult r = vkCreateInstance(&ci, nullptr, &c.inst);
    if (r != VK_SUCCESS) { o.line("vkCreateInstance 失败: %s", vkResultName(r)); o.line("RESULT: FAIL"); return o.s; }
    volkLoadInstance(c.inst);
    uint32_t dn = 0;
    vkEnumeratePhysicalDevices(c.inst, &dn, nullptr);
    if (!dn) { o.line("没有物理设备"); o.line("RESULT: FAIL"); return o.s; }
    std::vector<VkPhysicalDevice> devs(dn);
    vkEnumeratePhysicalDevices(c.inst, &dn, devs.data());
    c.pd = devs[0];
    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(c.pd, &props);
    c.tsPeriod = props.limits.timestampPeriod;
    vkGetPhysicalDeviceMemoryProperties(c.pd, &c.mp);
    o.line("设备: %s  api=%u.%u.%u", props.deviceName, VK_VERSION_MAJOR(props.apiVersion), VK_VERSION_MINOR(props.apiVersion), VK_VERSION_PATCH(props.apiVersion));

    uint32_t qn = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(c.pd, &qn, nullptr);
    std::vector<VkQueueFamilyProperties> qf(qn);
    vkGetPhysicalDeviceQueueFamilyProperties(c.pd, &qn, qf.data());
    c.qf = UINT32_MAX;
    for (uint32_t i = 0; i < qn; i++)
        if (qf[i].queueFlags & VK_QUEUE_COMPUTE_BIT) { c.qf = i; break; }
    if (c.qf == UINT32_MAX) { o.line("没有计算队列"); o.line("RESULT: FAIL"); return o.s; }

    // FP16 特性
    VkPhysicalDeviceShaderFloat16Int8Features fp16f{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES};
    VkPhysicalDeviceFeatures2 f2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    f2.pNext = &fp16f;
    vkGetPhysicalDeviceFeatures2(c.pd, &f2);
    c.fp16 = fp16f.shaderFloat16 == VK_TRUE;
    uint32_t en = 0;
    vkEnumerateDeviceExtensionProperties(c.pd, nullptr, &en, nullptr);
    std::vector<VkExtensionProperties> dext(en);
    vkEnumerateDeviceExtensionProperties(c.pd, nullptr, &en, dext.data());
    bool hasF16Ext = false;
    for (auto &e : dext) if (!strcmp(e.extensionName, "VK_KHR_shader_float16_int8")) hasF16Ext = true;
    const bool api12 = props.apiVersion >= VK_API_VERSION_1_2;
    VkPhysicalDeviceShaderFloat16Int8Features enable16{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES};
    enable16.shaderFloat16 = c.fp16 ? VK_TRUE : VK_FALSE;
    VkPhysicalDeviceFeatures2 enf2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    enf2.pNext = &enable16;
    enf2.features.shaderStorageImageExtendedFormats = f2.features.shaderStorageImageExtendedFormats;
    enf2.features.shaderStorageImageReadWithoutFormat = f2.features.shaderStorageImageReadWithoutFormat;
    enf2.features.shaderStorageImageWriteWithoutFormat = f2.features.shaderStorageImageWriteWithoutFormat;
    std::vector<const char *> exts;
    if (c.fp16 && !api12 && hasF16Ext) exts.push_back("VK_KHR_shader_float16_int8");
    float prio = 1.0f;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = c.qf;
    qci.queueCount = 1;
    qci.pQueuePriorities = &prio;
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.pNext = &enf2;
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    dci.enabledExtensionCount = (uint32_t)exts.size();
    dci.ppEnabledExtensionNames = exts.data();
    double t0 = nowMs();
    r = vkCreateDevice(c.pd, &dci, nullptr, &c.dev);
    if (r != VK_SUCCESS) { o.line("vkCreateDevice 失败: %s", vkResultName(r)); o.line("RESULT: FAIL"); return o.s; }
    o.line("vkCreateDevice %.1f ms  shaderFloat16=%d", nowMs() - t0, (int)c.fp16);
    volkLoadDevice(c.dev);
    vkGetDeviceQueue(c.dev, c.qf, 0, &c.queue);
    VkCommandPoolCreateInfo cpi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    cpi.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    cpi.queueFamilyIndex = c.qf;
    vkCreateCommandPool(c.dev, &cpi, nullptr, &c.pool);

    bool failed = false;

    // ---- 1/2. FMA 算力 ----
    auto fmaTest = [&](const char *name, const uint32_t *code, size_t bytes, double flopsPerIter) {
        o.line("[%s]", name);
        Buf out;
        const uint32_t threads = 1u << 20;
        if (!makeBuf(c, threads * 4ull, out, o)) { failed = true; return; }
        Pipe p;
        if (!makePipe(c, code, bytes, {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER}, 4, p, o)) { failed = true; destroyPipe(c, p); return; }
        writeBuf(c, p, 0, out);
        auto run = [&](uint32_t iters) {
            return timeSubmit(c, [&](VkCommandBuffer cb) {
                vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, p.p);
                vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, p.pl, 0, 1, &p.ds, 0, nullptr);
                vkCmdPushConstants(cb, p.pl, VK_SHADER_STAGE_COMPUTE_BIT, 0, 4, &iters);
                vkCmdDispatch(cb, threads / 64, 1, 1);
            }, o);
        };
        double t = run(64); // 预热 + 校准
        if (t < 0) { failed = true; destroyPipe(c, p); return; }
        uint32_t iters = 64;
        if (t < 40) iters = (uint32_t)std::min(1e6, 64.0 * 80.0 / std::max(t, 0.05));
        std::vector<double> ts;
        for (int i = 0; i < 5; i++) {
            double x = run(iters);
            if (x < 0) { failed = true; break; }
            ts.push_back(x);
        }
        if (!ts.empty()) {
            Stats s = stats(ts);
            double flops = (double)threads * iters * flopsPerIter;
            o.line("  iters=%u 中位 %.1f ms (最小 %.1f 最大 %.1f)  ≈ %.0f GFLOPS(中位) / %.0f GFLOPS(最快)", iters, s.median, s.mn, s.mx,
                   flops / (s.median * 1e6), flops / (s.mn * 1e6));
        }
        destroyPipe(c, p);
        vkDestroyBuffer(c.dev, out.b, nullptr);
        vkFreeMemory(c.dev, out.m, nullptr);
    };
    fmaTest("FP32 FMA", kFma32, sizeof kFma32, 16.0);
    if (c.fp16) fmaTest("FP16 FMA(f16vec2)", kFma16, sizeof kFma16, 32.0);
    else o.line("[FP16 FMA] 跳过:设备不支持 shaderFloat16");

    // ---- 3. 显存带宽 ----
    {
        o.line("[缓冲带宽 读+写]");
        const VkDeviceSize sz = 128ull << 20;
        Buf a, b;
        if (makeBuf(c, sz, a, o) && makeBuf(c, sz, b, o)) {
            Pipe p;
            if (makePipe(c, kBw, sizeof kBw, {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER}, 0, p, o)) {
                writeBuf(c, p, 0, a);
                writeBuf(c, p, 1, b);
                const uint32_t groups = (uint32_t)(sz / 16 / 64);
                std::vector<double> ts;
                for (int i = 0; i < 6; i++) {
                    double x = timeSubmit(c, [&](VkCommandBuffer cb) {
                        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, p.p);
                        vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, p.pl, 0, 1, &p.ds, 0, nullptr);
                        for (int k = 0; k < 4; k++) { vkCmdDispatch(cb, groups, 1, 1); barrierCompute(cb); }
                    }, o);
                    if (x < 0) { failed = true; break; }
                    if (i > 0) ts.push_back(x);
                }
                if (!ts.empty()) {
                    Stats s = stats(ts);
                    o.line("  4×(读128MB+写128MB) 中位 %.1f ms ≈ %.1f GB/s", s.median, 4.0 * 2 * (double)sz / (s.median * 1e6));
                }
                destroyPipe(c, p);
            } else failed = true;
        } else failed = true;
        if (a.b) { vkDestroyBuffer(c.dev, a.b, nullptr); vkFreeMemory(c.dev, a.m, nullptr); }
        if (b.b) { vkDestroyBuffer(c.dev, b.b, nullptr); vkFreeMemory(c.dev, b.m, nullptr); }
    }

    // ---- 4. 提交与屏障开销 ----
    {
        o.line("[提交 / 屏障开销](1 个工作组的空派发)");
        Buf a, b;
        Pipe p;
        if (makeBuf(c, 1 << 20, a, o) && makeBuf(c, 1 << 20, b, o) &&
            makePipe(c, kBw, sizeof kBw, {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER}, 0, p, o)) {
            writeBuf(c, p, 0, a);
            writeBuf(c, p, 1, b);
            std::vector<double> ts;
            for (int i = 0; i < 60; i++) {
                double x = timeSubmit(c, [&](VkCommandBuffer cb) {
                    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, p.p);
                    vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, p.pl, 0, 1, &p.ds, 0, nullptr);
                    vkCmdDispatch(cb, 1, 1, 1);
                }, o);
                if (x < 0) { failed = true; break; }
                if (i >= 10) ts.push_back(x);
            }
            if (!ts.empty()) { Stats s = stats(ts); o.line("  单次 提交+等待 中位 %.3f ms  p95 %.3f ms  最大 %.3f ms", s.median, s.p95, s.mx); }
            ts.clear();
            for (int i = 0; i < 20; i++) {
                double x = timeSubmit(c, [&](VkCommandBuffer cb) {
                    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, p.p);
                    vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, p.pl, 0, 1, &p.ds, 0, nullptr);
                    for (int k = 0; k < 200; k++) vkCmdDispatch(cb, 1, 1, 1);
                }, o);
                if (x < 0) { failed = true; break; }
                if (i >= 5) ts.push_back(x);
            }
            if (!ts.empty()) { Stats s = stats(ts); o.line("  200 次无屏障派发 中位 %.3f ms (每次 %.1f µs)", s.median, s.median * 1000 / 200); }
            ts.clear();
            for (int i = 0; i < 20; i++) {
                double x = timeSubmit(c, [&](VkCommandBuffer cb) {
                    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, p.p);
                    vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, p.pl, 0, 1, &p.ds, 0, nullptr);
                    for (int k = 0; k < 200; k++) { vkCmdDispatch(cb, 1, 1, 1); barrierCompute(cb); }
                }, o);
                if (x < 0) { failed = true; break; }
                if (i >= 5) ts.push_back(x);
            }
            if (!ts.empty()) { Stats s = stats(ts); o.line("  200 次 派发+屏障 中位 %.3f ms (每对 %.1f µs)", s.median, s.median * 1000 / 200); }
        } else failed = true;
        destroyPipe(c, p);
        if (a.b) { vkDestroyBuffer(c.dev, a.b, nullptr); vkFreeMemory(c.dev, a.m, nullptr); }
        if (b.b) { vkDestroyBuffer(c.dev, b.b, nullptr); vkFreeMemory(c.dev, b.m, nullptr); }
    }

    // ---- 5. RGBA16F 存储图像 ----
    {
        o.line("[RGBA16F 存储图像 读+写] 3840x2160");
        VkFormatProperties fp{};
        vkGetPhysicalDeviceFormatProperties(c.pd, VK_FORMAT_R16G16B16A16_SFLOAT, &fp);
        if (!(fp.optimalTilingFeatures & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT)) {
            o.line("  跳过:R16G16B16A16_SFLOAT 不支持存储图像");
        } else {
            const uint32_t W = 3840, H = 2160;
            VkImage img[2] = {};
            VkDeviceMemory mem[2] = {};
            VkImageView view[2] = {};
            bool ok = true;
            for (int i = 0; i < 2 && ok; i++) {
                VkImageCreateInfo ii{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
                ii.imageType = VK_IMAGE_TYPE_2D;
                ii.format = VK_FORMAT_R16G16B16A16_SFLOAT;
                ii.extent = {W, H, 1};
                ii.mipLevels = 1;
                ii.arrayLayers = 1;
                ii.samples = VK_SAMPLE_COUNT_1_BIT;
                ii.tiling = VK_IMAGE_TILING_OPTIMAL;
                ii.usage = VK_IMAGE_USAGE_STORAGE_BIT;
                ii.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
                if (vkCreateImage(c.dev, &ii, nullptr, &img[i]) != VK_SUCCESS) { o.line("  创建图像失败"); ok = false; break; }
                VkMemoryRequirements mr;
                vkGetImageMemoryRequirements(c.dev, img[i], &mr);
                VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
                ai.allocationSize = mr.size;
                ai.memoryTypeIndex = findMem(c, mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                if (vkAllocateMemory(c.dev, &ai, nullptr, &mem[i]) != VK_SUCCESS) { o.line("  分配图像显存失败"); ok = false; break; }
                vkBindImageMemory(c.dev, img[i], mem[i], 0);
                VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
                vi.image = img[i];
                vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
                vi.format = VK_FORMAT_R16G16B16A16_SFLOAT;
                vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
                vkCreateImageView(c.dev, &vi, nullptr, &view[i]);
            }
            Pipe p;
            if (ok && makePipe(c, kImg, sizeof kImg, {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}, 0, p, o)) {
                VkDescriptorImageInfo di[2] = {{VK_NULL_HANDLE, view[0], VK_IMAGE_LAYOUT_GENERAL}, {VK_NULL_HANDLE, view[1], VK_IMAGE_LAYOUT_GENERAL}};
                VkWriteDescriptorSet w[2] = {};
                for (int i = 0; i < 2; i++) {
                    w[i] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
                    w[i].dstSet = p.ds;
                    w[i].dstBinding = i;
                    w[i].descriptorCount = 1;
                    w[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
                    w[i].pImageInfo = &di[i];
                }
                vkUpdateDescriptorSets(c.dev, 2, w, 0, nullptr);
                std::vector<double> ts;
                for (int i = 0; i < 7; i++) {
                    double x = timeSubmit(c, [&](VkCommandBuffer cb) {
                        VkImageMemoryBarrier ib[2] = {};
                        for (int k = 0; k < 2; k++) {
                            ib[k] = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
                            ib[k].dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
                            ib[k].oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
                            ib[k].newLayout = VK_IMAGE_LAYOUT_GENERAL;
                            ib[k].srcQueueFamilyIndex = ib[k].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                            ib[k].image = img[k];
                            ib[k].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
                        }
                        vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 2, ib);
                        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, p.p);
                        vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, p.pl, 0, 1, &p.ds, 0, nullptr);
                        for (int k = 0; k < 4; k++) { vkCmdDispatch(cb, W / 8, H / 8, 1); barrierCompute(cb); }
                    }, o);
                    if (x < 0) { failed = true; break; }
                    if (i > 0) ts.push_back(x);
                }
                if (!ts.empty()) {
                    Stats s = stats(ts);
                    const double bytes = 4.0 * 2 * W * H * 8;
                    o.line("  4 次 4K 读+写 中位 %.1f ms ≈ %.1f GB/s  (%.2f ms / 4K 帧)", s.median, bytes / (s.median * 1e6), s.median / 4);
                }
            } else failed = true;
            destroyPipe(c, p);
            for (int i = 0; i < 2; i++) {
                if (view[i]) vkDestroyImageView(c.dev, view[i], nullptr);
                if (img[i]) vkDestroyImage(c.dev, img[i], nullptr);
                if (mem[i]) vkFreeMemory(c.dev, mem[i], nullptr);
            }
        }
    }

    vkDestroyCommandPool(c.dev, c.pool, nullptr);
    vkDestroyDevice(c.dev, nullptr);
    vkDestroyInstance(c.inst, nullptr);
    o.line(failed ? "RESULT: FAIL 部分子项失败(见上)" : "RESULT: OK");
    return o.s;
}
