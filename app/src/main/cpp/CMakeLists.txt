cmake_minimum_required(VERSION 3.22.1)
project(localai)

set(CMAKE_CXX_STANDARD 17)

# ─── llama.cpp source ────────────────────────────────────────────────────────
# Clone llama.cpp into app/src/main/cpp/llama.cpp/
# git clone https://github.com/ggerganov/llama.cpp app/src/main/cpp/llama.cpp

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

# ARM64 optimizations for Unisoc T610 (Cortex-A75 + Cortex-A55)
set(LLAMA_NATIVE OFF)
set(LLAMA_BUILD_TESTS OFF)
set(LLAMA_BUILD_EXAMPLES OFF)
set(LLAMA_BUILD_SERVER OFF)

# Enable NEON SIMD (ARM64 always has NEON)
add_compile_options(-march=armv8-a+dotprod -O3 -ffast-math)

# Add llama.cpp as subdirectory
add_subdirectory(${LLAMA_DIR} llama_build)

# ─── Our JNI bridge ──────────────────────────────────────────────────────────
add_library(llama SHARED llama_jni.cpp)

target_include_directories(llama PRIVATE
    ${LLAMA_DIR}
    ${LLAMA_DIR}/common
)

target_link_libraries(llama
    llama_static      # llama.cpp static lib
    common            # llama.cpp common utils
    android
    log
)
