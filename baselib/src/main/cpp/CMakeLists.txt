cmake_minimum_required(VERSION 3.22.1)
project(omnibot_llama_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(FetchContent)

# Pin llama.cpp to a known-good upstream commit for reproducible Android builds.
set(LLAMA_CPP_COMMIT "192067b72d1b7a3653b3f0c59190303b18596637" CACHE STRING "Pinned llama.cpp commit")

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    llama_cpp
    URL "https://github.com/ggml-org/llama.cpp/archive/${LLAMA_CPP_COMMIT}.zip"
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)

FetchContent_MakeAvailable(llama_cpp)

add_library(omnibot_llama_jni SHARED
    omnibot_llama_jni.cpp
)

target_include_directories(omnibot_llama_jni PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/ggml/include
)

target_link_libraries(omnibot_llama_jni PRIVATE
    llama
    android
    log
)

target_compile_definitions(omnibot_llama_jni PRIVATE
    OMNIBOT_LLAMA_JNI=1
)

# Keep the JNI bridge small and let llama.cpp own inference internals.
target_compile_options(omnibot_llama_jni PRIVATE
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
)

if (ANDROID)
    set_target_properties(omnibot_llama_jni PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN ON
    )
endif()
