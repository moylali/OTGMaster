#!/usr/bin/env bash
# Record what a volume contains, then prove afterwards that nothing changed
# except what was supposed to. READ-ONLY throughout.
#
# Usage:
#   sudo bash scripts/volume_baseline.sh snapshot /dev/sdX1 [options]
#   sudo bash scripts/volume_baseline.sh compare  /dev/sdX1 [options]
#
# Options:
#   --name NAME        baseline directory name (default: the volume label)
#   --password PASS    container password (default: password123)
#   --pim N            VeraCrypt PIM (default: 1)
#   --store DIR        where baselines live (default: baselines/ in the workspace)
#   --metadata-only    compare only the allocation tables, not file contents
#
# The two halves cost very different amounts and catch different things:
#
#   metadata   seconds. Copies and diffs the boot region and FAT. This is what
#              would have caught the FAT32 damage on the first run — 131,600
#              changed entries against a run that wrote about 20 MB is not a
#              judgement call.
#   contents   minutes, because it reads every byte — roughly 10 on a 54 GB
#              drive over USB 3. Catches the case metadata cannot: correct
#              structure holding wrong bytes.
#
# So --metadata-only is the routine check after each run, and the full compare is
# for preparing a drive or for when something already looks wrong. snapshot
# always captures both, so the choice is only ever made at compare time.
#
# ---------------------------------------------------------------------------
# WHY
#
# The FAT32 corruption found on 2026-09-28 was provable only because FAT32 keeps
# two copies of its allocation table and libaums writes just one — so an untouched
# FAT[1] survived to compare against. That was luck, not design. exFAT has a
# single FAT and ext4 has no mirrored bitmap, so the same damage on either would
# have left nothing to diff.
#
# This supplies the missing copy externally: take the baseline before the run,
# compare after. It is filesystem-agnostic, and for FAT32 it is strictly better
# than the FAT[1] trick — which disappears the moment the mirroring bug is fixed.
#
# WHAT IT CATCHES THAT THE EXISTING CHECKS DO NOT
#
#   fsck        structure only. No filesystem here carries data checksums, so a
#               cluster holding the wrong bytes in a structurally valid chain is
#               invisible to e2fsck, fsck.exfat and fsck.vfat alike.
#   fixtures    contents, but only for manifest entries — 14 files of 20,096 on
#               the exFAT drive. The FAT32 damage was in FILL/fill_0023.bin,
#               which no entry covered, so "fixtures: ALL 16 MATCHED" was both
#               true and worthless.
#
# This hashes every file, so there is no unmanifested corner to hide in, and it
# copies the raw metadata regions so allocation-table damage shows up as bytes
# rather than as a downstream symptom.
#
# WHAT IT CANNOT DO
#
# It cannot tell you damage is absent from a volume it has never seen before. A
# baseline taken after the damage records the damage as normal. Snapshot when you
# have reason to believe the volume is good — freshly prepared, or just checked.

set -uo pipefail

usage() {
    echo "Usage: sudo bash $0 snapshot|compare /dev/sdX1 [options]" >&2
    echo "  --name NAME  --password PASS  --pim N  --store DIR  --metadata-only" >&2
    exit 1
}

# Checked rather than expanded with ${1:?...}: that form ends at the first '}',
# so a usage string containing braces silently appends its own tail to the
# variable — "/dev/sdd1 /dev/sdX1 [options]} is not a block device".
[[ $# -ge 2 ]] || usage
MODE="$1"; shift
PART="$1"; shift

NAME=""
PASS=password123
PIM=1
# In the workspace rather than /var/lib. These are evidence, not system state:
# they are the reference a later comparison is judged against, so they should sit
# with the rest of the project's evidence and survive this machine. The directory
# is gitignored for now — the raw FAT copies are 57 MB each and have no business
# in git, while the hash lists are small and arguably should be committed. That
# split is not decided yet.
#
# Deliberately NOT stored on the drive itself: a volume that corrupts its own
# data can corrupt its own baseline, which is exactly how the FAT32 drive kept a
# valid-looking MANIFEST.txt while its allocation table was wrecked.
STORE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/baselines"
META_ONLY=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --name)     NAME="$2"; shift 2 ;;
        --password) PASS="$2"; shift 2 ;;
        --pim)      PIM="$2";  shift 2 ;;
        --store)    STORE="$2"; shift 2 ;;
        --metadata-only) META_ONLY=1; shift ;;
        *) echo "unknown option: $1" >&2; exit 1 ;;
    esac
