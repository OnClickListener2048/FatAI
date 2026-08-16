cmake_minimum_required(VERSION 3.22.1)
project(needle_jni C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# libneedle.a is the needle2 engine static library (android-arm64), fetched from
# Cactus-Compute/needle2 on HuggingFace at build time and checked into the repo.
set(NEEDLE_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/libs/android-arm64)

add_library(needle STATIC IMPORTED)
set_target_properties(needle PROPERTIES IMPORTED_LOCATION ${NEEDLE_LIB_DIR}/libneedle.a)

add_library(needle_jni SHARED needle_jni.cpp)
target_include_directories(needle_jni PRIVATE ${NEEDLE_LIB_DIR})
# The engine is C++ (libc++ ABI); c++_static + exceptions come from ANDROID_STL.
target_link_libraries(needle_jni needle log)
