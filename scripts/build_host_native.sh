#!/usr/bin/env bash
# Builds the app's native code — libntfs-3g, libfsapfs, bitlocker_crypto and the VeraCrypt/LUKS
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
srcs=("$cpp"/ntfs-3g/libntfs-3g/*.c "$cpp"/ntfs/NtfsNative.cpp
      "$cpp"/bitlocker/*.c "$cpp"/bitlocker/*.cpp "$cpp"/mbedtls/library/*.c
      "$cpp"/VeraCryptNative.cpp "$cpp"/serpent/*.c "$cpp"/apfs/ApfsNative.cpp)
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
mbed=(-I"$cpp/mbedtls/include" -I"$cpp/bitlocker" -I"$cpp/serpent")
for f in "$cpp"/ntfs-3g/libntfs-3g/*.c; do
    o="$obj/ntfs_$(basename "$f" .c).o"
    gcc -c "${flags[@]}" "$f" -o "$o" & pids+=($!)
    objs+=("$o")
done
for f in "$cpp"/mbedtls/library/*.c "$cpp"/bitlocker/*.c "$cpp"/serpent/*.c; do
    o="$obj/mbed_$(basename "$f" .c).o"
    gcc -c -fPIC -O2 -w "${mbed[@]}" "$f" -o "$o" & pids+=($!)
    objs+=("$o")
done
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
g++ -c -std=c++17 "${flags[@]}" "$cpp/ntfs/NtfsNative.cpp" -o "$obj/NtfsNative.o" & pids+=($!)
g++ -c -std=c++17 "${flags[@]}" "${mbed[@]}" "$cpp/bitlocker/BitLockerNative.cpp" -o "$obj/BitLockerNative.o" & pids+=($!)
g++ -c -std=c++17 "${flags[@]}" "${mbed[@]}" "$cpp/VeraCryptNative.cpp" -o "$obj/VeraCryptNative.o" & pids+=($!)
objs+=("$obj/NtfsNative.o" "$obj/BitLockerNative.o" "$obj/VeraCryptNative.o")
for p in "${pids[@]}"; do wait "$p"; done
g++ -shared -o "$out" "${objs[@]}"
echo "built $out"
