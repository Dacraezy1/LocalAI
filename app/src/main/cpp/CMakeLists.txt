cmake_minimum_required(VERSION 3.22.1)
project(localai)

set(CMAKE_CXX_STANDARD 17)

# ─── llama.cpp source ────────────────────────────────────────────────────────
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

# Build flags for llama.cpp
set(LLAMA_NATIVE OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# ARM64 optimizations for Unisoc T610
add_compile_options(-march=armv8-a+dotprod -O3 -ffast-math)

# Add llama.cpp as subdirectory - it defines targets: llama, common, ggml
add_subdirectory(${LLAMA_DIR} llama_build)

# ─── Our JNI bridge ──────────────────────────────────────────────────────────
# IMPORTANT: name must NOT be "llama" - that's already taken by llama.cpp
add_library(localai_jni SHARED llama_jni.cpp)

target_include_directories(localai_jni PRIVATE
    ${LLAMA_DIR}
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include
)

target_link_libraries(localai_jni
    llama          # llama.cpp shared/static lib (defined by llama.cpp's CMake)
    android
    log
)