done

die() { echo "ERROR: $*" >&2; exit 1; }
[[ $EUID -eq 0 ]] || die "run with sudo."
[[ -b "$PART" ]] || die "$PART is not a block device."
[[ "$MODE" == "snapshot" || "$MODE" == "compare" ]] || usage

# Per-invocation names. These were fixed strings, which made two concurrent runs
# collide on one mount point: Linux stacks mounts rather than refusing, so a
# second run mounted its volume over the first's and its cleanup then popped the
# mount out from under a job still walking that directory. The visible symptom
# was "umount: /mnt/otgBaseline: target is busy"; the invisible one was a
# baseline that may have hashed files from the wrong volume.
MNT=/mnt/otgBaseline.$$
MAPPER=otgBaseline_$$
OPENED=""
DEV=""

cleanup() {
    mountpoint -q "$MNT" 2>/dev/null && umount "$MNT"
    [[ -d "$MNT" ]] && rmdir "$MNT" 2>/dev/null
    case "$OPENED" in
        luks)      cryptsetup close "$MAPPER" 2>/dev/null ;;
        veracrypt) veracrypt -t -d "$PART" --non-interactive >/dev/null 2>&1 ;;
    esac
    return 0
}
trap cleanup EXIT

# ---------------------------------------------------------------------------
# Open whatever container this is
# ---------------------------------------------------------------------------
if cryptsetup isLuks "$PART" 2>/dev/null; then
    OPENED=luks
    echo -n "$PASS" | cryptsetup open "$PART" "$MAPPER" - || die "LUKS open failed."
    DEV="/dev/mapper/$MAPPER"
elif [[ -n "$(blkid -o value -s TYPE "$PART" 2>/dev/null)" ]]; then
    OPENED=plain
    DEV="$PART"
else
    OPENED=veracrypt
    veracrypt -t -d "$PART" --non-interactive >/dev/null 2>&1
    veracrypt -t --mount "$PART" --password="$PASS" --pim="$PIM" --keyfiles="" \
        --protect-hidden=no --filesystem=none --non-interactive \
        || die "VeraCrypt attach failed."
    DEV=$(veracrypt -t -l 2>/dev/null | awk -v p="$PART" '$2 == p { print $3 }')
fi
[[ -b "$DEV" ]] || die "opened but no device node."

FS=$(blkid -o value -s TYPE "$DEV" 2>/dev/null || true)
LABEL=$(blkid -o value -s LABEL "$DEV" 2>/dev/null || true)
[[ -n "$NAME" ]] || NAME="${LABEL:-$(basename "$PART")}"
DIR="$STORE/$NAME"

echo "device     : $DEV  ($OPENED)"
echo "filesystem : ${FS:-unrecognised}"
echo "baseline   : $DIR"
echo

# ---------------------------------------------------------------------------
# Raw metadata regions. Copying bytes rather than interpreting them, so a
# difference is a fact rather than a reading of one.
# ---------------------------------------------------------------------------
dump_metadata() {
    local out=$1
    mkdir -p "$out"
    case "$FS" in
    vfat)
        # bytes/sector @11, reserved @14, FAT count @16, sectors/FAT @36 (FAT32).
        local bps resv nfat spf
        bps=$(od -An -tu2 -j11 -N2 "$DEV" | tr -d ' ')
        resv=$(od -An -tu2 -j14 -N2 "$DEV" | tr -d ' ')
        nfat=$(od -An -tu1 -j16 -N1 "$DEV" | tr -d ' ')
        spf=$(od -An -tu4 -j36 -N4 "$DEV" | tr -d ' ')
        echo "bytes_per_sector=$bps reserved=$resv fat_count=$nfat sectors_per_fat=$spf" \
            > "$out/geometry.txt"
        dd if="$DEV" of="$out/reserved.bin" bs=1 count=$((resv * bps)) status=none
        local i
        for ((i = 0; i < nfat; i++)); do
            dd if="$DEV" of="$out/fat$i.bin" bs=1M iflag=skip_bytes,count_bytes \
               skip=$(( (resv + i * spf) * bps )) count=$(( spf * bps )) status=none
        done
        ;;
    exfat)
        # FatOffset @80 (sectors), FatLength @84, BytesPerSectorShift @108.
        local shift bps fatoff fatlen
        shift=$(od -An -tu1 -j108 -N1 "$DEV" | tr -d ' ')
        bps=$((1 << shift))
        fatoff=$(od -An -tu4 -j80 -N4 "$DEV" | tr -d ' ')
        fatlen=$(od -An -tu4 -j84 -N4 "$DEV" | tr -d ' ')
        echo "bytes_per_sector=$bps fat_offset=$fatoff fat_length=$fatlen" \
            > "$out/geometry.txt"
        # Main and backup boot regions, 12 sectors each.
        dd if="$DEV" of="$out/bootregion.bin" bs=1 count=$((24 * bps)) status=none
        dd if="$DEV" of="$out/fat0.bin" bs=1M iflag=skip_bytes,count_bytes \
           skip=$((fatoff * bps)) count=$((fatlen * bps)) status=none
        ;;
    ext2|ext3|ext4)
        # The group descriptors are not one contiguous run across flex groups, so
        # dumpe2fs' own rendering is the honest thing to diff. The superblock is
        # copied raw as well.
        dd if="$DEV" of="$out/superblock.bin" bs=1 skip=1024 count=1024 status=none
        dumpe2fs "$DEV" 2>/dev/null > "$out/dumpe2fs.txt" || true
        ;;
    *)
        echo "no metadata capture for '${FS:-unrecognised}'" > "$out/geometry.txt"
        ;;
    esac
}

