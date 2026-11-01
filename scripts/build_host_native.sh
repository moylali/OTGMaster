#!/usr/bin/env bash
# Builds the app's native code — libntfs-3g, libexfat, libfsapfs, bitlocker_crypto and the VeraCrypt/LUKS
# sector crypto (VeraCryptNative, Serpent) over mbedtls, each with its JNI bridge —
# for the host JVM,
# so the unit tests drive the same native code the app ships against loopback
# images. Output: $1 (default app/build/host-native/libotg-host.so).
#
# Rebuilds only when a source is newer than the output.
set -euo pipefail
cd "$(dirname "$0")/.."
out="${1:-app/build/host-native/libotg-host.so}"
cpp=app/src/main/cpp
srcs=("$cpp"/ntfs-3g/libntfs-3g/*.c "$cpp"/ntfs-3g/ntfsprogs/*.c "$cpp"/ntfs-3g/ntfsprogs/*.h "$cpp"/ntfs/NtfsNative.cpp "$cpp"/exfat/*.c "$cpp"/exfat/*.h
      "$cpp"/exfat/ExFatNative.cpp
      "$cpp"/bitlocker/*.c "$cpp"/bitlocker/*.cpp "$cpp"/mbedtls/library/*.c
      "$cpp"/VeraCryptNative.cpp "$cpp"/vc_cascade.cpp "$cpp"/vc_cascade.h "$cpp"/serpent/*.c
      "$cpp"/libtomcrypt/src/ciphers/twofish/*.c "$cpp"/libtomcrypt/src/misc/*.c
      "$cpp"/libtomcrypt/src/misc/crypt/*.c "$cpp"/argon2/src/*.c "$cpp"/argon2/src/blake2/*.c
      "$cpp"/apfs/ApfsNative.cpp "$cpp"/zstd/ZstdNative.cpp "$cpp"/zstd/common/*.c "$cpp"/zstd/decompress/*.c)
apfs_libs=(libfsapfs libbfio libcaes libcdata libcerror libcfile libclocale libcnotify libcpath
           libcsplit libfcache libfdata libfdatetime libfguid libfmos libhmac libuna)
for l in "${apfs_libs[@]}"; do srcs+=("$cpp"/libfsapfs/"$l"/*.c); done

if [[ -f "$out" ]] && [[ -z "$(find "${srcs[@]}" "$cpp"/ntfs/config.h "$cpp"/ntfs-3g/include "$cpp"/bitlocker "$cpp"/libfsapfs app/src/test/cpp -newer "$out" -print -quit)" ]]; then
    echo "$out is up to date"
    exit 0
fi

java_home="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac || command -v java)")")")}"
[[ -f "$java_home/include/jni.h" ]] || { echo "jni.h not found under $java_home" >&2; exit 1; }

obj="$(dirname "$out")/obj"
mkdir -p "$obj"
flags=(-fPIC -O1 -g -DHAVE_CONFIG_H -D_FILE_OFFSET_BITS=64
       -I"$cpp/ntfs" -I"$cpp/ntfs-3g/include/ntfs-3g" -Iapp/src/test/cpp
       -I"$java_home/include" -I"$java_home/include/linux" -w)
objs=()
pids=()
# The LTC_* definitions shape symmetric_key, so the Twofish objects and vc_cascade.cpp
# must be built with the same ones (as app/src/main/cpp/CMakeLists.txt does).
ltc=(-DLTC_NOTHING -DLTC_TWOFISH -DLTC_TWOFISH_TABLES -DLTC_NO_TEST -I"$cpp/libtomcrypt/src/headers")
mbed=(-I"$cpp/mbedtls/include" -I"$cpp/bitlocker" -I"$cpp/serpent" "${ltc[@]}" -I"$cpp/argon2/include")
for f in "$cpp"/ntfs-3g/libntfs-3g/*.c; do
    o="$obj/ntfs_$(basename "$f" .c).o"
    gcc -c "${flags[@]}" "$f" -o "$o" & pids+=($!)
    objs+=("$o")
done
for f in "$cpp"/ntfs-3g/ntfsprogs/*.c; do
    o="$obj/ntfsprogs_$(basename "$f" .c).o"
    gcc -c "${flags[@]}" -DOTG_MKNTFS_LIBRARY -DHAVE_GETOPT_H -DHAVE_LIBGEN_H -I"$cpp/ntfs-3g/ntfsprogs" "$f" -o "$o" & pids+=($!)
    objs+=("$o")
done
for f in "$cpp"/mbedtls/library/*.c "$cpp"/bitlocker/*.c "$cpp"/serpent/*.c \
         "$cpp"/libtomcrypt/src/ciphers/twofish/twofish.c "$cpp"/libtomcrypt/src/misc/*.c \
         "$cpp"/libtomcrypt/src/misc/crypt/*.c; do
    o="$obj/mbed_$(basename "$f" .c).o"
    gcc -c -fPIC -O2 -w "${mbed[@]}" "$f" -o "$o" & pids+=($!)
    objs+=("$o")
done
for f in "$cpp"/argon2/src/*.c "$cpp"/argon2/src/blake2/*.c; do
    o="$obj/argon2_$(basename "$f" .c).o"
    gcc -c -fPIC -O2 -w -DARGON2_NO_THREADS -I"$cpp/argon2/include" "$f" -o "$o" & pids+=($!)
    objs+=("$o")
done
# libexfat with the defines app/src/main/cpp/CMakeLists.txt gives it. io.c holds the
# JNI_OnLoad, as it does in the app's library. Two differences from the NDK's clang:
# the desktop jni.h types AttachCurrentThread's env as void** (gcc 14 makes that an
# error), and g++ has no _Static_assert in C++, which exfat.h uses via compiler.h.
exfat=(-fPIC -O1 -g -w -D_FILE_OFFSET_BITS=64 -DPACKAGE='"exfat"' -DVERSION='"1.4.0"'
       -I"$cpp/exfat" -Iapp/src/test/cpp -I"$java_home/include" -I"$java_home/include/linux")
for f in "$cpp"/exfat/*.c; do
    o="$obj/exfat_$(basename "$f" .c).o"
    gcc -c "${exfat[@]}" -Wno-error=incompatible-pointer-types "$f" -o "$o" & pids+=($!)
    objs+=("$o")
done
g++ -c -std=c++17 "${exfat[@]}" -D_Static_assert=static_assert "$cpp/exfat/ExFatNative.cpp" \
    -o "$obj/ExFatNative.o" & pids+=($!)
objs+=("$obj/ExFatNative.o")
# libfsapfs and its libyal dependencies, with the configure-generated headers in
# common/ and include/ (docs/VENDOR_FIXES.md).
apfs_inc=(-DHAVE_CONFIG_H -I"$cpp/libfsapfs/common" -I"$cpp/libfsapfs/include")
for l in "${apfs_libs[@]}"; do apfs_inc+=(-I"$cpp/libfsapfs/$l"); done
for l in "${apfs_libs[@]}"; do
    for f in "$cpp"/libfsapfs/"$l"/*.c; do
        o="$obj/apfs_${l}_$(basename "$f" .c).o"
        gcc -c -fPIC -O1 -g -w "${apfs_inc[@]}" "$f" -o "$o" & pids+=($!)
        objs+=("$o")
    done
done
g++ -c -std=c++17 -fPIC -O1 -g -w "${apfs_inc[@]}" -Iapp/src/test/cpp \
    -I"$java_home/include" -I"$java_home/include/linux" "$cpp/apfs/ApfsNative.cpp" -o "$obj/ApfsNative.o" & pids+=($!)
objs+=("$obj/ApfsNative.o")
# zstd's decoder, with the definitions CMakeLists.txt gives it.
for f in "$cpp"/zstd/common/*.c "$cpp"/zstd/decompress/*.c; do
    o="$obj/zstd_$(basename "$f" .c).o"
    gcc -c -fPIC -O1 -g -w -DZSTD_DISABLE_ASM=1 -DZSTD_LEGACY_SUPPORT=0 -I"$cpp/zstd" -I"$cpp/zstd/common" "$f" -o "$o" & pids+=($!)
    objs+=("$o")
done
g++ -c -std=c++17 "${flags[@]}" -I"$cpp/zstd" "$cpp/zstd/ZstdNative.cpp" -o "$obj/ZstdNative.o" & pids+=($!)
objs+=("$obj/ZstdNative.o")
g++ -c -std=c++17 "${flags[@]}" "$cpp/ntfs/NtfsNative.cpp" -o "$obj/NtfsNative.o" & pids+=($!)
g++ -c -std=c++17 "${flags[@]}" "${mbed[@]}" "$cpp/bitlocker/BitLockerNative.cpp" -o "$obj/BitLockerNative.o" & pids+=($!)
g++ -c -std=c++17 "${flags[@]}" "${mbed[@]}" "$cpp/VeraCryptNative.cpp" -o "$obj/VeraCryptNative.o" & pids+=($!)
g++ -c -std=c++17 "${flags[@]}" "${mbed[@]}" "$cpp/vc_cascade.cpp" -o "$obj/vc_cascade.o" & pids+=($!)
objs+=("$obj/NtfsNative.o" "$obj/BitLockerNative.o" "$obj/VeraCryptNative.o" "$obj/vc_cascade.o")
for p in "${pids[@]}"; do wait "$p"; done
g++ -shared -o "$out" "${objs[@]}"
echo "built $out"
