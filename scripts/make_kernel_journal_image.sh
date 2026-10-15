#!/usr/bin/env bash
# Builds an ext4 image whose journal the Linux kernel wrote and never replayed, without
# root: mkfs, a udisks loop mount, some writes, a sync, and a copy of the image taken
# while it is still mounted. The copy is flagged needs_recovery, the state a drive is in
# when it is pulled, or when Android's own mount is cut off (docs/TEST_DATA.md §6e).
#
#   bash scripts/make_kernel_journal_image.sh OUT.img [SIZE_MiB]
#   OTG_EXT4_JOURNAL_IMAGE=OUT.img ./gradlew :app:testDebugUnitTest \
#       --tests 'app.fayaz.otgmaster.ext4.Ext4JournalReplayTest'
set -euo pipefail
OUT=${1:?usage: $0 OUT.img [SIZE_MiB]}
SIZE=${2:-256}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
IMG="$WORK/live.img"
truncate -s "${SIZE}M" "$IMG"
mkfs.ext4 -q -F -m 0 -E "lazy_itable_init=0,lazy_journal_init=0,root_owner=$(id -u):$(id -g)" "$IMG"
DEV=$(udisksctl loop-setup -f "$IMG" --no-user-interaction | grep -oE '/dev/loop[0-9]+')
cleanup() {
    udisksctl unmount -b "$DEV" --no-user-interaction >/dev/null 2>&1 || true
    udisksctl loop-delete -b "$DEV" --no-user-interaction >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT
MNT=$(udisksctl mount -b "$DEV" --no-user-interaction | sed -E 's/.* at (.+)$/\1/; s/\.$//')
# Several syncs, so several transactions; deleting whole directories frees metadata
# blocks, which the kernel then revokes.
for round in 1 2 3; do
    mkdir -p "$MNT/r$round/deep/er"
    for i in $(seq 1 40); do head -c $((i * 9000 + round)) /dev/urandom > "$MNT/r$round/deep/f$i.bin"; done
    for i in $(seq 1 200); do echo "$round $i" > "$MNT/r$round/deep/er/s$i.txt"; done
    sync
    [ "$round" -gt 1 ] && rm -rf "$MNT/r$((round - 1))/deep/er"
    mv "$MNT/r$round/deep/f1.bin" "$MNT/moved$round.bin"
    sync
done
echo hello > "$MNT/hello.txt"
sync
cp "$IMG" "$OUT"
dumpe2fs -h "$OUT" 2>/dev/null | grep -q needs_recovery || { echo "the copy does not need recovery"; exit 1; }
echo "$OUT: $(debugfs -R logdump "$OUT" 2>/dev/null | grep -c 'commit block') committed transaction(s), needs_recovery"
