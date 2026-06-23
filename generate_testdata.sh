#!/bin/bash
set -e

mkdir -p testdata/fat32
mkdir -p testdata/fat32_keyfile
mkdir -p testdata/exfat
mkdir -p testdata/exfat_keyfile
mkdir -p testdata/fat16
mkdir -p testdata/ntfs
mkdir -p testdata/ext4


PASSWORD="password123"

create_fat32_volume() {
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

    # Create container without inner filesystem first
    echo "Creating $IMG_FILE (no filesystem)..."
    veracrypt -t -c --volume-type=normal "$IMG_FILE" --size="10M" --password="$PASSWORD" \
        --encryption=AES --hash=SHA-512 --filesystem=none --pim=1 $KEY_ARG \
        --random-source=/dev/urandom --non-interactive

    # Mount with no-filesystem to get raw decrypted block device
    echo "Mounting $IMG_FILE for FAT32 formatting..."
    veracrypt -t --mount "$IMG_FILE" --password="$PASSWORD" --pim=1 $KEY_ARG \
        --non-interactive --filesystem=none --slot=1

    # Format the decrypted block device as FAT32 explicitly
    echo "Formatting /dev/mapper/veracrypt1 as FAT32..."
    mkfs.fat -F 32 /dev/mapper/veracrypt1

    veracrypt -t -u "$IMG_FILE" --non-interactive

    # Remount so veracrypt auto-detects and mounts the FAT32
    echo "Remounting $IMG_FILE to copy files..."
    mkdir -p "/tmp/mnt_fat32_vc"
    veracrypt -t --mount "$IMG_FILE" "/tmp/mnt_fat32_vc" --password="$PASSWORD" --pim=1 $KEY_ARG \
        --non-interactive --slot=1

    echo "Copying flower.jpg..."
    cp testdata/flower.jpg "/tmp/mnt_fat32_vc/flower.jpg"

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

    echo "Copying flower.jpg..."
    cp testdata/flower.jpg "/tmp/mnt_exfat_vc/flower.jpg"

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

create_fat32_volume "testdata/fat32" "false"
create_fat32_volume "testdata/fat32_keyfile" "true"
create_exfat_volume "testdata/exfat" "false"
create_exfat_volume "testdata/exfat_keyfile" "true"
create_unsupported_volume "testdata/fat16" "FAT16" "mkfs.fat -F 16"
create_unsupported_volume "testdata/ntfs" "NTFS" "mkfs.ntfs -f -q"
create_unsupported_volume "testdata/ext4" "ext4" "mkfs.ext4 -F"

# Remove the old default directory
rm -rf testdata/default_image

# Ensure all generated files are readable by the invoking user (when run via sudo, files are root-owned)
chmod -R a+rX testdata/

echo "Done generating test data."
