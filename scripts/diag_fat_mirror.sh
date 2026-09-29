#!/usr/bin/env bash
# READ-ONLY diagnostic: are the two FAT copies on a FAT32 volume identical?
#
# Usage: sudo bash scripts/diag_fat_mirror.sh /dev/sdX1 [password] [pim]
#
# fsck.fat reports "FATs differ" but not where or by how much, and -F NUM did not
# change its analysis, so this reads the boot sector, locates both FAT copies and
# compares them byte for byte. Nothing is written: the volume is attached with
# --filesystem=none, both FATs are copied to a scratch directory, and the volume
# is detached from an EXIT trap.

set -uo pipefail

PART="${1:?Usage: sudo bash $0 /dev/sdX1 [password] [pim]}"
PASS="${2:-password123}"
PIM="${3:-1}"
OUT=$(mktemp -d)

[[ $EUID -eq 0 ]] || { echo "ERROR: run with sudo."; exit 1; }

cleanup() { veracrypt -t -d "$PART" --non-interactive >/dev/null 2>&1 || true; }
trap cleanup EXIT

veracrypt -t -d "$PART" --non-interactive >/dev/null 2>&1
veracrypt -t --mount "$PART" --password="$PASS" --pim="$PIM" --keyfiles="" \
    --protect-hidden=no --filesystem=none --non-interactive \
    || { echo "ERROR: could not attach $PART"; exit 1; }

DEV=$(veracrypt -t -l 2>/dev/null | awk -v p="$PART" '$2 == p { print $3 }')
[[ -b "$DEV" ]] || { echo "ERROR: no device node"; exit 1; }
echo "decrypted device: $DEV"

rd() { dd if="$DEV" bs=1 skip="$1" count="$2" status=none | xxd -p; }
le16() { local h; h=$(rd "$1" 2); echo $((16#${h:2:2}${h:0:2})); }
le32() { local h; h=$(rd "$1" 4); echo $((16#${h:6:2}${h:4:2}${h:2:2}${h:0:2})); }

BPS=$(le16 11)          # bytes per sector
RESV=$(le16 14)         # reserved sectors
NFAT=$((16#$(rd 16 1))) # number of FATs
SPF=$(le32 36)          # sectors per FAT (FAT32)

echo "bytes/sector      : $BPS"
echo "reserved sectors  : $RESV"
echo "number of FATs    : $NFAT"
echo "sectors per FAT   : $SPF"

(( NFAT >= 2 )) || { echo "Only $NFAT FAT — nothing to mirror."; exit 0; }

FAT_BYTES=$(( SPF * BPS ))
FAT0=$(( RESV * BPS ))
FAT1=$(( FAT0 + FAT_BYTES ))
echo "FAT size          : $FAT_BYTES bytes ($((FAT_BYTES/1024/1024)) MiB)"
echo "FAT[0] at         : $FAT0"
echo "FAT[1] at         : $FAT1"
echo

echo "Extracting both FATs to $OUT ..."
dd if="$DEV" of="$OUT/fat0.bin" bs=1M iflag=skip_bytes,count_bytes \
   skip="$FAT0" count="$FAT_BYTES" status=none
dd if="$DEV" of="$OUT/fat1.bin" bs=1M iflag=skip_bytes,count_bytes \
   skip="$FAT1" count="$FAT_BYTES" status=none

echo
if cmp -s "$OUT/fat0.bin" "$OUT/fat1.bin"; then
    echo ">>> IDENTICAL — both FATs match. The mirror is being maintained."
else
    echo ">>> THEY DIFFER."
    echo "first difference: $(cmp "$OUT/fat0.bin" "$OUT/fat1.bin" 2>&1 | head -1)"
    DIFFB=$(cmp -l "$OUT/fat0.bin" "$OUT/fat1.bin" 2>/dev/null | wc -l)
    echo "differing bytes : $DIFFB of $FAT_BYTES"
    echo "differing 4-byte entries (approx): $(( DIFFB / 4 ))"
    echo
    echo "A stale mirror should differ only where clusters were allocated since"
    echo "the volume was formatted. A FAT[0] that is itself damaged would differ"
    echo "in ways that do not correspond to any allocation."
fi
echo
echo "kept for inspection: $OUT/fat0.bin $OUT/fat1.bin"
