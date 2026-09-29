#!/usr/bin/env bash
# Post-run structural check for a VeraCrypt volume. READ-ONLY throughout.
#
# Usage: sudo bash scripts/verify_veracrypt_volume.sh /dev/sdX1 [password] [pim]
#
# Why: a SHA-256 over the bytes just written cannot see a corrupt filesystem.
# The ext4 write path once reported "write verify: ALL PASSED" with matching
# hashes on all three passes while every checksum it wrote was invalid. Only the
# filesystem's own checker catches that, and the device path runs through a
# crypto layer the host unit tests cannot reach — so the volume gets checked
# from here after a device run.
#
# Read-only by construction: the volume is attached with --filesystem=none and
# detached again; e2fsck runs with -fn (forced, answer no to everything, opens
# read-only); fsck.exfat runs with no repair flag, which is check-only; fsck.vfat
# runs with -n. Nothing here is given permission to write.

set -uo pipefail

PART="${1:?Usage: sudo bash $0 /dev/sdX1 [password] [pim]}"
VC_PASS="${2:-password123}"
VC_PIM="${3:-1}"

[[ $EUID -eq 0 ]] || { echo "ERROR: run with sudo."; exit 1; }
[[ -b "$PART" ]] || { echo "ERROR: $PART is not a block device."; exit 1; }

cleanup() { veracrypt -t -d "$PART" --non-interactive >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "=== attaching $PART (PIM $VC_PIM) ==="
veracrypt -t -d "$PART" --non-interactive >/dev/null 2>&1
veracrypt -t --mount "$PART" \
    --password="$VC_PASS" --pim="$VC_PIM" --keyfiles="" \
    --protect-hidden=no --filesystem=none --non-interactive \
    || { echo "ERROR: could not attach — wrong password/PIM, or not a VeraCrypt volume."; exit 1; }

VC_DEV=$(veracrypt -t -l 2>/dev/null | awk -v p="$PART" '$2 == p { print $3 }')
[[ -n "$VC_DEV" && -b "$VC_DEV" ]] || { echo "ERROR: attached but no device node reported."; exit 1; }
echo "decrypted device: $VC_DEV"

FS=$(blkid -o value -s TYPE "$VC_DEV" 2>/dev/null || true)
echo "filesystem: ${FS:-unrecognised}"
echo

case "$FS" in
    ext2|ext3|ext4) e2fsck -fn "$VC_DEV"; rc=$? ;;
    exfat)          fsck.exfat  "$VC_DEV"; rc=$? ;;
    vfat)           fsck.vfat -n "$VC_DEV"; rc=$? ;;
    *)              echo "No checker for '${FS:-unrecognised}'."; exit 1 ;;
esac

echo
# e2fsck: 0 clean, 4 errors left uncorrected. fsck.exfat/fsck.vfat: 0 clean, 1 errors.
if (( rc == 0 )); then
    echo ">>> CLEAN — $FS on $PART is structurally valid after the run"
else
    echo ">>> NOT CLEAN — checker exited $rc. Do not dismiss this as a prep artefact"
    echo "    until the same drive checks clean without the change under test."
fi
exit $rc
