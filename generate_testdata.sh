#!/bin/bash
set -e

mkdir -p testdata/fat32
mkdir -p testdata/fat32_keyfile
mkdir -p testdata/exfat
mkdir -p testdata/exfat_keyfile

# Create a sample.jpg
echo "/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wgALCAABAAEBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA=" | base64 -d > sample.jpg

PASSWORD="password123"

create_volume() {
    DIR=$1
    FS=$2
    HAS_KEYFILE=$3
    IMG_FILE="$DIR/test.img"
    
    echo "$PASSWORD" > "$DIR/password.txt"
    
    KEY_ARG=""
    if [ "$HAS_KEYFILE" = "true" ]; then
        dd if=/dev/urandom of="$DIR/test.key" bs=64 count=1 2>/dev/null
        KEY_ARG="-k $DIR/test.key"
    fi
    
    echo "Creating $IMG_FILE with fs $FS..."
    veracrypt -t -c --volume-type=normal "$IMG_FILE" --size="10M" --password="$PASSWORD" --encryption=AES --hash=SHA-512 --filesystem="$FS" --pim=0 $KEY_ARG --random-source=/dev/urandom --non-interactive
    
    echo "Mounting $IMG_FILE..."
    mkdir -p "/tmp/mnt_$FS"
    veracrypt -t --mount "$IMG_FILE" "/tmp/mnt_$FS" --password="$PASSWORD" --pim=0 $KEY_ARG --non-interactive
    
    echo "Copying sample.jpg..."
    cp sample.jpg "/tmp/mnt_$FS/sample.jpg"
    
    echo "Unmounting $IMG_FILE..."
    veracrypt -t -d "$IMG_FILE" --non-interactive
    rm -rf "/tmp/mnt_$FS"
}

create_volume "testdata/fat32" "fat" "false"
create_volume "testdata/fat32_keyfile" "fat" "true"
create_volume "testdata/exfat" "exfat" "false"
create_volume "testdata/exfat_keyfile" "exfat" "true"

# Remove the old default directory
rm -rf testdata/default
rm sample.jpg

echo "Done generating test data."
