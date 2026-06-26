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

    # Create container without filesystem
    echo "Creating $IMG_FILE (fs: fat32, cipher=$CIPHER, pim=$PIM_VAL)..."
    touch "$IMG_FILE" && chmod 666 "$IMG_FILE" && sudo veracrypt -t -c --volume-type=normal "$IMG_FILE" --size="10M" --password="$PASSWORD" \
        --encryption=$CIPHER --hash=SHA-512 --filesystem=none --pim=$PIM_VAL $KEY_ARG \
        --random-source=/dev/urandom --non-interactive

    # Mount it to format manually
    MAPPED_SLOT=$(sudo veracrypt -t --mount "$IMG_FILE" --password="$PASSWORD" --pim=$PIM_VAL $KEY_ARG --filesystem=none --non-interactive | grep -o '/dev/mapper/veracrypt[0-9]*')
    if [ -z "$MAPPED_SLOT" ]; then
        # Try to find it if stdout was empty
        MAPPED_SLOT=$(sudo veracrypt -t -l | grep "$IMG_FILE" | awk '{print $4}')
    fi
    
    sudo mkfs.fat -F 32 "$MAPPED_SLOT"

    mkdir -p "/tmp/mnt_fat32_vc"
    sudo mount -o "uid=$(id -u),gid=$(id -g)" "$MAPPED_SLOT" "/tmp/mnt_fat32_vc"

    populate_complex_files "/tmp/mnt_fat32_vc"

    sudo sync
    sudo umount "/tmp/mnt_fat32_vc"
    sudo sync
    # veracrypt -d can race with the kernel releasing the device; fall back to dmsetup
    sudo veracrypt -t -d "$IMG_FILE" 2>/dev/null \
        || sudo dmsetup remove "$(basename "$MAPPED_SLOT")" 2>/dev/null \
        || true
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
    touch "$IMG_FILE" && chmod 666 "$IMG_FILE" && sudo veracrypt -t -c --volume-type=normal "$IMG_FILE" --size="10M" --password="$PASSWORD" \
        --encryption=AES --hash=SHA-512 --filesystem=exfat --pim=1 $KEY_ARG \
        --random-source=/dev/urandom --non-interactive

    echo "Mounting $IMG_FILE..."
    mkdir -p "/tmp/mnt_exfat_vc"
    sudo veracrypt -t --mount "$IMG_FILE" "/tmp/mnt_exfat_vc" --password="$PASSWORD" --pim=1 $KEY_ARG \
        --non-interactive --fs-options="uid=$(id -u),gid=$(id -g)"

    populate_complex_files "/tmp/mnt_exfat_vc"

    echo "Unmounting $IMG_FILE..."
    sudo veracrypt -t -u "$IMG_FILE" --non-interactive
    rm -rf "/tmp/mnt_exfat_vc"
}

