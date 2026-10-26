add_compile_options(
        -O3
        -fPIC
        -ffunction-sections
        -fdata-sections
        -D_FORTIFY_SOURCE=0
        # 32 位 ABI 下让 off_t / struct stat 为 64 位（nftw 会自动映射到 nftw64），
        # 避免大文件累加时溢出。64 位 ABI 下为空操作。
        -D_FILE_OFFSET_BITS=64
        -ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=/src
)

add_link_options(
        -s
        -flto
        -Wl,--gc-sections
        -Wl,--build-id=none
        -Wl,--hash-style=both
)

add_library(nativelib SHARED
        nativelib.cpp
)

target_link_libraries(nativelib
        android
        log
)

# 主机侧单元测试（默认关闭）：不会进入 Android 构建，因为只有当次 CMake 不是交叉编译时才可见。
# 需要符号链接支持，请在 Linux/macOS/WSL/CI 上运行，例如：
#   cmake -S <jni/nativelib> -B /tmp/nativelib-test -DNATIVELIB_BUILD_HOST_TESTS=ON
#   cmake --build /tmp/nativelib-test && /tmp/nativelib-test/calculate_size_test
if(NOT ANDROID)
    option(NATIVELIB_BUILD_HOST_TESTS "Build host unit tests for nativelib size traversal" OFF)
    if(NATIVELIB_BUILD_HOST_TESTS)
        add_executable(calculate_size_test tests/calculate_size_test.cpp)
        target_compile_definitions(calculate_size_test PRIVATE NATIVELIB_UNIT_TEST)
        target_compile_options(calculate_size_test PRIVATE -O1)
    endif()
endif()
