# SPDX-FileCopyrightText: 2015 - 2024 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

cmake_minimum_required(VERSION 3.18.0)

project(trime VERSION 3.0.0)

# for reproducible build
add_link_options("LINKER:--hash-style=both,--build-id=none")

set(CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake" ${CMAKE_MODULE_PATH})

# general options
option(BUILD_SHARED_LIBS "" OFF)
option(BUILD_TESTING "" OFF)
option(TRIME_OPTIMIZED_DEBUG "Optimize native code in debuggable Android builds" OFF)

if(TRIME_OPTIMIZED_DEBUG AND CMAKE_BUILD_TYPE STREQUAL "Debug")
  # Android's debug variant otherwise builds librime with -O0 and verbose DLOGs.
  # Keep Java/Kotlin debuggability but make schema deployment match release builds.
  add_compile_options(-O3 -DNDEBUG)
endif()

include(Boost)

option(WITH_GFLAGS "Use gflags" OFF)
option(WITH_GTEST "Use googletest" OFF)
option(WITH_UNWIND "Enable libunwind support" OFF)
add_subdirectory(librime/deps/glog)
target_compile_options(
  glog_internal PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")

option(YAML_CPP_BUILD_TOOLS "" OFF)
option(YAML_CPP_BUILD_TESTS "" OFF)
option(YAML_CPP_INSTALL "" OFF)
add_subdirectory(librime/deps/yaml-cpp)

# enable compress for leveldb
option(SNAPPY_BUILD_TESTS "" OFF)
option(SNAPPY_BUILD_BENCHMARKS "" OFF)
option(SNAPPY_INSTALL "" OFF)
add_subdirectory(snappy)

set(HAVE_SNAPPY TRUE)
option(LEVELDB_BUILD_TESTS "" OFF)
option(LEVELDB_BUILD_BENCHMARKS "" OFF)
option(LEVELDB_INSTALL "" OFF)
add_subdirectory(librime/deps/leveldb)

add_subdirectory(librime/deps/marisa-trie)
target_compile_options(
  marisa PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")

set(LIBMARISA marisa)
option(USE_SYSTEM_MARISA "" ON)
add_subdirectory(OpenCC)
include(OpenccWorkarounds)

set(X11Keysym "${CMAKE_SOURCE_DIR}/librime/include")
include_directories("${X11Keysym}")

include(Rime)

add_subdirectory(librime_jni)
