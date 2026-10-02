#!/usr/bin/env bash
# Builds the app's filesystem and BitLocker native code — libntfs-3g with its JNI
# bridge, and bitlocker_crypto over mbedtls with its JNI bridge — for the host JVM,
# so the unit tests drive the same native code the app ships against loopback
# images. Output: $1 (default app/build/host-native/libotg-host.so).
#
# Rebuilds only when a source is newer than the output.
set -euo pipefail
cd "$(dirname "$0")/.."
out="${1:-app/build/host-native/libotg-host.so}"
cpp=app/src/main/cpp
srcs=("$cpp"/ntfs-3g/libntfs-3g/*.c "$cpp"/ntfs/NtfsNative.cpp
      "$cpp"/bitlocker/*.c "$cpp"/bitlocker/*.cpp "$cpp"/mbedtls/library/*.c)

if [[ -f "$out" ]] && [[ -z "$(find "${srcs[@]}" "$cpp"/ntfs/config.h "$cpp"/ntfs-3g/include "$cpp"/bitlocker app/src/test/cpp -newer "$out" -print -quit)" ]]; then
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
mbed=(-I"$cpp/mbedtls/include" -I"$cpp/bitlocker")
for f in "$cpp"/ntfs-3g/libntfs-3g/*.c; do
    o="$obj/ntfs_$(basename "$f" .c).o"
    gcc -c "${flags[@]}" "$f" -o "$o" & pids+=($!)
    objs+=("$o")
done
for f in "$cpp"/mbedtls/library/*.c "$cpp"/bitlocker/*.c; do
    o="$obj/mbed_$(basename "$f" .c).o"
    gcc -c -fPIC -O2 -w "${mbed[@]}" "$f" -o "$o" & pids+=($!)
    objs+=("$o")
done
g++ -c -std=c++17 "${flags[@]}" "$cpp/ntfs/NtfsNative.cpp" -o "$obj/NtfsNative.o" & pids+=($!)
g++ -c -std=c++17 "${flags[@]}" "${mbed[@]}" "$cpp/bitlocker/BitLockerNative.cpp" -o "$obj/BitLockerNative.o" & pids+=($!)
objs+=("$obj/NtfsNative.o" "$obj/BitLockerNative.o")
for p in "${pids[@]}"; do wait "$p"; done
g++ -shared -o "$out" "${objs[@]}"
echo "built $out"
