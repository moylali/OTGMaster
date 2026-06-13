cmake_minimum_required(VERSION 3.22.1)
project("otgmaster")

# Configure mbedtls
set(ENABLE_TESTING OFF CACHE BOOL "Disable mbedtls tests" FORCE)
set(ENABLE_PROGRAMS OFF CACHE BOOL "Disable mbedtls programs" FORCE)
add_subdirectory(mbedtls)

add_library(veracrypt-native SHARED VeraCryptNative.cpp)

target_link_options(veracrypt-native PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(veracrypt-native
        mbedcrypto
        log)
