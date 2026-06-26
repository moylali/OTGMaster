#!/bin/bash
cd "$(dirname "$0")/.." || exit 1
set -e

mkdir -p testdata/fat32
mkdir -p testdata/fat32_keyfile
mkdir -p testdata/exfat
mkdir -p testdata/exfat_keyfile
mkdir -p testdata/fat16
mkdir -p testdata/ntfs
mkdir -p testdata/ext4
mkdir -p testdata/serpent


PASSWORD="password123"

populate_complex_files() {
    local MNT=$1
    echo "Copying flower.jpg..."
    cp testdata/flower.jpg "$MNT/flower.jpg"
    
    echo "Creating complex directory structures..."
    mkdir -p "$MNT/nested/very/deep/folder"
    touch "$MNT/nested/very/deep/folder/empty_file.txt"
    echo "Hello World" > "$MNT/file with spaces.txt"
    echo "Unicode" > "$MNT/unicöde_fîle.txt"
    # A file spanning multiple clusters (10KB)
    dd if=/dev/urandom of="$MNT/large_file.bin" bs=1024 count=10 2>/dev/null
}

create_fat32_volume() {
    DIR=$1
    HAS_KEYFILE=$2
    CIPHER=${3:-AES}
    PIM_VAL=${4:-1}
    IMG_FILE="$DIR/test.img"

    echo "$PASSWORD" > "$DIR/password.txt"
    echo "$PIM_VAL" > "$DIR/pim.txt"
    echo "$CIPHER" > "$DIR/cipher.txt"

    KEY_ARG=""
    if [ "$HAS_KEYFILE" = "true" ]; then
        dd if=/dev/urandom of="$DIR/test.key" bs=64 count=1 2>/dev/null
        KEY_ARG="-k $DIR/test.key"
    fi

    # Create container without inner filesystem first
    echo "Creating $IMG_FILE (no filesystem, cipher=$CIPHER, pim=$PIM_VAL)..."
    veracrypt -t -c --volume-type=normal "$IMG_FILE" --size="10M" --password="$PASSWORD" \
        --encryption=$CIPHER --hash=SHA-512 --filesystem=none --pim=$PIM_VAL $KEY_ARG \
        --random-source=/dev/urandom --non-interactive

    # Mount with no-filesystem to get raw decrypted block device
    echo "Mounting $IMG_FILE for FAT32 formatting..."
    veracrypt -t --mount "$IMG_FILE" --password="$PASSWORD" --pim=$PIM_VAL $KEY_ARG \
        --non-interactive --filesystem=none --slot=1

    # Format the decrypted block device as FAT32 explicitly
    echo "Formatting /dev/mapper/veracrypt1 as FAT32..."
    mkfs.fat -F 32 /dev/mapper/veracrypt1

    veracrypt -t -u "$IMG_FILE" --non-interactive

    # Remount so veracrypt auto-detects and mounts the FAT32
    echo "Remounting $IMG_FILE to copy files..."
    mkdir -p "/tmp/mnt_fat32_vc"
    veracrypt -t --mount "$IMG_FILE" "/tmp/mnt_fat32_vc" --password="$PASSWORD" --pim=$PIM_VAL $KEY_ARG \
        --non-interactive --slot=1

    populate_complex_files "/tmp/mnt_fat32_vc"

    echo "Unmounting $IMG_FILE..."
    veracrypt -t -u "$IMG_FILE" --non-interactive
    rm -rf "/tmp/mnt_fat32_vc"
}

create_exfat_volume() {
    DIR=$1
    HAS_KEYFILE=$2
    IMG_FILE="$DIR/test.img"

    echo "$PASSWORD" > "$DIR/password.txt"
    echo "1" > "$DIR/pim.txt"

    KEY_ARG=""
    if [ "$HAS_KEYFILE" = "true" ]; then
        dd if=/dev/urandom of="$DIR/test.key" bs=64 count=1 2>/dev/null
        KEY_ARG="-k $DIR/test.key"
    fi

    echo "Creating $IMG_FILE with fs exfat..."
    veracrypt -t -c --volume-type=normal "$IMG_FILE" --size="10M" --password="$PASSWORD" \
        --encryption=AES --hash=SHA-512 --filesystem=exfat --pim=1 $KEY_ARG \
        --random-source=/dev/urandom --non-interactive

    echo "Mounting $IMG_FILE..."
    mkdir -p "/tmp/mnt_exfat_vc"
    veracrypt -t --mount "$IMG_FILE" "/tmp/mnt_exfat_vc" --password="$PASSWORD" --pim=1 $KEY_ARG \
        --non-interactive

    populate_complex_files "/tmp/mnt_exfat_vc"

    echo "Unmounting $IMG_FILE..."
    veracrypt -t -u "$IMG_FILE" --non-interactive
    rm -rf "/tmp/mnt_exfat_vc"
}

