#!/usr/bin/env python3
"""
Builds the E2E case for repairing an ext4 drive whose journal needs recovery
(E2EAutomatedTest.runJournalRepairCase), without root:

  ext4_journal   plain ext4, 64 MiB, 4 KiB blocks, the usual mount tree plus
                 journal_replayed.txt, and a checksummed (CSUM_V3) journal holding
                 three committed transactions, flagged needs_recovery:
                   1. the block of journal_replayed.txt, "REPLAYED: no " -> "REPLAYED: yes";
                   2. a free block filled with a pattern;
                   3. a revoke of that free block.
                 So after a correct replay the file says yes and the free block is
                 still zero, and a replay that ignores revokes is caught.

debugfs writes the transactions (jo -c / jw / jc) the way a cut-off mount leaves
them; it is the e2fsprogs journal code, the same recovery.c the kernel uses. This
is the state Android leaves a plain ext4 drive in when it has mounted it and OTG
Master then claims the USB device (docs/TEST_DATA.md §6e).

description.txt, plain.txt and journal.txt are committed; this writes the gitignored
test.img and password.txt.

    python3 scripts/make_journal_fixture.py
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

from make_e2e_matrix import sh, tree  # noqa: E402

SLOT_BYTES = 64 << 20
CASE = "ext4_journal"
BEFORE = b"REPLAYED: no \n"
AFTER = b"REPLAYED: yes\n"


def debugfs(img, commands):
    p = subprocess.run(["debugfs", "-w", "-f", "-", img], input=commands,
                       capture_output=True, text=True)
    bad = [l for l in p.stdout.splitlines() if re.match(r"\s*(journal_\w+|jw|jo|jc):", l)]
    if p.returncode != 0 or bad:
        raise SystemExit(f"debugfs failed:\n{p.stdout}{p.stderr}")


def query(img, request):
    return subprocess.run(["debugfs", "-R", request, img], capture_output=True, text=True).stdout


def main():
    case_dir = os.path.join(ROOT, "testdata", CASE)
    img = os.path.join(case_dir, "test.img")
    with tempfile.TemporaryDirectory() as work:
        src = os.path.join(work, "tree")
        tree(src)
        with open(os.path.join(src, "journal_replayed.txt"), "wb") as f:
            f.write(BEFORE)
        with open(img, "wb") as f:
            f.truncate(SLOT_BYTES)
        sh("mkfs.ext4", "-q", "-F", "-m", "0", "-b", "4096", "-L", "OTGJOURNAL",
           "-E", "lazy_itable_init=0,lazy_journal_init=0", "-d", src, img)

        home = int(query(img, "bmap /journal_replayed.txt 0").split()[-1])
        # A block nothing uses: the last one, checked rather than assumed.
        free = SLOT_BYTES // 4096 - 1
        if "not in use" not in query(img, f"testb {free}"):
            raise SystemExit(f"block {free} is in use")

        after = os.path.join(work, "after.bin")
        with open(after, "wb") as f:
            f.write(AFTER + bytes(4096 - len(AFTER)))
        pattern = os.path.join(work, "pattern.bin")
        with open(pattern, "wb") as f:
            f.write(bytes((i * 7 + 1) & 0xFF for i in range(4096)))
        debugfs(img, f"jo -c\njw -b {home} {after}\njw -b {free} {pattern}\njw -r {free} /dev/null\njc\n")

    features = sh("dumpe2fs", "-h", img)
    if "needs_recovery" not in features or "journal_checksum_v3" not in features:
        raise SystemExit(f"the journal was not left needing recovery:\n{features}")
    with open(os.path.join(case_dir, "password.txt"), "w") as f:
        f.write("unused\n")
    with open(os.path.join(case_dir, "journal_blocks.txt"), "w") as f:
        f.write(f"home={home}\nrevoked={free}\n")
    print(f"{CASE}: plain ext4, journal needs recovery (file block {home}, revoked block {free})")


if __name__ == "__main__":
    main()