# ---------------------------------------------------------------------------
# Contents. Every file, not a sample — that is the whole point.
# ---------------------------------------------------------------------------
dump_contents() {
    local out=$1
    mkdir -p "$MNT"
    mount -o ro "$DEV" "$MNT" || die "read-only mount failed."
    ( cd "$MNT" && find . -mindepth 1 -printf '%y\t%s\t%p\n' | LC_ALL=C sort ) > "$out/tree.txt"
    ( cd "$MNT" && find . -type f -print0 | LC_ALL=C sort -z \
        | xargs -0 -r sha256sum ) > "$out/files.sha256"
    umount "$MNT"
    echo "  files hashed: $(wc -l < "$out/files.sha256")"
    echo "  tree entries: $(wc -l < "$out/tree.txt")"
}

if [[ "$MODE" == "snapshot" ]]; then
    [[ -e "$DIR" ]] && die "$DIR already exists — move or remove it first, so a
       baseline is never silently replaced by a later, possibly damaged, state."
    mkdir -p "$DIR"
    {
        echo "taken:      $(date -u +%Y-%m-%dT%H:%M:%SZ)"
        echo "partition:  $PART"
        echo "container:  $OPENED"
        echo "filesystem: $FS"
        echo "label:      $LABEL"
    } > "$DIR/meta.txt"
    echo "Capturing metadata regions..."; dump_metadata "$DIR/meta"
    echo "Hashing every file..."; dump_contents "$DIR"
    echo
    echo ">>> baseline stored at $DIR"
    exit 0
fi

# --- compare ---------------------------------------------------------------
[[ -d "$DIR" ]] || die "no baseline at $DIR — take one with 'snapshot' first."
NEW=$(mktemp -d)
echo "Capturing current state..."
dump_metadata "$NEW/meta"
(( META_ONLY == 0 )) && dump_contents "$NEW"
echo

CHANGED=0

if (( META_ONLY == 1 )); then
    echo "=== contents ==="
    echo "  SKIPPED (--metadata-only). File contents were not read, so a cluster"
    echo "  holding the wrong bytes inside a valid chain would not be seen here."
else
echo "=== contents ==="
# Paths present in one side only, and paths whose hash moved.
awk '{ h=$1; $1=""; sub(/^[[:space:]]+/,""); print $0"\t"h }' "$DIR/files.sha256" | LC_ALL=C sort > "$NEW/old.tsv"
awk '{ h=$1; $1=""; sub(/^[[:space:]]+/,""); print $0"\t"h }' "$NEW/files.sha256"  | LC_ALL=C sort > "$NEW/new.tsv"
cut -f1 "$NEW/old.tsv" > "$NEW/old.paths"; cut -f1 "$NEW/new.tsv" > "$NEW/new.paths"

REMOVED=$(LC_ALL=C comm -23 "$NEW/old.paths" "$NEW/new.paths" | wc -l)
ADDED=$(LC_ALL=C comm -13 "$NEW/old.paths" "$NEW/new.paths" | wc -l)
MODIFIED=$(LC_ALL=C join -t$'\t' "$NEW/old.tsv" "$NEW/new.tsv" 2>/dev/null \
           | awk -F'\t' '$2 != $3' | wc -l)