create_unsupported_volume() {
    DIR=$1
    FS_DISPLAY_NAME=$2
    MKFS_CMD=$3
    IMG_FILE="$DIR/test.img"

    echo "$PASSWORD" > "$DIR/password.txt"
    echo "1" > "$DIR/pim.txt"
    echo "true" > "$DIR/expects_error.txt"
    echo "$FS_DISPLAY_NAME" > "$DIR/expected_fs.txt"

    echo "Creating $IMG_FILE (inner: $FS_DISPLAY_NAME)..."
    veracrypt -t -c --volume-type=normal "$IMG_FILE" --size="10M" --password="$PASSWORD" \
        --encryption=AES --hash=SHA-512 --filesystem=none --pim=1 \
        --random-source=/dev/urandom --non-interactive

    echo "Mounting $IMG_FILE..."
    veracrypt -t --mount "$IMG_FILE" --password="$PASSWORD" --pim=1 \
        --non-interactive --filesystem=none --slot=1

    echo "Formatting /dev/mapper/veracrypt1 as $FS_DISPLAY_NAME..."
    eval "$MKFS_CMD /dev/mapper/veracrypt1"

    echo "Unmounting..."
    veracrypt -t -u "$IMG_FILE" --non-interactive
}

create_partitioned_volume() {
    DIR=$1
    IMG_FILE="$DIR/test.img"
    
    echo "$PASSWORD" > "$DIR/password.txt"
    echo "1" > "$DIR/pim.txt"
    echo "AES" > "$DIR/cipher.txt"
    
    echo "Creating 20M raw disk image for partitioned volume..."
    dd if=/dev/zero of="$IMG_FILE" bs=1M count=20 2>/dev/null
    
    echo "Creating MBR partition table and partitions..."
    parted -s "$IMG_FILE" mklabel msdos
    parted -s "$IMG_FILE" mkpart primary 1MiB 6MiB
    parted -s "$IMG_FILE" mkpart primary 6MiB 19MiB
    
    # Map to loop devices
    LOOP_DEV=$(losetup -P -f --show "$IMG_FILE")
    
    echo "Creating VeraCrypt volume on partition 2 (${LOOP_DEV}p2)..."
    veracrypt -t -c --volume-type=normal "${LOOP_DEV}p2" --password="$PASSWORD" \
        --encryption=AES --hash=SHA-512 --filesystem=none --pim=1 \
        --random-source=/dev/urandom --non-interactive
        
    echo "Mounting ${LOOP_DEV}p2 to format FAT32..."
    veracrypt -t --mount "${LOOP_DEV}p2" --password="$PASSWORD" --pim=1 \
        --non-interactive --filesystem=none --slot=1
        
    mkfs.fat -F 32 /dev/mapper/veracrypt1
    veracrypt -t -u "${LOOP_DEV}p2" --non-interactive
    
    echo "Remounting ${LOOP_DEV}p2 to copy files..."
    mkdir -p "/tmp/mnt_part_vc"
    veracrypt -t --mount "${LOOP_DEV}p2" "/tmp/mnt_part_vc" --password="$PASSWORD" --pim=1 \
        --non-interactive --slot=1
        
    populate_complex_files "/tmp/mnt_part_vc"
    
    veracrypt -t -u "${LOOP_DEV}p2" --non-interactive
    rm -rf "/tmp/mnt_part_vc"
    
    echo "Formatting the first partition as normal FAT32..."
    mkfs.fat -F 32 "${LOOP_DEV}p1"
    
    losetup -d "$LOOP_DEV"
}

mkdir -p testdata/fat32_keyfile_pim
mkdir -p testdata/partitioned_mbr

create_fat32_volume "testdata/fat32" "false"
create_fat32_volume "testdata/fat32_keyfile" "true"
create_fat32_volume "testdata/fat32_keyfile_pim" "true" "AES" "123"
create_fat32_volume "testdata/serpent" "false" "Serpent"

create_partitioned_volume "testdata/partitioned_mbr"

# Unsupported-cipher case: a normal AES volume, but the test selects "Twofish" in the
# cipher picker, so the app must reject it before ever touching the device.
mkdir -p testdata/unsupported_cipher
create_fat32_volume "testdata/unsupported_cipher" "false"
echo "Twofish" > "testdata/unsupported_cipher/cipher.txt"
echo "true" > "testdata/unsupported_cipher/expects_error.txt"

create_exfat_volume "testdata/exfat" "false"
create_exfat_volume "testdata/exfat_keyfile" "true"
create_unsupported_volume "testdata/fat16" "FAT16" "mkfs.fat -F 16"
create_unsupported_volume "testdata/ntfs" "NTFS" "mkfs.ntfs -f -q"
create_unsupported_volume "testdata/ext4" "ext4" "mkfs.ext4 -F"

# Create a dummy image to act as a second simultaneous USB drive (for multi-drive testing)
dd if=/dev/zero of="testdata/dummy.img" bs=1M count=2 2>/dev/null

# Remove the old default directory
rm -rf testdata/default_image

# Ensure all generated files are readable by the invoking user (when run via sudo, files are root-owned)
chmod -R a+rX testdata/

echo "Done generating test data."
