cmake_minimum_required(VERSION 3.22.1)
project("localaudiototext")

set(CMAKE_CXX_STANDARD 11)

# Disable tests and examples from whisper.cpp
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "")
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "")
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "")

add_subdirectory(whisper)

add_library(
        whisper_jni
        SHARED
        whisper_jni.cpp
)

find_library(
        log-lib
        log
)

target_link_libraries(
        whisper_jni
        whisper
        ${log-lib}
)
