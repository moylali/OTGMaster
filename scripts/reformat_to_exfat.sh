#!/bin/bash
# Swap the inner filesystem of an existing LUKS partition to exFAT, in place.
#
# Usage: sudo ./scripts/reformat_to_exfat.sh
#
# Opens the LUKS container, runs mkfs.exfat inside it, repopulates the BENCH/
# tree and closes up. The container, its header and its keyslots are untouched,
# so the volume keeps the identity the app has already been tested against.
#
# Why it exists: exFAT-inside-LUKS was added to the matrix after the drives had
# been built, and rebuilding a prepared drive to change one inner filesystem
# throws away everything else on it for no reason.
#
# CAUTION: the target partitions are hardcoded at the bottom of this file
# (/dev/sdb4 and /dev/sdc4 as written). Device letters move between boots and
# between machines. Check them against lsblk before every run — this script has
# no confirmation prompt and no device-identity check, so a stale letter
# reformats whatever now answers to it.
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
PASSWORD="password123"

populate_data() {
    local MNT=$1
    echo "Populating $MNT with test data..."
    local BENCH="$MNT/BENCH"
    mkdir -p "$BENCH/large" "$BENCH/dense_short" "$BENCH/dense_lfn" "$BENCH/nested"
    
    dd if=/dev/urandom of="$BENCH/large/seq_64m.bin" bs=1048576 count=64 2>/dev/null
    dd if=/dev/urandom of="$BENCH/large/seq_16m.bin" bs=1048576 count=16 2>/dev/null
    
    local LINE="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde"
    local BLOCK=""
    for i in {1..64}; do BLOCK="${BLOCK}${LINE}"; done
    for i in {1..50}; do
        echo -n "$BLOCK" > "$(printf '%s/dense_short/%05d.dat' "$BENCH" "$i")"
        echo -n "$BLOCK" > "$(printf '%s/dense_lfn/densefile_%05d_padding_for_lfn_entries.dat' "$BENCH" "$i")"
    done
    
    local DEEP="$BENCH/nested"
    for i in {1..10}; do
        DEEP="$DEEP/level_$(printf '%02d' $i)"
        mkdir -p "$DEEP"
        echo -n "$BLOCK" > "$DEEP/marker.dat"
    done
    echo -n "$BLOCK" > "$DEEP/leaf_at_depth_10.dat"
    sync
}

reformat_luks_inner() {
    local part=$1
    echo "Reformatting inner FS of $part to exfat..."
    local map_name="luks_exfat_$$"
    echo -n "$PASSWORD" | cryptsetup luksOpen "$part" "$map_name" -
    mkfs.exfat "/dev/mapper/$map_name"
    local mnt="/tmp/mnt_exfat_$$"
    mkdir -p "$mnt"
    mount "/dev/mapper/$map_name" "$mnt"
    populate_data "$mnt"
    umount "$mnt"
    rm -rf "$mnt"
    cryptsetup luksClose "$map_name"
}

reformat_luks_inner "/dev/sdb4"
reformat_luks_inner "/dev/sdc4"
echo "Reformatted /dev/sdb4 and /dev/sdc4 inner filesystems to exfat!"
