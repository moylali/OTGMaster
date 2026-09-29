#!/bin/bash
# Build the whole small-media LUKS matrix as loopback images, from nothing.
#
# Usage: sudo ./scripts/generate_luks_testcases.sh     (from the workspace root)
#
# Calls prepare_luks_usb.sh three times and leaves the results in testdata/luks/:
#
#   usb1_mixed.img   exFAT + FAT32 + LUKS1 + LUKS2 on one disk
#   usb2_luks1.img   LUKS1 over ext2, ext3, ext4, FAT32
#   usb3_luks2.img   LUKS2 over the same four
#
# This is the entry point to reach for when adding a container format or a
# filesystem: it rebuilds every combination in seconds with no drive attached,
# where the same coverage across physical drives would take hours and more
# hardware than there is.
#
# PART OF THE SMALL-MEDIA LUKS FIXTURE SET
#
# Two families of test media exist in this project, for different questions:
#
#   scripts/prepare_drive_{a,b,c,d}.sh   64 GB physical drives, 2 GiB fixtures,
#                                        hash manifests. Answer "how fast, and
#                                        does it stay correct under load".
#   this set                             ~200 MB loopback images or small
#                                        partitions, tiny fixtures. Answer "does
#                                        the app parse and handle this format".
#
# The small set covers far more *combinations* than the big drives do — LUKS1 and
# LUKS2 over ext2, ext3, ext4 and FAT32, plus a mixed-container disk — and
# regenerates in seconds instead of an hour, which is what makes it the one to
# reach for when adding a container format or a filesystem. The big drives cannot
# cover that matrix; there are only four of them and each takes an hour to build.
#
# What this set deliberately does NOT do:
#   - no MANIFEST.txt, so the benchmark's `fixtures` section has nothing to check
#     against and will report "nothing to check". These images prove a volume is
#     *handled*, not that its bytes survived a write. Use a big drive plus
#     scripts/regen_manifest.sh for that.
#   - no throughput meaning. 50 MB partitions and 64 MB fixtures sit inside any
#     cache; numbers measured here are not comparable to anything in
#     docs/BENCHMARK_RESULTS.md and must not be quoted as figures.
#
# See docs/TEST_DATA.md for the per-case contract these images are consumed under.

set -e

if [[ $EUID -ne 0 ]]; then
   echo "This script must be run as root (sudo ./scripts/generate_luks_testcases.sh)" 
   exit 1
fi

mkdir -p testdata/luks

echo "Generating USB 1 (4 partitions - exfat, fat32, luks1, luks2)..."
./scripts/prepare_luks_usb.sh --image testdata/luks/usb1_mixed.img --layout 1

echo "Generating USB 2 (4 partitions luks1 -> ext2, ext3, ext4, fat32)..."
./scripts/prepare_luks_usb.sh --image testdata/luks/usb2_luks1.img --layout 2

echo "Generating USB 3 (4 partitions luks2 -> ext2, ext3, ext4, fat32)..."
./scripts/prepare_luks_usb.sh --image testdata/luks/usb3_luks2.img --layout 3

echo "All images generated successfully in testdata/luks/"
