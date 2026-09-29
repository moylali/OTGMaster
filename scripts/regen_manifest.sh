#!/usr/bin/env bash
# Regenerate MANIFEST.txt on an already-prepared fixture drive, in the format the
# benchmark's `fixtures` section parses. Works on LUKS and VeraCrypt containers
# and on plain partitions, so every benchmark drive can be hash-checked — not
# just the ext4 ones.
#
# Usage:
#   sudo bash scripts/regen_manifest.sh /dev/sdX1 [password] [pim]
#
#   /dev/sdX1   the PARTITION, not the disk
#   password    default password123
#   pim         VeraCrypt only, default 1 (ignored for LUKS and plain)
#
# The container type is detected, not declared. Nothing is reformatted: this only
# rewrites BENCH/MANIFEST.txt.
#
# WHY THIS EXISTS
#
# A benchmark run reported "fixtures: nothing to check — manifest has no hashes"
# on the VeraCrypt+exFAT drive, so the one check that can see silent data
# corruption was doing nothing there. Only the drives built by the prepare_drive_*
# scripts carried hashes; the drives built by the older prepare_test_usb.sh
# carried a manifest that predates the format, and rebuilding a 60 GB drive just
# to get hashes is not a reasonable price.
#
# WHAT THE MANIFEST CAN AND CANNOT TELL YOU
#
# It records the drive as it is *now*. Run it when you have reason to believe the
# fixtures are good — a fresh preparation, or a volume that has just passed its
# filesystem checker — because regenerating it over a damaged drive blesses the
# damage as the new baseline. From then on it catches drift.
#
# The benchmark's write sections create their own scratch files and do not touch
# the fixture tree, so regenerating after a run is not in itself unsound; but a
# manifest generated after an unexplained failure proves nothing.

set -uo pipefail

PART="${1:?Usage: sudo bash $0 /dev/sdX1 [password] [pim]}"
PASS="${2:-password123}"
PIM="${3:-1}"

[[ $EUID -eq 0 ]] || { echo "ERROR: run with sudo."; exit 1; }
[[ -b "$PART" ]] || { echo "ERROR: $PART is not a block device."; exit 1; }

MAPPER=otgRegen
MNT=/mnt/otgRegen
OPENED=""          # luks | veracrypt | plain
TARGET=""

cleanup() {
    mountpoint -q "$MNT" 2>/dev/null && umount "$MNT"
    case "$OPENED" in
        luks)      cryptsetup close "$MAPPER" 2>/dev/null ;;
        veracrypt) veracrypt -t -d "$PART" --non-interactive >/dev/null 2>&1 ;;
    esac
}
trap cleanup EXIT

# ---------------------------------------------------------------------------
# Open whatever this is
# ---------------------------------------------------------------------------
if cryptsetup isLuks "$PART" 2>/dev/null; then
    echo "container: LUKS"
    echo -n "$PASS" | cryptsetup open "$PART" "$MAPPER" - || { echo "ERROR: LUKS open failed."; exit 1; }
    OPENED=luks
    TARGET="/dev/mapper/$MAPPER"
elif [[ -n "$(blkid -o value -s TYPE "$PART" 2>/dev/null)" ]]; then
    echo "container: none (plain partition)"
    OPENED=plain
    TARGET="$PART"
else
    # No LUKS magic and no recognisable filesystem — the shape of a VeraCrypt
    # volume, whose first sector is its encrypted header.
    echo "container: VeraCrypt (PIM $PIM)"
    veracrypt -t -d "$PART" --non-interactive >/dev/null 2>&1
    veracrypt -t --mount "$PART" \
        --password="$PASS" --pim="$PIM" --keyfiles="" \
        --protect-hidden=no --filesystem=none --non-interactive \
        || { echo "ERROR: VeraCrypt attach failed — wrong password/PIM?"; exit 1; }
    OPENED=veracrypt
    TARGET=$(veracrypt -t -l 2>/dev/null | awk -v p="$PART" '$2 == p { print $3 }')
    [[ -n "$TARGET" && -b "$TARGET" ]] || { echo "ERROR: attached but no device node."; exit 1; }
fi

FS=$(blkid -o value -s TYPE "$TARGET" 2>/dev/null || true)
echo "device:    $TARGET"
echo "filesystem: ${FS:-unrecognised}"

mkdir -p "$MNT"
mount "$TARGET" "$MNT" || { echo "ERROR: mount failed."; exit 1; }

B="$MNT/BENCH"
[[ -d "$B" ]] || { echo "ERROR: $B not found — is this a fixture drive?"; exit 1; }

# ---------------------------------------------------------------------------
# Write the manifest
# ---------------------------------------------------------------------------
# Format, one record per line, tab separated: path <TAB> size <TAB> sha256.
#
# The directory records are the fiddly part and the reason the old manifests were
# skipped rather than checked:
#   - the size field MUST end in "files". The benchmark refuses a directory line
#     whose size field says anything else, because the older format wrote
#     "10 levels" there with a *file's* hash beside it — comparing that against a
#     listing hash yields a mismatch that looks exactly like corruption and is not.
#   - the hash is over the sorted "name<TAB>size" listing, with `head -c -1`
#     stripping the trailing newline, because the benchmark joins the entries with
#     "\n" as a separator rather than a terminator.
echo
echo "Writing $B/MANIFEST.txt ..."
{
    echo "# OTG Master benchmark fixture"
    echo "# generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "# source:    $PART ($OPENED, $FS)"
    echo
    printf '# path\tbytes\tsha256\n'

    shopt -s nullglob
    for f in "$B"/large/*; do
        [[ -f "$f" ]] || continue
        printf 'large/%s\t%s\t%s\n' "$(basename "$f")" \
            "$(stat -c%s "$f")" "$(sha256sum "$f" | cut -d' ' -f1)"
    done

    for d in dense_short dense_lfn; do
        [[ -d "$B/$d" ]] || continue
        printf '%s/\t%s files\t%s\n' "$d" \
            "$(find "$B/$d" -maxdepth 1 -type f | wc -l | tr -d ' ')" \
            "$(cd "$B/$d" && for f in *; do
                if   [ -d "$f" ]; then printf '%s\tdir\n' "$f"
                elif [ -f "$f" ]; then printf '%s\t%s\n' "$f" "$(stat -c%s "$f")"
                fi
               done | LC_ALL=C sort | head -c -1 | sha256sum | cut -d' ' -f1)"
    done

    # Full relative path from BENCH/, so bench.search() can find it.
    if [[ -d "$B/nested" ]]; then
        find "$B/nested" -type f | while read -r L; do
            printf '%s\t%s\t%s\n' "${L#"$B"/}" \
                "$(stat -c%s "$L")" "$(sha256sum "$L" | cut -d' ' -f1)"
        done
    fi
} > "$B/MANIFEST.txt"

sync
echo
cat "$B/MANIFEST.txt"
echo
echo "=== Done. Re-run the benchmark's fixtures section against this drive. ==="
