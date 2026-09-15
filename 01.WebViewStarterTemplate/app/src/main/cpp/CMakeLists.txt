cmake_minimum_required(VERSION 3.22.1)
project(webviewtemplate_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

add_library(webviewtemplate_native SHARED AudioEngine.cpp)

target_compile_options(webviewtemplate_native PRIVATE
    -O2
    -ffast-math
    -DANDROID
    -Wall
    -Wno-unused-parameter
)

target_link_libraries(webviewtemplate_native
    oboe::oboe
    log
    android
    OpenSLES
)
