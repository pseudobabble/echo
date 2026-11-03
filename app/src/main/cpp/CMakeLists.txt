cmake_minimum_required(VERSION 3.22.1)
project(echo_whisper)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)

add_subdirectory(whisper.cpp)

add_library(echo_whisper SHARED echo_whisper.cpp)
target_include_directories(echo_whisper PRIVATE whisper.cpp/include)

find_library(log-lib log)
target_link_libraries(echo_whisper PRIVATE whisper ${log-lib})
