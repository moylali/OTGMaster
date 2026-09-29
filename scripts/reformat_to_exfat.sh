#!/bin/bash
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