printf '  removed : %s\n  added   : %s\n  modified: %s\n' "$REMOVED" "$ADDED" "$MODIFIED"

# The benchmark writes its report and appends to the index on every run, so those
# two paths changing is the expected outcome, not a finding. Reporting them under
# the same heading as real surprises trains the reader to skim the heading —
# which is the failure mode this whole tool exists to avoid.
expected_path() {
    case "$1" in
        ./BENCH/reports/*|./BENCH_UNALIGNED*|./BENCH/bench_*|./otgbench-*) return 0 ;;
        *) return 1 ;;
    esac
}

if (( MODIFIED > 0 )); then
    MOD_LIST=$(LC_ALL=C join -t$'\t' "$NEW/old.tsv" "$NEW/new.tsv" 2>/dev/null \
        | awk -F'\t' '$2 != $3 { print $1 }')
    UNEXPECTED=""
    EXPECTED=""
    while IFS= read -r f; do
        [[ -z "$f" ]] && continue
        if expected_path "$f"; then EXPECTED+="$f"$'\n'; else UNEXPECTED+="$f"$'\n'; fi
    done <<< "$MOD_LIST"

    if [[ -n "$EXPECTED" ]]; then
        echo "  modified, and expected to be — the runner writes these:"
        printf '%s' "$EXPECTED" | head -20 | sed 's/^/      /'
    fi
    if [[ -n "$UNEXPECTED" ]]; then
        echo
        echo "  *** MODIFIED AND NOT EXPLAINED BY THE RUN — this is the serious one."
        echo "      A file the benchmark does not write must not have different bytes"
        echo "      afterwards. This is the shape the FAT32 corruption had:"
        printf '%s' "$UNEXPECTED" | head -40 | sed 's/^/      /'
        CHANGED=1
    fi
fi
if (( REMOVED > 0 )); then
    echo "  removed paths:"; LC_ALL=C comm -23 "$NEW/old.paths" "$NEW/new.paths" | head -20 | sed 's/^/      /'
fi
if (( ADDED > 0 )); then
    echo "  added paths:"
    LC_ALL=C comm -13 "$NEW/old.paths" "$NEW/new.paths" | while IFS= read -r f; do
        if expected_path "$f"; then echo "      $f   (expected — runner output)"
        else echo "      $f"; CHANGED=1; fi
    done
fi
fi

echo
echo "=== metadata regions ==="
for f in "$DIR"/meta/*.bin; do
    [[ -e "$f" ]] || continue
    b=$(basename "$f")
    if [[ ! -e "$NEW/meta/$b" ]]; then
        echo "  $b: MISSING now"; CHANGED=1; continue
    fi
    if cmp -s "$f" "$NEW/meta/$b"; then
        echo "  $b: identical"
    else
        n=$(cmp -l "$f" "$NEW/meta/$b" 2>/dev/null | wc -l)
        sz=$(stat -c%s "$f")
        echo "  $b: $n of $sz bytes differ (~$((n / 4)) 4-byte entries)"
        echo "      first difference: $(cmp "$f" "$NEW/meta/$b" 2>&1 | head -1)"
        CHANGED=1
    fi
done
if [[ -e "$DIR/meta/dumpe2fs.txt" ]]; then
    if diff -q "$DIR/meta/dumpe2fs.txt" "$NEW/meta/dumpe2fs.txt" >/dev/null 2>&1; then
        echo "  dumpe2fs: identical"
    else
        echo "  dumpe2fs: differs"
        diff "$DIR/meta/dumpe2fs.txt" "$NEW/meta/dumpe2fs.txt" | head -20 | sed 's/^/      /'
        CHANGED=1
    fi
fi

echo
echo "Allocation-table churn is expected in proportion to what the run wrote."
echo "A run that wrote tens of megabytes changing hundreds of thousands of FAT"
echo "entries is the signal this tool exists to surface."
echo
if (( CHANGED == 0 )); then
    if (( META_ONLY == 1 )); then
        echo ">>> NO METADATA CHANGE — allocation tables byte-identical. File"
        echo "    contents were not checked; run without --metadata-only for that."
    else
        echo ">>> NO CHANGE — every file and every metadata region is byte-identical."
    fi
else
    echo ">>> CHANGES FOUND — see above. Judge each against what the run should"
    echo "    have written; do not assume churn is benign because fsck is happy."
fi
rm -rf "$NEW"
exit $CHANGED
