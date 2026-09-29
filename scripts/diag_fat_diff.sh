#!/usr/bin/env bash
# READ-ONLY: characterise how two extracted FAT images differ.
#
# Usage: sudo bash scripts/diag_fat_diff.sh /path/fat0.bin /path/fat1.bin
#
# Answers the two questions a raw "they differ" cannot:
#   - are the reserved entries (0 and 1) the same? Those are the media
#     descriptor and the dirty/clean-shutdown flags. No allocation touches them,
#     so a difference there is not explained by a stale mirror.
#   - are the differences spread across the whole table, or confined to a region?
#     Churn from allocation clusters; damage does not have to.

set -uo pipefail
F0="${1:?usage: sudo bash $0 fat0.bin fat1.bin}"
F1="${2:?usage: sudo bash $0 fat0.bin fat1.bin}"

echo "=== reserved entries (first 16 bytes = FAT entries 0-3) ==="
printf 'FAT[0]: '; xxd -l 16 -g 4 "$F0" | cut -d' ' -f2-5
printf 'FAT[1]: '; xxd -l 16 -g 4 "$F1" | cut -d' ' -f2-5
echo
echo "entry 0 should be 0x0FFFFFF8 (media descriptor F8), entry 1 an EOC value"
echo "whose top bits carry the clean-shutdown and hard-error flags."
echo
echo "=== where the differences are ==="
SIZE=$(stat -c%s "$F0")
echo "FAT size: $SIZE bytes, $(( SIZE / 4 )) entries"
echo
echo "differing byte offsets, bucketed into 16 equal spans:"
cmp -l "$F0" "$F1" 2>/dev/null | awk -v sz="$SIZE" '
    { b = int(($1 - 1) * 16 / sz); count[b]++ }
    END {
        for (i = 0; i < 16; i++) {
            lo = int(i * sz / 16 / 4); hi = int((i+1) * sz / 16 / 4)
            printf "  entries %10d-%-10d : %s\n", lo, hi, (count[i] ? count[i] " bytes" : "-")
        }
    }'
echo
echo "=== first 5 differing entries, decoded ==="
cmp -l "$F0" "$F1" 2>/dev/null | head -20 | awk '{ print int(($1 - 1) / 4) }' | sort -un | head -5 | while read -r e; do
    off=$(( e * 4 ))
    v0=$(xxd -s "$off" -l 4 -e -g 4 "$F0" | awk '{print $2}')
    v1=$(xxd -s "$off" -l 4 -e -g 4 "$F1" | awk '{print $2}')
    printf '  entry %-10d FAT[0]=0x%s  FAT[1]=0x%s\n' "$e" "$v0" "$v1"
done
