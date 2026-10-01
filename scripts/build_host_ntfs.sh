#!/usr/bin/env bash
# Builds libntfs-3g and the NtfsNative JNI bridge for the host JVM, so the unit
# tests in app/src/test/.../ntfs drive the same native code the app ships
# against loopback images. Output: $1 (default app/build/host-ntfs/libntfs-host.so).
#
# Rebuilds only when a source is newer than the output.
set -euo pipefail
cd "$(dirname "$0")/.."
out="${1:-app/build/host-ntfs/libntfs-host.so}"
cpp=app/src/main/cpp
srcs=("$cpp"/ntfs-3g/libntfs-3g/*.c "$cpp"/ntfs/NtfsNative.cpp)

if [[ -f "$out" ]] && [[ -z "$(find "${srcs[@]}" "$cpp"/ntfs/config.h "$cpp"/ntfs-3g/include app/src/test/cpp -newer "$out" -print -quit)" ]]; then
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
for f in "$cpp"/ntfs-3g/libntfs-3g/*.c; do
    o="$obj/$(basename "$f" .c).o"
    gcc -c "${flags[@]}" "$f" -o "$o" & pids+=($!)
    objs+=("$o")
done
g++ -c -std=c++17 "${flags[@]}" "$cpp/ntfs/NtfsNative.cpp" -o "$obj/NtfsNative.o" & pids+=($!)
for p in "${pids[@]}"; do wait "$p"; done
g++ -shared -o "$out" "${objs[@]}" "$obj/NtfsNative.o"
echo "built $out"
