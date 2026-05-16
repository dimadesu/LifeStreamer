cmake_minimum_required(VERSION 3.22.1)

project("srtla_android")

find_library(log-lib log)

# Path to SRTLA C sources in the bond-bunny submodule
set(SRTLA_DIR ${CMAKE_SOURCE_DIR}/../../../../bond-bunny/srtla)

add_library(srtla_android SHARED
    srtla_jni.cpp              # LifeStreamer JNI wrapper (com.dimadesu.lifestreamer.srtla)
    ${SRTLA_DIR}/srtla_send.c  # Original SRTLA sender
    ${SRTLA_DIR}/common.c      # SRTLA common functions
)

target_compile_definitions(srtla_android PRIVATE
    ANDROID=1
    VERSION="lifestreamer"
)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wall")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++17")

# 16KB page size alignment for Android 15+ compatibility
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

target_include_directories(srtla_android PUBLIC ${SRTLA_DIR})

target_link_libraries(srtla_android ${log-lib})
