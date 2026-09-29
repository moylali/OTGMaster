#!/bin/bash
# Release what a failed preparation run left behind.
#
# Usage: sudo ./scripts/cleanup_and_parallel.sh
#
# Unmounts and removes any /tmp/mnt_* directory, then closes every
# /dev/mapper/luks_test_* mapping. Both are the naming conventions the scripts in
# this set use for their temporary mounts and mappers.
#
# Needed because those scripts run under `set -e` and take no EXIT trap, so a
# failure part-way leaves a mounted filesystem and an open LUKS mapping holding
# the device. The next run then fails at wipefs with "device is busy", which
# looks like a hardware fault and is not. Run this first when a preparation
# refuses to start.
#
# Safe to run when nothing is stuck: every step is tolerant of finding nothing,
# and it only ever touches paths matching those two patterns.
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
# Cleanup any orphaned mounts and luks mappings
for mnt in /tmp/mnt_*; do
    if mountpoint -q "$mnt"; then
        umount "$mnt" || true
    fi
    rm -rf "$mnt"
done

for map in $(ls /dev/mapper/luks_test_* 2>/dev/null); do
    cryptsetup luksClose "$map" || true
done
