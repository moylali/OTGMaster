cmake_minimum_required(VERSION 3.22.1)
project("otgmaster")

# Reproducible builds: canonicalize all host-absolute paths in compiled output.
# Without this, absolute source paths end up in DWARF debug info, which is used
# to compute .note.gnu.build-id. That hash survives llvm-strip and ends up in
# the packaged APK, so builds at /home/fayaz vs /home/vagrant produce different
# bytes even though the stripped .so has no DWARF sections. -ffile-prefix-map
# remaps both -fdebug-prefix-map and -fmacro-prefix-map, covering __FILE__ too.
add_compile_options(
    -ffile-prefix-map=${CMAKE_SOURCE_DIR}=.
    -ffile-prefix-map=${CMAKE_BINARY_DIR}=.
    -ffile-prefix-map=${ANDROID_NDK}=ndk
)

# Configure mbedtls
set(ENABLE_TESTING OFF CACHE BOOL "Disable mbedtls tests" FORCE)
set(ENABLE_PROGRAMS OFF CACHE BOOL "Disable mbedtls programs" FORCE)
add_subdirectory(mbedtls)

# libexfat
file(GLOB EXFAT_SRCS "exfat/*.c")
add_library(exfat STATIC ${EXFAT_SRCS})
target_compile_definitions(exfat PRIVATE
    _FILE_OFFSET_BITS=64
    PACKAGE="exfat"
    VERSION="1.4.0"
)
target_include_directories(exfat PUBLIC exfat)

# serpent (vendored public-domain reference implementation, see serpent/PROVENANCE.md)
add_library(serpent STATIC
        serpent/serpent-reference.c
        serpent/serpent-aux.c
        serpent/serpent_adapter.c
        serpent/xts_generic.c)
target_include_directories(serpent PUBLIC serpent)

# veracrypt-native
add_library(veracrypt-native SHARED VeraCryptNative.cpp exfat/ExFatNative.cpp)
target_compile_definitions(veracrypt-native PRIVATE _FILE_OFFSET_BITS=64)

target_link_options(veracrypt-native PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(veracrypt-native
        mbedcrypto
        exfat
        serpent
        log)
