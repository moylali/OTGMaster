#!/bin/bash
# Prepares 3 physical USBs and fills them with test data.

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
