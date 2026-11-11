#!/usr/bin/env bash
# Builds testdata/apfs/apfs_multi.tar.xz: one APFS container holding two volumes, for the
# test that the app opens the volume asked for rather than always the first (review of
# 2026-11-09, "APFS multi-volume"). macOS only, like make_apfs_images_macos.sh.
#
#   volume 0  OTGVOLA  unencrypted         volume_a.txt, a.bin
#   volume 1  OTGVOLB  encrypted (password123)  volume_b.txt, b.bin
#
# 1100 MiB, not the 64 MiB of the E2E images: APFS allows one volume per 512 MiB of
# container, and diskutil refused a second volume in 64 MiB (-69493). So this image does
# not fit the E2E emulator's 64 MiB slot and is used by a host test. The volumes are
# almost empty, so the archive is about 250 KB.
#
# Run it as a script (bash scripts/make_apfs_multi_macos.sh), not pasted into a shell:
# `set -e` in an interactive shell closes the shell at the first failing command.
set -euo pipefail
repo=$(cd "$(dirname "$0")/.." && pwd)
[ "$(uname)" = Darwin ] || { echo "This script needs macOS (hdiutil, diskutil)."; exit 1; }
work=$(mktemp -d); disk=""
cleanup() { [ -n "$disk" ] && hdiutil detach "$disk" -force >/dev/null 2>&1 || true; rm -rf "$work"; }
trap cleanup EXIT
cd "$work"
hdiutil create -size 1100m -layout GPTSPUD -fs APFS -volname OTGTMP -type UDIF multi.dmg
out=$(hdiutil attach -nomount multi.dmg); echo "$out"
disk=$(awk '/GUID_partition_scheme/{print $1;exit}' <<<"$out")
synth=$(awk '/EF57347C-0000-11AA-AA11/{print $1;exit}' <<<"$out")
tmpvol=$(awk '/41504653-0000-11AA-AA11/{print $1;exit}' <<<"$out")
diskutil apfs deleteVolume "$tmpvol"
diskutil apfs addVolume "$synth" APFS OTGVOLA
diskutil apfs addVolume "$synth" APFS OTGVOLB -passphrase password123
a=$(diskutil info OTGVOLA | awk -F': *' '/Mount Point/{print $2}')
b=$(diskutil info OTGVOLB | awk -F': *' '/Mount Point/{print $2}')
printf 'volume A\n' > "$a/volume_a.txt"; dd if=/dev/urandom of="$a/a.bin" bs=1024 count=10 2>/dev/null
printf 'volume B\n' > "$b/volume_b.txt"; dd if=/dev/urandom of="$b/b.bin" bs=1024 count=10 2>/dev/null
mkdir -p apfs_multi
(cd "$a" && shasum -a 256 volume_a.txt a.bin) > apfs_multi/volume_a.sha256
(cd "$b" && shasum -a 256 volume_b.txt b.bin) > apfs_multi/volume_b.sha256
diskutil apfs list "$synth" > apfs_multi/apfs_list.txt
sync; diskutil unmount OTGVOLA; diskutil unmount OTGVOLB
hdiutil detach "$disk"; disk=""
hdiutil convert multi.dmg -format UDTO -o multi
mv multi.cdr apfs_multi/test.img
COPYFILE_DISABLE=1 tar --no-mac-metadata --uid 0 --gid 0 -cJf "$repo/testdata/apfs/apfs_multi.tar.xz" apfs_multi
shasum -a 256 apfs_multi/test.img "$repo/testdata/apfs/apfs_multi.tar.xz"
echo "Done. Update the hashes in testdata/apfs/PROVENANCE.md."
