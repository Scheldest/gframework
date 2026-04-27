cmake_minimum_required(VERSION 3.22.1)

set(CMAKE_TRY_COMPILE_TARGET_TYPE "STATIC_LIBRARY")

project("fps-native")

add_library(
    fps-native
    SHARED
    native-lib.cpp
)

target_link_libraries(fps-native android log)