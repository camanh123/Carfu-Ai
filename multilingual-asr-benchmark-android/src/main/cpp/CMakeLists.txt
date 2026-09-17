cmake_minimum_required(VERSION 3.22)

project(whisper_jni C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Diagnostic APK may be debug-signed; native code is always Release-optimized.
# No host-native / ARMv8.2+fp16 march flags — CARFU T610 must not assume extra ISAs.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_compile_options(-O3 -DNDEBUG -fvisibility=hidden -ffunction-sections -fdata-sections -fPIC)
add_link_options(-Wl,--gc-sections)

set(WHISPER_LIB_DIR "${CMAKE_SOURCE_DIR}/../../../third_party/whisper.cpp")
if (NOT EXISTS "${WHISPER_LIB_DIR}/src/whisper.cpp")
    message(FATAL_ERROR "whisper.cpp missing at ${WHISPER_LIB_DIR}. Run scripts/prepare-deps.sh")
endif()

file(READ "${WHISPER_LIB_DIR}/CMakeLists.txt" MAIN_CMAKE_CONTENT)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)" VERSION_MATCH "${MAIN_CMAKE_CONTENT}")
if (CMAKE_MATCH_1)
    set(WHISPER_VERSION "${CMAKE_MATCH_1}")
else()
    set(WHISPER_VERSION "unknown")
endif()
message(STATUS "whisper.cpp version ${WHISPER_VERSION} from ${WHISPER_LIB_DIR}")

# Static ggml, CPU backend only. Official Android example architecture:
# compile whisper.cpp + JNI into libwhisper.so and link ggml from whisper.cpp/ggml.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "ggml static" FORCE)
set(GGML_STATIC ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_ACCELERATE OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_HIP OFF CACHE BOOL "" FORCE)
set(GGML_SYCL OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(GGML_CPU ON CACHE BOOL "" FORCE)
set(GGML_LTO OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(ggml SOURCE_DIR "${WHISPER_LIB_DIR}/ggml")
FetchContent_MakeAvailable(ggml)

find_library(LOG_LIB log)

add_library(whisper SHARED
    jni.c
    "${WHISPER_LIB_DIR}/src/whisper.cpp"
)

target_compile_definitions(whisper PRIVATE
    GGML_USE_CPU
    WHISPER_VERSION="${WHISPER_VERSION}"
)

target_include_directories(whisper PRIVATE
    "${WHISPER_LIB_DIR}"
    "${WHISPER_LIB_DIR}/src"
    "${WHISPER_LIB_DIR}/include"
    "${WHISPER_LIB_DIR}/ggml/include"
    "${WHISPER_LIB_DIR}/ggml/src"
    "${WHISPER_LIB_DIR}/ggml/src/ggml-cpu"
)

# Official whisper.android links: log, android, ggml
target_link_libraries(whisper
    ${LOG_LIB}
    android
    ggml
)
