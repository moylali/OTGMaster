#!/usr/bin/env bash
# Builds the two APFS tools the host checks use, without root, into matrix/tools/:
#
#   apfsck     linux-apfs/apfsprogs — structural check of a plain APFS container
#   apfs-fuse  sgan81/apfs-fuse    — mounts APFS as this user, decrypting with -r
#
# Neither is packaged for Ubuntu. apfs-fuse needs libfuse3-dev (sudo apt install
# libfuse3-dev) and a cmake; the Android SDK's cmake 3.22.1 is used if there is no
# system one. Prints the two export lines verify_matrix_drive.py and
# verify_apfs_images.py read.
#
#   bash scripts/build_apfs_tools.sh
#   export APFSCK=... APFS_FUSE=...     # as printed
set -euo pipefail
cd "$(dirname "$0")/.."
T=matrix/tools
mkdir -p "$T"

if [ ! -x "$T/apfsprogs/apfsck/apfsck" ]; then
    [ -d "$T/apfsprogs" ] || git clone -q --depth 1 https://github.com/linux-apfs/apfsprogs.git "$T/apfsprogs"
    make -s -C "$T/apfsprogs/apfsck" >/dev/null
fi

if [ ! -x "$T/apfs-fuse/build/apfs-fuse" ]; then
    [ -d "$T/apfs-fuse" ] || git clone -q --recursive https://github.com/sgan81/apfs-fuse.git "$T/apfs-fuse"
    pkg-config --exists fuse3 || { echo "libfuse3-dev is missing: sudo apt install libfuse3-dev"; exit 1; }
    CMAKE=$(command -v cmake || ls -d "${ANDROID_HOME:-$HOME/android-sdk-local}"/cmake/*/bin/cmake 2>/dev/null | tail -1)
    [ -n "$CMAKE" ] || { echo "no cmake found (system or Android SDK)"; exit 1; }
    NINJA=$(command -v ninja || echo "$(dirname "$CMAKE")/ninja")
    mkdir -p "$T/apfs-fuse/build"
    # apfs-fuse predates GCC 13's leaner headers: uint8_t and friends need <cstdint>.
    (cd "$T/apfs-fuse/build" && CXXFLAGS="-include cstdint" "$CMAKE" .. -G Ninja \
        -DCMAKE_MAKE_PROGRAM="$NINJA" -DUSE_FUSE3=ON -DCMAKE_BUILD_TYPE=Release >/dev/null &&
        "$NINJA" apfs-fuse >/dev/null)
fi

echo "export APFSCK=$PWD/$T/apfsprogs/apfsck/apfsck APFS_FUSE=$PWD/$T/apfs-fuse/build/apfs-fuse"
