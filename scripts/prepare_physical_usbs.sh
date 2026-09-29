#!/bin/bash
# Write the small-media LUKS layouts onto three physical USB sticks, in sequence.
#
# Usage: sudo ./scripts/prepare_physical_usbs.sh
#
# The same three layouts generate_luks_testcases.sh produces as images, but on
# real hardware — which is the point. A loopback image cannot exercise libaums,
# the USB transport, bulk-transfer chunking, or a phone's OTG power budget, so a
# format that parses perfectly from an image can still fail on a stick.
#
# Fills each partition with a small BENCH/ tree: two random files (64 MB, 16 MB),
# 50 short-name and 50 long-name files, and a 10-deep nested directory. Small on
# purpose — this proves the layout is handled, not how fast it is.
#
# Sequential, so it takes as long as three drives take. prepare_parallel.sh does
# the same work concurrently.
#
# DESTRUCTIVE: it repartitions the devices named inside it. Read which ones
# before running.
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

# Setup basic test data structures on a mounted partition
populate_data() {
    local MNT=$1
    echo "Populating $MNT with test data..."
    local BENCH="$MNT/BENCH"
    mkdir -p "$BENCH/large" "$BENCH/dense_short" "$BENCH/dense_lfn" "$BENCH/nested"
    
    # Large files
    dd if=/dev/urandom of="$BENCH/large/seq_64m.bin" bs=1048576 count=64 2>/dev/null
    dd if=/dev/urandom of="$BENCH/large/seq_16m.bin" bs=1048576 count=16 2>/dev/null
    
    # Dense short
    local LINE="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde"
    local BLOCK=""
    for i in {1..64}; do BLOCK="${BLOCK}${LINE}"; done
    for i in {1..50}; do
        echo -n "$BLOCK" > "$(printf '%s/dense_short/%05d.dat' "$BENCH" "$i")"
    done
    
    # Dense lfn
    for i in {1..50}; do
        echo -n "$BLOCK" > "$(printf '%s/dense_lfn/densefile_%05d_padding_for_lfn_entries.dat' "$BENCH" "$i")"
    done
    
    # Nested
    local DEEP="$BENCH/nested"
    for i in {1..10}; do
        DEEP="$DEEP/level_$(printf '%02d' $i)"
        mkdir -p "$DEEP"
        echo -n "$BLOCK" > "$DEEP/marker.dat"
    done
    echo -n "$BLOCK" > "$DEEP/leaf_at_depth_10.dat"
    
    # Sync and permissions
    sync
}

format_and_populate() {
    local DEV=$1
    local LAYOUT=$2
    
    echo "Preparing $DEV with layout $LAYOUT..."
    wipefs -a "$DEV"
    parted -s "$DEV" mklabel msdos
    
    # 4 partitions of equal size, each 10GB for a 57G drive, leaving the rest unallocated to save time
    parted -s "$DEV" mkpart primary 1MiB 2000MiB
    parted -s "$DEV" mkpart primary 2000MiB 4000MiB
    parted -s "$DEV" mkpart primary 4000MiB 6000MiB
    parted -s "$DEV" mkpart primary 6000MiB 8000MiB
    partprobe "$DEV"
    sleep 2
    
    local PART1="${DEV}1"
    local PART2="${DEV}2"
    local PART3="${DEV}3"
    local PART4="${DEV}4"
    
    format_luks() {
        local part=$1
        local luks_type=$2
        local inner_fs=$3
        local map_name="luks_test_$$_${RANDOM}"
        
        echo "Formatting $part as $luks_type..."
        echo -n "$PASSWORD" | cryptsetup luksFormat --type "$luks_type" "$part" -
        echo -n "$PASSWORD" | cryptsetup luksOpen "$part" "$map_name" -
        
        local target="/dev/mapper/$map_name"
        case "$inner_fs" in
            ext2) mkfs.ext2 -F -q "$target" ;;
            ext3) mkfs.ext3 -F -q "$target" ;;
            ext4) mkfs.ext4 -F -q "$target" ;;
            fat32) mkfs.fat -F 32 "$target" ;;
        esac
        
        local MNT="/tmp/mnt_$$_${RANDOM}"
        mkdir -p "$MNT"
        mount "$target" "$MNT"
        populate_data "$MNT"
        umount "$MNT"
        rm -rf "$MNT"
        
        cryptsetup luksClose "$map_name"
    }
    
    format_plain() {
        local part=$1
        local fs_type=$2
        
        case "$fs_type" in
            exfat) mkfs.exfat "$part" ;;
            fat32) mkfs.fat -F 32 "$part" ;;
        esac
        
        local MNT="/tmp/mnt_$$_${RANDOM}"
        mkdir -p "$MNT"
        mount "$part" "$MNT"
        populate_data "$MNT"
        umount "$MNT"
        rm -rf "$MNT"
    }

    case "$LAYOUT" in
        1)
            format_plain "$PART1" exfat
            format_plain "$PART2" fat32
            format_luks "$PART3" luks1 ext4
            format_luks "$PART4" luks2 ext4
            ;;
        2)
            format_luks "$PART1" luks1 ext2
            format_luks "$PART2" luks1 ext3
            format_luks "$PART3" luks1 ext4
            format_luks "$PART4" luks1 fat32
            ;;
        3)
            format_luks "$PART1" luks2 ext2
            format_luks "$PART2" luks2 ext3
            format_luks "$PART3" luks2 ext4
            format_luks "$PART4" luks2 fat32
            ;;
    esac
    
    echo "$DEV is fully prepared."
}

umount /dev/sda1 2>/dev/null || true
umount /dev/sdb1 2>/dev/null || true
umount /dev/sdc1 2>/dev/null || true
cryptsetup close luks_test* 2>/dev/null || true

format_and_populate "/dev/sda" 1
format_and_populate "/dev/sdb" 2
format_and_populate "/dev/sdc" 3

echo "All physical USBs successfully formatted and populated!"
