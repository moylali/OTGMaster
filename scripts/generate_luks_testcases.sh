#!/bin/bash
# Generate the 3 LUKS test USB images.
# This requires sudo. Run it from the workspace root.

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
