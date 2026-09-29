#!/bin/bash
# Prepare USBs or disk images for LUKS testcases.
# Usage: sudo ./scripts/prepare_luks_usb.sh --disk /dev/sdX --layout 1
#        sudo ./scripts/prepare_luks_usb.sh --image test.img --layout 2

set -e

DISK=""
LAYOUT=""
IMAGE_MODE=0
PASSWORD="password123"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --disk) DISK="$2"; shift 2 ;;
        --image) DISK="$2"; IMAGE_MODE=1; shift 2 ;;
        --layout) LAYOUT="$2"; shift 2 ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

if [[ -z "$DISK" || -z "$LAYOUT" ]]; then
    echo "Usage: sudo $0 [--disk /dev/sdX | --image file.img] --layout [1|2|3]"
    exit 1
fi

if [[ $EUID -ne 0 ]]; then
   echo "This script must be run as root (sudo)" 
   exit 1
fi

if [[ $IMAGE_MODE -eq 1 ]]; then
    echo "Creating 200MB image file: $DISK"
    dd if=/dev/zero of="$DISK" bs=1M count=200
    DEV=$(losetup --find --show -P "$DISK")
else
    DEV="$DISK"
    if ! lsblk "$DEV" >/dev/null 2>&1; then
        echo "Device $DEV not found!"
        exit 1
    fi
    echo "WARNING: DESTRUCTIVE ACTION ON $DEV"
    read -p "Type uppercase YES to continue: " CONFIRM
    if [[ "$CONFIRM" != "YES" ]]; then
        echo "Aborted."
        exit 1
    fi
fi

echo "Wiping existing partitions..."
wipefs -a "$DEV"
parted -s "$DEV" mklabel msdos

# 4 equal partitions of ~48MB each
parted -s "$DEV" mkpart primary 1MiB 50MiB
parted -s "$DEV" mkpart primary 50MiB 100MiB
parted -s "$DEV" mkpart primary 100MiB 150MiB
parted -s "$DEV" mkpart primary 150MiB 100%

partprobe "$DEV"
sleep 2

if [[ $IMAGE_MODE -eq 1 ]]; then
    # With -P, partitions are named like /dev/loop0p1
    PART1="${DEV}p1"
    PART2="${DEV}p2"
    PART3="${DEV}p3"
    PART4="${DEV}p4"
else
    PART1="${DEV}1"
    PART2="${DEV}2"
    PART3="${DEV}3"
    PART4="${DEV}4"
fi

format_luks() {
    local part=$1
    local luks_type=$2
    local inner_fs=$3
    local map_name="luks_test_$$_${RANDOM}"

    echo "Formatting $part as $luks_type..."
    echo -n "$PASSWORD" | cryptsetup luksFormat --type "$luks_type" "$part" -
    echo "Opening $part..."
    echo -n "$PASSWORD" | cryptsetup luksOpen "$part" "$map_name" -
    
    echo "Formatting inner filesystem as $inner_fs..."
    case "$inner_fs" in
        ext2) mkfs.ext2 -F "/dev/mapper/$map_name" ;;
        ext3) mkfs.ext3 -F "/dev/mapper/$map_name" ;;
        ext4) mkfs.ext4 -F "/dev/mapper/$map_name" ;;
        fat32) mkfs.fat -F 32 "/dev/mapper/$map_name" ;;
        exfat) mkfs.exfat "/dev/mapper/$map_name" ;;
        none) echo "Skipping inner FS" ;;
    esac

    echo "Closing $map_name..."
    cryptsetup luksClose "$map_name"
}

case "$LAYOUT" in
    1)
        echo "Layout 1: exfat, fat32, luks1, luks2"
        mkfs.exfat "$PART1"
        mkfs.fat -F 32 "$PART2"
        format_luks "$PART3" luks1 ext4
        format_luks "$PART4" luks2 ext4
        ;;
    2)
        echo "Layout 2: 4 partitions luks1 -> ext2, ext3, ext4, fat32"
        format_luks "$PART1" luks1 ext2
        format_luks "$PART2" luks1 ext3
        format_luks "$PART3" luks1 ext4
        format_luks "$PART4" luks1 fat32
        ;;
    3)
        echo "Layout 3: 4 partitions luks2 -> ext2, ext3, ext4, fat32"
        format_luks "$PART1" luks2 ext2
        format_luks "$PART2" luks2 ext3
        format_luks "$PART3" luks2 ext4
        format_luks "$PART4" luks2 fat32
        ;;
    *)
        echo "Unknown layout: $LAYOUT"
        ;;
esac

if [[ $IMAGE_MODE -eq 1 ]]; then
    losetup -d "$DEV"
    echo "Image $DISK prepared successfully."
else
    echo "Device $DEV prepared successfully."
fi
