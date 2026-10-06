cmake_minimum_required(VERSION 3.22.1)
project(localtg-native LANGUAGES C CXX)

# LSFG(Lossless Scaling 帧生成)的原生部分。
# 源码由 tools\setup-lsfg.ps1 从 参考项目\LSFG-Android 复制到 lsfg\vendor\(不提交);
# 没有复制过的话 Gradle 根本不会启用这个 CMake 工程(见 app\build.gradle.kts)。
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(VENDOR "${CMAKE_CURRENT_SOURCE_DIR}/lsfg/vendor")
set(LSFG_ROOT "${VENDOR}/lsfg-vk-android")
if(NOT EXISTS "${LSFG_ROOT}/framegen/CMakeLists.txt")
    message(FATAL_ERROR "没有找到 ${LSFG_ROOT}/framegen —— 先运行 tools\\setup-lsfg.ps1")
endif()

# volk:Vulkan 加载器(两个文件,直接编译)
add_library(volk STATIC "${LSFG_ROOT}/thirdparty/volk/volk.c")
target_include_directories(volk PUBLIC "${LSFG_ROOT}/thirdparty/volk")
target_compile_definitions(volk PUBLIC VK_USE_PLATFORM_ANDROID_KHR=1)

# pe-parse:PE 解析(跳过上游 CMake,它依赖 ICU;UTF-16 → UTF-8 用参考项目自带的最小实现)
set(PEPARSE_DIR "${LSFG_ROOT}/thirdparty/pe-parse/pe-parser-library")
add_library(pe-parse STATIC
    "${PEPARSE_DIR}/src/buffer.cpp"
    "${PEPARSE_DIR}/src/parse.cpp"
    "${VENDOR}/app/unicode_minimal.cpp"
)
target_include_directories(pe-parse PUBLIC "${PEPARSE_DIR}/include")
target_compile_features(pe-parse PUBLIC cxx_std_17)

# dxbc:DXVK 的 DXBC → SPIR-V 翻译器
add_subdirectory("${LSFG_ROOT}/thirdparty/dxbc" "${CMAKE_BINARY_DIR}/dxbc" EXCLUDE_FROM_ALL)

# framegen:Vulkan 帧生成管线(原样使用)
add_subdirectory("${LSFG_ROOT}/framegen" "${CMAKE_BINARY_DIR}/framegen" EXCLUDE_FROM_ALL)

find_library(log-lib log)

add_library(lsfgbridge SHARED
    lsfg/lsfg_bridge.cpp
    lsfg/vendor/app/android_shader_loader.cpp
)
target_include_directories(lsfgbridge PRIVATE lsfg/shim lsfg/vendor/app)
target_link_libraries(lsfgbridge
    PRIVATE
        lsfg-vk-framegen
        dxbc
        pe-parse
        volk
        ${log-lib}
        android
        EGL
        GLESv3
)
# Android 15+ 的 16KB 页面设备要求原生库按 16KB 对齐
target_link_options(lsfgbridge PRIVATE -Wl,-z,max-page-size=16384 -Wl,--gc-sections)
