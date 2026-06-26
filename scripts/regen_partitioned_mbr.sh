#!/bin/bash
# One-shot script to regenerate testdata/partitioned_mbr only.
cd "$(dirname "$0")/.." || exit 1
set -e

PASSWORD="password123"

populate_complex_files() {
    local MNT=$1
    cp testdata/flower.jpg "$MNT/flower.jpg"
    mkdir -p "$MNT/nested/very/deep/folder"
    touch "$MNT/nested/very/deep/folder/empty_file.txt"
    echo "Hello World" > "$MNT/file with spaces.txt"
    echo "Unicode" > "$MNT/unicöde_fîle.txt"
    dd if=/dev/urandom of="$MNT/large_file.bin" bs=1024 count=10 2>/dev/null
}

DIR="testdata/partitioned_mbr"
IMG_FILE="$DIR/test.img"

echo "$PASSWORD" > "$DIR/password.txt"
echo "1" > "$DIR/pim.txt"
echo "AES" > "$DIR/cipher.txt"

echo "Creating 20M raw disk image..."
dd if=/dev/zero of="$IMG_FILE" bs=1M count=20 2>/dev/null

echo "Creating MBR with two partitions..."
parted -s "$IMG_FILE" mklabel msdos
parted -s "$IMG_FILE" mkpart primary fat32 1MiB 5MiB
parted -s "$IMG_FILE" mkpart primary 5MiB 15MiB

TMP_VC="$DIR/tmp_vc.img"
echo "Creating VeraCrypt volume (filesystem=none, 10M)..."
touch "$TMP_VC" && chmod 666 "$TMP_VC"
sudo veracrypt -t -c --volume-type=normal "$TMP_VC" --size="10M" --password="$PASSWORD" \
    --encryption=AES --hash=SHA-512 --filesystem=none --pim=1 \
    --random-source=/dev/urandom --non-interactive

sudo veracrypt -t --mount "$TMP_VC" --password="$PASSWORD" --pim=1 --filesystem=none --non-interactive
MAPPED_SLOT=$(sudo veracrypt -t -l | grep "$TMP_VC" | awk '{print $3}')
sudo mkfs.fat -F 32 "$MAPPED_SLOT"

mkdir -p /tmp/mnt_part_vc
sudo mount -o "uid=$(id -u),gid=$(id -g)" "$MAPPED_SLOT" /tmp/mnt_part_vc
populate_complex_files /tmp/mnt_part_vc

sudo sync
sudo umount /tmp/mnt_part_vc
sudo sync
sudo veracrypt -t -d "$TMP_VC" 2>/dev/null \
    || sudo dmsetup remove "$(basename "$MAPPED_SLOT")" 2>/dev/null \
    || true
rm -rf /tmp/mnt_part_vc

echo "Injecting VeraCrypt volume at partition 2 (5MiB offset)..."
dd if="$TMP_VC" of="$IMG_FILE" bs=1M seek=5 conv=notrunc 2>/dev/null

TMP_FAT="$DIR/tmp_fat.img"
dd if=/dev/zero of="$TMP_FAT" bs=1M count=4 2>/dev/null
mkfs.fat -F 32 "$TMP_FAT"
echo "Injecting FAT32 at partition 1 (1MiB offset)..."
dd if="$TMP_FAT" of="$IMG_FILE" bs=1M seek=1 conv=notrunc 2>/dev/null

rm -f "$TMP_VC" "$TMP_FAT"
chmod a+rX "$DIR/"*

echo "Done. Partition layout:"
parted -s "$IMG_FILE" print
