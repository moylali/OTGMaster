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

# libexfat I/O instrumentation. Enabled from the debug build type only
# (app/build.gradle.kts). io.c holds the counters and ExFatNative.cpp exports
# them over JNI, so the definition has to reach BOTH targets.
option(OTG_IO_STATS "Compile libexfat pread/pwrite counters (debug only)" OFF)
if(OTG_IO_STATS)
    message(STATUS "OTG: libexfat I/O instrumentation enabled")
endif()

# libexfat
file(GLOB EXFAT_SRCS "exfat/*.c")
add_library(exfat STATIC ${EXFAT_SRCS})
target_compile_definitions(exfat PRIVATE
    _FILE_OFFSET_BITS=64
    PACKAGE="exfat"
    VERSION="1.4.0"
)
target_include_directories(exfat PUBLIC exfat)
if(OTG_IO_STATS)
    target_compile_definitions(exfat PRIVATE OTG_IO_STATS=1)
endif()

# libntfs-3g (vendored, see docs/VENDOR_FIXES.md). Only the library is built:
# no FUSE driver, no ntfsprogs, and no unix device I/O — the device is supplied
# through ntfs_device_operations in ntfs/NtfsNative.cpp. ntfs/config.h stands in
# for the autoconf-generated one.
file(GLOB NTFS3G_SRCS "ntfs-3g/libntfs-3g/*.c")
add_library(ntfs3g STATIC ${NTFS3G_SRCS})
target_compile_definitions(ntfs3g PRIVATE HAVE_CONFIG_H _FILE_OFFSET_BITS=64)
target_include_directories(ntfs3g PUBLIC ntfs ntfs-3g/include/ntfs-3g)
target_compile_options(ntfs3g PRIVATE -Wno-address-of-packed-member -Wno-unused-but-set-variable)

# libfsapfs and the libyal libraries it needs (vendored, see docs/VENDOR_FIXES.md).
# Read-only APFS, plain and natively encrypted. libyal's configure-generated
# headers (common/config.h, common/types.h, include/libfsapfs*.h,
# libfsapfs/libfsapfs_definitions.h) are checked in: config.h was generated
# against the NDK and is ABI-neutral on __LP64__. Built without libyal's
# multi-threading (so no libcthreads); ApfsFileSystem serialises every call.
set(APFS_LIBS libfsapfs libbfio libcaes libcdata libcerror libcfile libclocale libcnotify
        libcpath libcsplit libfcache libfdata libfdatetime libfguid libfmos libhmac libuna)
set(APFS_SRCS "")
set(APFS_INCS libfsapfs/common libfsapfs/include)
foreach(lib ${APFS_LIBS})
    file(GLOB lib_srcs "libfsapfs/${lib}/*.c")
    list(APPEND APFS_SRCS ${lib_srcs})
    list(APPEND APFS_INCS libfsapfs/${lib})
endforeach()
# The include paths stay PRIVATE: libyal's common.h and types.h share their names
# with headers in mbedtls and ntfs-3g, so they must not reach the other bridges.
# The JNI bridge is an object library for the same reason.
add_library(fsapfs STATIC ${APFS_SRCS})
target_compile_definitions(fsapfs PRIVATE HAVE_CONFIG_H)
target_include_directories(fsapfs PRIVATE ${APFS_INCS})
target_compile_options(fsapfs PRIVATE -w)
add_library(apfs-jni OBJECT apfs/ApfsNative.cpp)
target_compile_definitions(apfs-jni PRIVATE HAVE_CONFIG_H)
target_include_directories(apfs-jni PRIVATE ${APFS_INCS})

# serpent (vendored public-domain reference implementation, see serpent/PROVENANCE.md)
add_library(serpent STATIC
        serpent/serpent-reference.c
        serpent/serpent-aux.c
        serpent/serpent_adapter.c
        serpent/xts_generic.c)
target_include_directories(serpent PUBLIC serpent)

# veracrypt-native
# BitLocker sector crypto, key stretch and AES-CCM unwrap over mbedtls (see bitlocker/).
add_library(bitlocker STATIC bitlocker/bitlocker_crypto.c)
target_include_directories(bitlocker PUBLIC bitlocker)
target_link_libraries(bitlocker mbedcrypto)

add_library(veracrypt-native SHARED VeraCryptNative.cpp exfat/ExFatNative.cpp ntfs/NtfsNative.cpp
        bitlocker/BitLockerNative.cpp $<TARGET_OBJECTS:apfs-jni>)
target_compile_definitions(veracrypt-native PRIVATE _FILE_OFFSET_BITS=64)
if(OTG_IO_STATS)
    target_compile_definitions(veracrypt-native PRIVATE OTG_IO_STATS=1)
endif()
target_link_options(veracrypt-native PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(veracrypt-native
        mbedcrypto
        exfat
        ntfs3g
        bitlocker
        fsapfs
        serpent
        log)
