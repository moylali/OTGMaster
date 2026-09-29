#!/bin/bash
# Prepares physical USBs in parallel

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

format_and_populate() {
    local DEV=$1
    local LAYOUT=$2
    
    echo "Preparing $DEV with layout $LAYOUT..."
    wipefs -a "$DEV"
    parted -s "$DEV" mklabel msdos
    
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
        echo -n "$PASSWORD" | cryptsetup luksFormat -q --type "$luks_type" "$part" -
        echo -n "$PASSWORD" | cryptsetup luksOpen "$part" "$map_name" -
        
        local target="/dev/mapper/$map_name"
        case "$inner_fs" in
            ext2) mkfs.ext2 -F -q "$target" ;;
            ext3) mkfs.ext3 -F -q "$target" ;;
            ext4) mkfs.ext4 -F -q "$target" ;;
            fat32) mkfs.fat -F 32 "$target" ;;
            exfat) mkfs.exfat "$target" ;;
        esac
        
        local MNT="/tmp/mnt_$$_${RANDOM}"
        mkdir -p "$MNT"
        mount "$target" "$MNT"
        populate_data "$MNT"
        umount "$MNT"
        rm -rf "$MNT"
        
        cryptsetup luksClose "$map_name"
    }

    case "$LAYOUT" in
        2)
            format_luks "$PART1" luks1 ext2
            format_luks "$PART2" luks1 ext3
            format_luks "$PART3" luks1 ext4
            format_luks "$PART4" luks1 exfat
            ;;
        3)
            format_luks "$PART1" luks2 ext2
            format_luks "$PART2" luks2 ext3
            format_luks "$PART3" luks2 ext4
            format_luks "$PART4" luks2 exfat
            ;;
    esac
    
    echo "$DEV is fully prepared."
}

# Run in parallel
format_and_populate "/dev/sdb" 2 &
PID_SDB=$!

format_and_populate "/dev/sdc" 3 &
PID_SDC=$!

wait $PID_SDB
wait $PID_SDC

echo "Parallel formatting complete!"
