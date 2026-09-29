#!/usr/bin/env bash
# Drive D — post-run structural check. READ-ONLY: nothing here writes to the card.
#
# Usage: sudo bash scripts/verify_drive_d.sh /dev/sdX
#
# Why this exists: a SHA-256 over the bytes just written cannot see a corrupt
# filesystem. The ext4 write path once reported "write verify: ALL PASSED" with
# matching hashes on all three passes while every superblock, inode, bitmap and
# directory checksum it wrote was invalid. Only the filesystem's own checker
# catches that, and the device path goes through a crypto layer the host unit
# tests cannot reach — so the volume gets checked from here after a device run.
#
# e2fsck -fn: -f forces a full check even if the superblock says clean, -n
# answers "no" to every repair prompt and opens read-only.

set -uo pipefail

DEV="${1:?Usage: sudo bash $0 /dev/sdX}"
VC_PASS=password123
VC_PIM=1

[[ $EUID -eq 0 ]] || { echo "ERROR: run with sudo."; exit 1; }

FAILED=0

echo "=== p2: plain ext4 (${DEV}2) ==="
umount "${DEV}2" 2>/dev/null
e2fsck -fn "${DEV}2"
rc=$?
# e2fsck: 0 = clean, 1 = errors corrected (impossible under -n), 4 = errors left.
(( rc == 0 )) && echo ">>> p2 CLEAN" || { echo ">>> p2 NOT CLEAN (e2fsck exit $rc)"; FAILED=1; }

echo
echo "=== p1: ext4 inside VeraCrypt (${DEV}1) ==="
veracrypt -t -d "${DEV}1" --non-interactive >/dev/null 2>&1
if veracrypt -t --mount "${DEV}1" \
        --password="$VC_PASS" --pim="$VC_PIM" --keyfiles="" \
        --protect-hidden=no --filesystem=none --non-interactive; then
    VC_DEV=$(veracrypt -t -l 2>/dev/null | awk -v p="${DEV}1" '$2 == p { print $3 }')
    if [[ -n "$VC_DEV" && -b "$VC_DEV" ]]; then
        echo "decrypted device: $VC_DEV"
        e2fsck -fn "$VC_DEV"
        rc=$?
        (( rc == 0 )) && echo ">>> p1 CLEAN" || { echo ">>> p1 NOT CLEAN (e2fsck exit $rc)"; FAILED=1; }
    else
        echo ">>> p1 SKIPPED — attached but no device node reported"; FAILED=1
    fi
    veracrypt -t -d "${DEV}1" --non-interactive >/dev/null 2>&1
else
    echo ">>> p1 SKIPPED — could not attach the VeraCrypt volume"; FAILED=1
fi

echo
echo "=== p3: NTFS (${DEV}3) — expected untouched, app must refuse it ==="
# The desktop auto-mounts NTFS read-write, and ntfsfix refuses a mounted device.
umount "${DEV}3" 2>/dev/null
ntfsfix -n "${DEV}3" 2>&1 | tail -3

echo
(( FAILED == 0 )) && echo "=== ALL EXT4 VOLUMES CLEAN ===" || echo "=== SOMETHING IS NOT CLEAN — see above ==="
exit $FAILED
