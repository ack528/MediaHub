// SPIR-V 改写:把 OpImageSampleExplicitLod 的常量纹素偏移(ConstOffset)换成"坐标 + 偏移 / 图像尺寸"。
// 起因:Mali-G1(驱动 r54p1)上 LSFG 的 alpha 阶段着色器(每次采样都带 ±1 的 ConstOffset)算出的结果和其它 GPU 不同,
// 而不用 ConstOffset 的 mipmaps 着色器结果完全一致。双线性过滤 + 在纹素中心采样时,两种写法数学上等价;
// CLAMP_TO_BORDER 下越界同样取到边界色。
#include "spvpatch.hpp"

#include <cstring>
#include <unordered_map>
#include <unordered_set>

namespace {

enum : uint32_t {
    OpTypeInt = 21, OpTypeFloat = 22, OpTypeVector = 23, OpTypeSampledImage = 27,
    OpConstant = 43, OpConstantComposite = 44, OpFunction = 54,
    OpImageSampleExplicitLod = 88, OpImage = 100, OpImageQuerySizeLod = 103,
    OpConvertSToF = 111, OpFAdd = 129, OpFDiv = 136,
};

// 有 [结果类型, 结果] 两个操作数的常见指令(用来查坐标的类型)
const std::unordered_set<uint32_t> kTyped = {
    12, 43, 44, 61, 65, 79, 80, 81, 83, 86, 87, 88, 89, 90, 91, 95, 98, 100, 103, 104,
    109, 110, 111, 112, 113, 114, 115, 124, 126, 127, 128, 129, 130, 131, 132, 133, 134, 135, 136,
    137, 140, 141, 142, 143, 144, 145, 146, 148, 169, 170, 171, 172, 173, 174, 175, 176, 177, 178,
    180, 181, 182, 183, 184, 185, 186, 187, 188, 189, 190, 191, 245,
};

uint32_t operandCount(uint32_t bit) {
    switch (bit) {
        case 0x1: case 0x2: case 0x8: case 0x10: case 0x20: case 0x40: case 0x80: return 1; // Bias Lod ConstOffset Offset ConstOffsets Sample MinLod
        case 0x4: return 2; // Grad
        default: return 0xffffffff;
    }
}

} // namespace

std::vector<uint8_t> spvRemoveConstOffset(const std::vector<uint8_t>& in, int* patched) {
    if (patched) *patched = 0;
    if (in.size() < 20 || in.size() % 4) return in;
    std::vector<uint32_t> w(in.size() / 4);
    std::memcpy(w.data(), in.data(), in.size());
    if (w[0] != 0x07230203) return in;

    // 第一遍:收集类型和常量
    std::unordered_map<uint32_t, uint32_t> typeOf;        // id → 类型 id
    std::unordered_map<uint32_t, uint32_t> sampledImgType; // OpTypeSampledImage id → OpTypeImage id
    uint32_t f32 = 0, i32 = 0, v2f = 0, v2i = 0, c0 = 0;
    std::unordered_map<uint32_t, std::pair<uint32_t, uint32_t>> vecs; // id → (分量类型, 个数)
    for (size_t i = 5; i < w.size();) {
        const uint32_t wc = w[i] >> 16, op = w[i] & 0xffff;
        if (wc == 0 || i + wc > w.size()) return in;
        if (op == OpTypeFloat && w[i + 2] == 32) f32 = w[i + 1];
        if (op == OpTypeInt && w[i + 2] == 32 && w[i + 3] == 1) i32 = w[i + 1];
        if (op == OpTypeVector) vecs[w[i + 1]] = {w[i + 2], w[i + 3]};
        if (op == OpTypeSampledImage) sampledImgType[w[i + 1]] = w[i + 2];
        if (kTyped.count(op) && wc >= 3) typeOf[w[i + 2]] = w[i + 1];
        i += wc;
    }
    for (auto& [id, v] : vecs) {
        if (v.first == f32 && v.second == 2) v2f = id;
        if (v.first == i32 && v.second == 2) v2i = id;
    }
    for (size_t i = 5; i < w.size();) {
        const uint32_t wc = w[i] >> 16, op = w[i] & 0xffff;
        if (op == OpConstant && wc == 4 && w[i + 1] == i32 && w[i + 3] == 0) { c0 = w[i + 2]; break; }
        i += wc;
    }
    if (!f32 || !i32 || !v2f || !v2i || !c0) return in;

    uint32_t bound = w[3];
    std::vector<uint32_t> out(w.begin(), w.begin() + 5);
    out.reserve(w.size() * 2);
    int count = 0;
    for (size_t i = 5; i < w.size();) {
        const uint32_t wc = w[i] >> 16, op = w[i] & 0xffff;
        bool done = false;
        if (op == OpImageSampleExplicitLod && wc >= 6) {
            const uint32_t rtype = w[i + 1], rid = w[i + 2], si = w[i + 3], coord = w[i + 4], mask = w[i + 5];
            // 解析图像操作数
            std::vector<std::pair<uint32_t, std::vector<uint32_t>>> ops;
            size_t p = i + 6;
            bool ok = (mask & ~0xffu) == 0;
            for (uint32_t bit = 1; ok && bit <= 0x80; bit <<= 1) {
                if (!(mask & bit)) continue;
                const uint32_t n = operandCount(bit);
                if (n == 0xffffffff || p + n > i + wc) { ok = false; break; }
                ops.push_back({bit, std::vector<uint32_t>(w.begin() + p, w.begin() + p + n)});
                p += n;
            }
            const auto ct = typeOf.find(coord);
            const auto st = typeOf.find(si);
            if (ok && (mask & 0x8) && ct != typeOf.end() && ct->second == v2f && st != typeOf.end() && sampledImgType.count(st->second)) {
                const uint32_t imgType = sampledImgType[st->second];
                uint32_t offId = 0;
                for (auto& o : ops) if (o.first == 0x8) offId = o.second[0];
                const uint32_t img = bound++, sz = bound++, szf = bound++, offf = bound++, d = bound++, c2 = bound++;
                auto emit = [&](std::initializer_list<uint32_t> v) {
                    out.push_back(static_cast<uint32_t>(v.size()) << 16 | *v.begin()); // 字数 = 操作码字 + 操作数(v 里第一个就是操作码)
                    out.insert(out.end(), v.begin() + 1, v.end());
                };
                emit({OpImage, imgType, img, si});
                emit({OpImageQuerySizeLod, v2i, sz, img, c0});
                emit({OpConvertSToF, v2f, szf, sz});
                emit({OpConvertSToF, v2f, offf, offId});
                emit({OpFDiv, v2f, d, offf, szf});
                emit({OpFAdd, v2f, c2, coord, d});
                std::vector<uint32_t> s = {0, rtype, rid, si, c2, mask & ~0x8u};
                for (auto& o : ops) if (o.first != 0x8) s.insert(s.end(), o.second.begin(), o.second.end());
                s[0] = static_cast<uint32_t>(s.size()) << 16 | OpImageSampleExplicitLod;
                out.insert(out.end(), s.begin(), s.end());
                count++;
                done = true;
            }
        }
        if (!done) out.insert(out.end(), w.begin() + i, w.begin() + i + wc);
        i += wc;
    }
    out[3] = bound;
    if (patched) *patched = count;
    std::vector<uint8_t> r(out.size() * 4);
    std::memcpy(r.data(), out.data(), r.size());
    return r;
}
