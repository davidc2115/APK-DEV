cmake_minimum_required(VERSION 3.22.1)
project(jarvis_llama)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── Options de build de llama.cpp : au strict minimum pour Android ──────────
set(LLAMA_BUILD_COMMON  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER  OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL          OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE         OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS   OFF CACHE BOOL "" FORCE)

# Le code source de llama.cpp est cloné par le workflow GitHub Actions
# directement dans ce dossier avant la compilation (voir build.yml).
add_subdirectory(llama.cpp)

add_library(jarvis_llama SHARED jarvis_llama_jni.cpp)

target_include_directories(jarvis_llama PRIVATE
    llama.cpp/include
    llama.cpp/ggml/include
)

target_link_libraries(jarvis_llama
    llama
    ggml
    android
    log
)
