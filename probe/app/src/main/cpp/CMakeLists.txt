cmake_minimum_required(VERSION 3.22.1)
project(dimensity-probe LANGUAGES C CXX)

# 检测程序的原生部分:复用主工程里 tools\setup-lsfg.ps1 复制好的 LSFG 源码(framegen / dxbc / pe-parse / volk),
# 不重复拷贝。必须从 tools\build-probe.ps1 构建(把项目根目录映射成 L: 盘,CMake 处理不了中文路径)。
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

get_filename_component(MAIN_CPP "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../android/app/src/main/cpp" ABSOLUTE)
set(VENDOR "${MAIN_CPP}/lsfg/vendor")
set(LSFG_ROOT "${VENDOR}/lsfg-vk-android")
if(NOT EXISTS "${LSFG_ROOT}/framegen/CMakeLists.txt")
    message(FATAL_ERROR "没有找到 ${LSFG_ROOT}/framegen —— 先运行 tools\setup-lsfg.ps1")
endif()

add_library(volk STATIC "${LSFG_ROOT}/thirdparty/volk/volk.c")
target_include_directories(volk PUBLIC "${LSFG_ROOT}/thirdparty/volk")
target_compile_definitions(volk PUBLIC VK_USE_PLATFORM_ANDROID_KHR=1)

set(PEPARSE_DIR "${LSFG_ROOT}/thirdparty/pe-parse/pe-parser-library")
add_library(pe-parse STATIC
    "${PEPARSE_DIR}/src/buffer.cpp"
    "${PEPARSE_DIR}/src/parse.cpp"
    "${VENDOR}/app/unicode_minimal.cpp"
)
target_include_directories(pe-parse PUBLIC "${PEPARSE_DIR}/include")
target_compile_features(pe-parse PUBLIC cxx_std_17)

add_subdirectory("${LSFG_ROOT}/thirdparty/dxbc" "${CMAKE_BINARY_DIR}/dxbc" EXCLUDE_FROM_ALL)
add_subdirectory("${LSFG_ROOT}/framegen" "${CMAKE_BINARY_DIR}/framegen" EXCLUDE_FROM_ALL)
# 让 framegen 的 Instance 允许外部指定 Vulkan 入口(自定义驱动),见 tools\lsfg-patches\framegen\src\core\instance.cpp
target_compile_definitions(lsfg-vk-framegen PRIVATE LSFG_PROBE=1)

find_library(log-lib log)

add_library(probe SHARED
    probe.cpp
    vkinfo.cpp
    vkcompute.cpp
    glinterop.cpp
    lsfgbench.cpp
    driver.cpp
    "${MAIN_CPP}/lsfg/spvpatch.cpp"
    "${VENDOR}/app/android_shader_loader.cpp"
)
target_include_directories(probe PRIVATE . "${VENDOR}/app" "${MAIN_CPP}/lsfg/shim" "${MAIN_CPP}/lsfg")
target_link_libraries(probe
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
target_link_options(probe PRIVATE -Wl,-z,max-page-size=16384 -Wl,--gc-sections)

# 主程序真实渲染管线用的 LSFG JNI 桥(com.localtg.render.LsfgNative),回放测试用 —— 和主程序同一份源码
add_library(lsfgbridge SHARED
    "${MAIN_CPP}/lsfg/lsfg_bridge.cpp"
    "${MAIN_CPP}/lsfg/spvpatch.cpp"
    "${VENDOR}/app/android_shader_loader.cpp"
)
target_include_directories(lsfgbridge PRIVATE "${MAIN_CPP}/lsfg/shim" "${VENDOR}/app" "${MAIN_CPP}/lsfg")
target_link_libraries(lsfgbridge PRIVATE lsfg-vk-framegen dxbc pe-parse volk ${log-lib} android EGL GLESv3)
target_link_options(lsfgbridge PRIVATE -Wl,-z,max-page-size=16384 -Wl,--gc-sections)
