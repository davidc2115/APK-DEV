cmake_minimum_required(VERSION 3.22.1)
project(jarvis_sd)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── Options de build de stable-diffusion.cpp : CPU seul, strict minimum ────
# (aucun GPU mobile fiable en CI ; Vulkan/OpenCL nécessitent une config NDK
# manuelle trop fragile pour un pipeline automatisé — voir docs/build.md)
set(SD_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(SD_WEBP             OFF CACHE BOOL "" FORCE)
set(SD_WEBM             OFF CACHE BOOL "" FORCE)
set(SD_CUDA             OFF CACHE BOOL "" FORCE)
set(SD_HIPBLAS          OFF CACHE BOOL "" FORCE)
set(SD_METAL            OFF CACHE BOOL "" FORCE)
set(SD_VULKAN           OFF CACHE BOOL "" FORCE)
set(SD_OPENCL           OFF CACHE BOOL "" FORCE)
set(SD_SYCL             OFF CACHE BOOL "" FORCE)
set(SD_MUSA             OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE         OFF CACHE BOOL "" FORCE)

# Le code source de stable-diffusion.cpp (avec ses sous-modules ggml/webp/webm)
# est cloné par le workflow GitHub Actions directement dans ce dossier avant
# la compilation. Module Gradle volontairement séparé de celui de llama.cpp :
# les deux projets embarquent chacun leur propre copie de ggml, qui
# entreraient en conflit de nom de cible CMake s'ils étaient compilés dans
# le même arbre de configuration.
add_subdirectory(stable-diffusion.cpp)

add_library(jarvis_sd SHARED jarvis_sd_jni.cpp)

target_include_directories(jarvis_sd PRIVATE
    stable-diffusion.cpp/include
    stable-diffusion.cpp/ggml/include
)

target_link_libraries(jarvis_sd
    stable-diffusion
    ggml
    android
    log
)