create_unsupported_volume() {
    DIR=$1
    FS_DISPLAY_NAME=$2
    FS_VERACRYPT=$3
    IMG_FILE="$DIR/test.img"

    echo "$PASSWORD" > "$DIR/password.txt"
    echo "1" > "$DIR/pim.txt"
    echo "true" > "$DIR/expects_error.txt"
    echo "$FS_DISPLAY_NAME" > "$DIR/expected_fs.txt"

    echo "Creating $IMG_FILE (inner: $FS_DISPLAY_NAME)..."
    touch "$IMG_FILE" && chmod 666 "$IMG_FILE" && sudo veracrypt -t -c --volume-type=normal "$IMG_FILE" --size="10M" --password="$PASSWORD" \
        --encryption=AES --hash=SHA-512 --filesystem=$FS_VERACRYPT --pim=1 \
        --random-source=/dev/urandom --non-interactive

    echo "Mounting $IMG_FILE..."
    mkdir -p "/tmp/mnt_other_vc"
    sudo veracrypt -t --mount "$IMG_FILE" "/tmp/mnt_other_vc" --password="$PASSWORD" --pim=1 \
        --non-interactive --slot=1
    
    # if [ "$FS_VERACRYPT" = "ext4" ]; then
    #     sudo chown -R $(id -u):$(id -g) "/tmp/mnt_other_vc"
    # fi

    # Create dummy files only if we can write to it
    if [ "$FS_VERACRYPT" != "ext4" ]; then
        echo "Hello $FS_DISPLAY_NAME" > "/tmp/mnt_other_vc/hello.txt"
        mkdir -p "/tmp/mnt_other_vc/folder"
        echo "World" > "/tmp/mnt_other_vc/folder/world.txt"
    fi

    echo "Unmounting..."
    sudo veracrypt -t -u "$IMG_FILE" --non-interactive
    rm -rf "/tmp/mnt_other_vc"
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
    # Create the VeraCrypt volume in a separate file first (no filesystem so we can force FAT32)
    TMP_VC="$DIR/tmp_vc.img"
    echo "Creating VeraCrypt volume (filesystem=none, will format as FAT32)..."
    touch "$TMP_VC" && chmod 666 "$TMP_VC" && sudo veracrypt -t -c --volume-type=normal "$TMP_VC" --size="10M" --password="$PASSWORD" \
        --encryption=AES --hash=SHA-512 --filesystem=none --pim=1 \
        --random-source=/dev/urandom --non-interactive

    MAPPED_SLOT=$(sudo veracrypt -t --mount "$TMP_VC" --password="$PASSWORD" --pim=1 --filesystem=none --non-interactive | grep -o '/dev/mapper/veracrypt[0-9]*')
    if [ -z "$MAPPED_SLOT" ]; then
        MAPPED_SLOT=$(sudo veracrypt -t -l | grep "$TMP_VC" | awk '{print $4}')
    fi
    sudo mkfs.fat -F 32 "$MAPPED_SLOT"

    echo "Mounting to copy files..."
    mkdir -p "/tmp/mnt_part_vc"
    sudo mount -o "uid=$(id -u),gid=$(id -g)" "$MAPPED_SLOT" "/tmp/mnt_part_vc"

    populate_complex_files "/tmp/mnt_part_vc"

    echo "Unmounting..."
    sudo sync
    sudo umount "/tmp/mnt_part_vc"
    sudo sync
    sudo veracrypt -t -d "$TMP_VC" 2>/dev/null \
        || sudo dmsetup remove "$(basename "$MAPPED_SLOT")" 2>/dev/null \
        || true
    rm -rf "/tmp/mnt_part_vc"
    
    echo "Formatting the first partition as normal FAT32..."
    TMP_FAT="$DIR/tmp_fat.img"
    dd if=/dev/zero of="$TMP_FAT" bs=1M count=4 2>/dev/null
    mkfs.fat -F 32 "$TMP_FAT"
    echo "Hello from regular boot partition" > "$DIR/boot.txt"
    # We can use mcopy to copy boot.txt if needed, but not strictly necessary for test
    
    echo "Injecting partitions into MBR..."
    dd if="$TMP_FAT" of="$IMG_FILE" bs=1M seek=1 conv=notrunc 2>/dev/null
    dd if="$TMP_VC" of="$IMG_FILE" bs=1M seek=5 conv=notrunc 2>/dev/null
    
    rm -f "$TMP_VC" "$TMP_FAT"
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
create_unsupported_volume "testdata/fat16" "FAT16" "fat"
create_unsupported_volume "testdata/ntfs" "NTFS" "ntfs"
create_unsupported_volume "testdata/ext4" "ext4" "ext4"

# Create a dummy image to act as a second simultaneous USB drive (for multi-drive testing)
dd if=/dev/zero of="testdata/dummy.img" bs=1M count=2 2>/dev/null

# Remove the old default directory
rm -rf testdata/default_image

# Ensure all generated files are readable by the invoking user (when run via sudo, files are root-owned)
chmod -R a+rX testdata/

echo "Done generating test data."
