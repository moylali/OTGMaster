#!/usr/bin/env bash
# Builds the APFS E2E fixtures. macOS only: no other OS creates APFS with native
# encryption, and populating APFS on Linux needs the out-of-tree linux-apfs-rw
# kernel module, which Secure Boot refuses to load unsigned.
#
#   testdata/apfs/apfs_ci       APFS, case-insensitive (Disk Utility's default)
#   testdata/apfs/apfs_cs       APFS, case-sensitive
#   testdata/apfs/apfs_enc_ci   APFS (Encrypted), case-insensitive, password123
#   testdata/apfs/apfs_enc_cs   APFS (Encrypted), case-sensitive, password123
#
# Each is a raw disk image laid out as Disk Utility formats a USB stick — a GPT
# with one APFS partition — holding the same files as every other mount case
# (flower.jpg, "file with spaces.txt", a nested tree, a Unicode name). The
# encrypted volumes are created encrypted (`diskutil apfs addVolume -passphrase`)
# rather than converted afterwards, so there is no background encryption to wait
# for and no half-encrypted state to capture.
#
# Run on a Mac from the repo root, then copy testdata/apfs/ to the Linux host and
# verify there with scripts/verify_apfs_images.py (apfsck + apfs-fuse, which
# decrypts with the password — an implementation independent of Apple's).
#
#   bash scripts/make_apfs_images_macos.sh
set -euo pipefail
cd "$(dirname "$0")/.."
[ "$(uname)" = Darwin ] || { echo "This script needs macOS (hdiutil, diskutil)."; exit 1; }

PASSWORD="password123"
SIZE="64m"   # comfortably above APFS's minimum, small enough for the 64 MiB QEMU slot
work=$(mktemp -d)
attached=""
cleanup() {
    [ -n "$attached" ] && hdiutil detach "$attached" -force >/dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT

populate() {
    local mnt=$1
    cp testdata/flower.jpg "$mnt/flower.jpg"
    mkdir -p "$mnt/nested/very/deep/folder"
    : > "$mnt/nested/very/deep/folder/empty_file.txt"
    printf 'Hello World\n' > "$mnt/file with spaces.txt"
    printf 'Unicode\n' > "$mnt/unicöde_fîle.txt"
    dd if=/dev/urandom of="$mnt/large_file.bin" bs=1024 count=10 2>/dev/null
    # Case-sensitivity is observable only with names differing in case: on a
    # case-sensitive volume both files exist; on an insensitive one the second
    # write lands on the first.
    printf 'lower\n' > "$mnt/case.txt"
    printf 'UPPER\n' > "$mnt/CASE.txt"
}

build() {
    local name=$1 personality=$2 encrypted=$3
    # Under testdata/apfs/, not testdata/ itself: run_e2e_tests.sh runs every
    # testdata/*/ holding a test.img, and the app does not read APFS yet.
    local dir="testdata/apfs/$name"
    echo "== $name ($personality${encrypted:+, encrypted})"
    mkdir -p "$dir"
    rm -f "$dir/test.img" "$dir"/*.txt

    # A GPT disk with one APFS container, which holds a throwaway volume.
    hdiutil create -size "$SIZE" -layout GPTSPUD -fs APFS -volname OTGTMP \
        -type UDIF "$work/$name.dmg" >/dev/null
    # `hdiutil attach` lists the GPT disk, its Apple_APFS partition, the
    # synthesized APFS container (type EF57347C-...) and the container's volume
    # (type 41504653-..., "APFS" in ASCII).
    local out synth tmpvol
    out=$(hdiutil attach -nomount "$work/$name.dmg")
    attached=$(awk '/GUID_partition_scheme/ {print $1; exit}' <<< "$out")
    synth=$(awk '/EF57347C-0000-11AA-AA11/ {print $1; exit}' <<< "$out")
    tmpvol=$(awk '/41504653-0000-11AA-AA11/ {print $1; exit}' <<< "$out")
    [ -n "$attached" ] && [ -n "$synth" ] && [ -n "$tmpvol" ] \
        || { echo "unexpected hdiutil attach output:"; echo "$out"; exit 1; }
    diskutil apfs deleteVolume "$tmpvol" >/dev/null
    if [ -n "$encrypted" ]; then
        diskutil apfs addVolume "$synth" "$personality" OTGAPFS -passphrase "$PASSWORD" >/dev/null
    else
        diskutil apfs addVolume "$synth" "$personality" OTGAPFS >/dev/null
    fi
    local mnt
    mnt=$(diskutil info OTGAPFS | awk -F': *' '/Mount Point/ {print $2}')
    [ -d "$mnt" ] || { echo "OTGAPFS did not mount"; exit 1; }
    populate "$mnt"
    sync
    if [ "$personality" = "Case-sensitive APFS" ]; then
        [ "$(cat "$mnt/case.txt")" = lower ] && [ "$(cat "$mnt/CASE.txt")" = UPPER ] \
            || { echo "volume is not case-sensitive"; exit 1; }
    else
        [ "$(cat "$mnt/case.txt")" = UPPER ] || { echo "volume is not case-insensitive"; exit 1; }
    fi
    diskutil unmount OTGAPFS >/dev/null
    hdiutil detach "$attached" >/dev/null
    attached=""

    # UDTO is a raw, sector-for-sector image (written as .cdr).
    hdiutil convert "$work/$name.dmg" -format UDTO -o "$work/$name" >/dev/null
    mv "$work/$name.cdr" "$dir/test.img"
    chmod 644 "$dir/test.img"

    echo "$PASSWORD" > "$dir/password.txt"
    echo "APFS" > "$dir/expected_fs.txt"
    echo "$personality${encrypted:+ with native APFS encryption (password)} on a GPT disk, as Disk Utility formats a USB stick. Unlock${encrypted:+ with the password}, mount, read flower.jpg." > "$dir/description.txt"
    shasum -a 256 "$dir/test.img"
}

build apfs_ci  "APFS"                ""
build apfs_cs  "Case-sensitive APFS" ""
build apfs_enc_ci "APFS"             yes
build apfs_enc_cs "Case-sensitive APFS" yes
echo "Done. Copy testdata/apfs/ to the Linux host and run scripts/verify_apfs_images.py."
