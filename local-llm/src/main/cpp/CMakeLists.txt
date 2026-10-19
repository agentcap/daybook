cmake_minimum_required(VERSION 3.22.1)
project(daybook_llm LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
foreach(option LLAMA_BUILD_COMMON LLAMA_BUILD_TESTS LLAMA_BUILD_TOOLS LLAMA_BUILD_EXAMPLES LLAMA_BUILD_SERVER LLAMA_BUILD_APP LLAMA_OPENSSL LLAMA_SUBPROCESS GGML_OPENMP GGML_NATIVE GGML_CPU_ALL_VARIANTS GGML_BACKEND_DL GGML_LLAMAFILE)
    set(${option} OFF CACHE BOOL "" FORCE)
endforeach()
# The x86 build is for the development emulator. Guard these features in JNI
# before initialization; the ARM64 phone build retains its portable baseline.
if (ANDROID_ABI STREQUAL "x86_64")
    foreach(feature GGML_AVX GGML_AVX2 GGML_FMA GGML_F16C)
        set(${feature} ON CACHE BOOL "" FORCE)
    endforeach()
endif()
add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/../../../../vendor/llama.cpp llama)
add_library(daybook_llm SHARED local_llm.cpp)
target_link_libraries(daybook_llm PRIVATE llama android log)
# whisper.cpp has its own ggml revision. Export JNI only, so neither runtime can
# accidentally resolve the other runtime's incompatible ggml/C++ symbols.
target_link_options(daybook_llm PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,--version-script=${CMAKE_CURRENT_LIST_DIR}/exports.map" "-Wl,-Bsymbolic" "-Wl,-z,max-page-size=16384")
