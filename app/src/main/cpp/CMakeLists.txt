cmake_minimum_required(VERSION 3.22.1)

project("fps-native")

add_library(
    fps-native
    SHARED
    native-lib.cpp
)

target_link_libraries(fps-native android log)