#pragma once
#include <cstdint>
#include <vector>

// 把 SPIR-V 里 OpImageSampleExplicitLod 的 ConstOffset 改成"坐标 + 偏移 / 图像尺寸"(见 spvpatch.cpp)。
// patched 返回改写了几处;无法安全改写的指令原样保留。
std::vector<uint8_t> spvRemoveConstOffset(const std::vector<uint8_t>& in, int* patched);
