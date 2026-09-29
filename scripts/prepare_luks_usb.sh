#!/bin/bash
# Build one 4-partition LUKS test volume, on a real disk or a loopback image.
#
# Usage: sudo ./scripts/prepare_luks_usb.sh --disk /dev/sdX  --layout 1
#        sudo ./scripts/prepare_luks_usb.sh --image test.img --layout 2
#
# This is the worker the rest of the small-media set is built on;
# generate_luks_testcases.sh calls it three times to produce the whole matrix.
#
# Layouts, each four ~50 MB primary partitions:
#   1  mixed containers   exFAT, FAT32, LUKS1, LUKS2 side by side on one disk —
#                         the case that proves the prober classifies each
#                         partition independently rather than by disk.
#   2  LUKS1 inner-FS     ext2, ext3, ext4, FAT32 each inside a LUKS1 container.
#   3  LUKS2 inner-FS     the same four inside LUKS2 (Argon2id).
#
# --image makes a 200 MB file and attaches it with losetup -P, so the whole
# matrix can be rebuilt with no hardware attached. --disk repartitions a real
# device and prompts for an uppercase YES first; it is destructive.
#
# Password is password123 throughout, matching scripts/generate_testdata.sh so
# the E2E suite and these images agree.
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
