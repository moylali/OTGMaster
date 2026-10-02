#!/usr/bin/env bash
# Builds the NTFS E2E fixtures — NTFS inside VeraCrypt and NTFS inside BitLocker —
# without root:
#
#   testdata/ntfs                  VeraCrypt AES/SHA-512 + NTFS, mount and read
#   testdata/ntfs_write            VeraCrypt AES/SHA-512 + NTFS, write and remount
#   testdata/bitlocker_ntfs        BitLocker AES-CBC-128 + NTFS, mount and read
#   testdata/bitlocker_ntfs_write  BitLocker XTS-AES-128 + NTFS, write and remount
#
# The NTFS is made by mkntfs and filled through a user-mode ntfs-3g FUSE mount.
# VeraCrypt containers come from the veracrypt CLI with --filesystem=none and are
# filled by scripts/fill_veracrypt_volume.py; BitLocker volumes are built by
# scripts/make_bitlocker_image.py and must pass its cryptsetup --verify.
#
# Needs: veracrypt, mkntfs, ntfs-3g (FUSE usable by this user), fusermount,
# cryptsetup, python3 with `cryptography`.
set -euo pipefail
cd "$(dirname "$0")/.."

PASSWORD="password123"
PIM=1
RECOVERY="111111-222222-333333-444444-555555-666666-111111-222222"
work=$(mktemp -d)
trap 'fusermount -u "$work/mnt" 2>/dev/null || true; rm -rf "$work"' EXIT
mkdir -p "$work/mnt"

# The same content the other mount fixtures carry, which E2EAutomatedTest checks.
populate() {
    local img=$1
    ntfs-3g "$img" "$work/mnt"
    cp testdata/flower.jpg "$work/mnt/flower.jpg"
    mkdir -p "$work/mnt/nested/very/deep/folder"
    touch "$work/mnt/nested/very/deep/folder/empty_file.txt"
    echo "Hello World" > "$work/mnt/file with spaces.txt"
    echo "Unicode" > "$work/mnt/unicöde_fîle.txt"
    dd if=/dev/urandom of="$work/mnt/large_file.bin" bs=1024 count=10 status=none
    fusermount -u "$work/mnt"
}

veracrypt_ntfs() {
    local dir=$1 desc=$2 write=$3
    mkdir -p "$dir"
    # rm, not overwrite: the old fixtures were made with sudo and their files are root's.
    rm -f "$dir/test.img" "$dir"/*.txt
    veracrypt -t -c --volume-type=normal "$dir/test.img" --size=16M --password="$PASSWORD" \
        --encryption=AES --hash=SHA-512 --filesystem=none --pim=$PIM \
        --random-source=/dev/urandom --non-interactive >/dev/null
    python3 scripts/fill_veracrypt_volume.py "$dir/test.img" "$PASSWORD" $PIM --make-plain "$work/plain.img"
    mkntfs -F -q -f -L VCNTFS "$work/plain.img" >/dev/null 2>&1
    populate "$work/plain.img"
    python3 scripts/fill_veracrypt_volume.py "$dir/test.img" "$PASSWORD" $PIM --fill "$work/plain.img"
    chmod 644 "$dir/test.img"
    echo "$PASSWORD" > "$dir/password.txt"
    echo "$PIM" > "$dir/pim.txt"
    echo "$desc" > "$dir/description.txt"
    [ "$write" = true ] && echo true > "$dir/write_test.txt"
    rm -f "$work/plain.img"
}

bitlocker_ntfs() {
    local dir=$1 cipher=$2 desc=$3 write=$4
    mkdir -p "$dir"
    rm -f "$dir/test.img" "$dir"/*.txt
    truncate -s 16M "$work/plain.img"
    mkntfs -F -q -f -L BLNTFS "$work/plain.img" >/dev/null 2>&1
    populate "$work/plain.img"
    python3 scripts/make_bitlocker_image.py "$work/plain.img" "$dir/test.img" \
        --password "$PASSWORD" --recovery "$RECOVERY" --cipher "$cipher" --verify
    echo "$PASSWORD" > "$dir/password.txt"
    echo "$RECOVERY" > "$dir/recovery.txt"
    echo "BITLOCKER" > "$dir/container.txt"
    echo "$desc" > "$dir/description.txt"
    [ "$write" = true ] && echo true > "$dir/write_test.txt"
    rm -f "$work/plain.img"
}

veracrypt_ntfs testdata/ntfs \
    "AES-encrypted VeraCrypt volume formatted as NTFS. Verifies the app unlocks it, mounts the NTFS, and reads flower.jpg through the DocumentsProvider." false
veracrypt_ntfs testdata/ntfs_write \
    "AES-encrypted VeraCrypt volume formatted as NTFS. Verifies create, write, nested directory, delete and their persistence across remounts." true
bitlocker_ntfs testdata/bitlocker_ntfs cbc128 \
    "BitLocker AES-CBC-128 volume (Windows' default for removable drives) holding NTFS. Verifies the app recognises BitLocker, unlocks it with the password, mounts the NTFS and reads flower.jpg." false
bitlocker_ntfs testdata/bitlocker_ntfs_write xts128 \
    "BitLocker XTS-AES-128 volume holding NTFS. Verifies create, write, nested directory, delete and their persistence across remounts through BitLocker." true
echo "NTFS fixtures ready."
