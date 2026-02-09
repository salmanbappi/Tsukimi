cmake_minimum_required(VERSION 3.10.2)

project("kotatsu_upscaler")

# Define libraries and include directories for NCNN
# NCNN Android distribution has per-ABI folders
set(ncnn_DIR ${CMAKE_SOURCE_DIR}/ncnn/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

# Link Vulkan and Android libs
find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics) # Needed for Bitmap manipulation

add_library(
        kotatsu_upscaler
        SHARED
        kotatsu_upscaler.cpp
)

target_link_libraries(
        kotatsu_upscaler
        ncnn
        ${log-lib}
        ${android-lib}
        ${jnigraphics-lib}
)
