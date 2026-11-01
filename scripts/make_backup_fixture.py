#!/usr/bin/env python3
"""Builds testdata/backup_mount: a repository made by desktop restic, on a plain drive.

E2EAutomatedTest.runBackupCase browses to it and mounts it. Desktop restic made it and no
job of the app knows it, so nothing about it can lean on what the app's own backups write
or on a saved password. See docs/TEST_DATA.md §6f.

    64 MiB plain FAT32
      otg_backup_repo/   restic format 2 (compressed), two snapshots
      not_a_backup/      a file named config, but no keys: not a repository

Snapshot 1 holds the usual mount tree and big.bin, large enough to span several blobs.
Snapshot 2 adds added_later.txt and changes one file, so each snapshot has to show its
own contents.

description.txt, plain.txt and backup.txt are committed. This writes the gitignored rest:
test.img, password.txt and backup_expected.txt, whose tab-separated lines the test reads:

    snapshot  <number>  <first 8 of its id>
    file      <number>  <sha256>  <path>
    range     <number>  <sha256>  <path>  <offset>  <length>

    python3 scripts/make_backup_fixture.py
"""

import hashlib
import json
import os
import random
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

from make_e2e_matrix import sh, tree, udisks_populate  # noqa: E402

SLOT_BYTES = 64 << 20
PASSWORD = "otg-backup-e2e-2026"
BIG_BYTES = 12 << 20
RANGE_OFFSET = 7 << 20
RANGE_LENGTH = 1 << 20


def find_restic():
    candidates = (
        os.environ.get("RESTIC"),
        shutil.which("restic"),
        os.path.expanduser("~/.local/bin/restic"),
    )
    for path in candidates:
        if path and os.access(path, os.X_OK):
            return path
    raise SystemExit(
        "restic not found: the backup_mount fixture has to be made by desktop restic. "
        "Install it or set RESTIC."
    )


def restic(binary, repo, *args, cwd=None):
    env = dict(os.environ, RESTIC_PASSWORD=PASSWORD, RESTIC_REPOSITORY=repo)
    p = subprocess.run(
        [binary, "--no-cache", *args],
        check=False,
        cwd=cwd,
        env=env,
        capture_output=True,
        text=True,
    )
    if p.returncode != 0:
        raise SystemExit(f"restic {' '.join(args)} failed:\n{p.stdout}{p.stderr}")
    return p.stdout


def backup(binary, repo, src, when):
    """Backs up src's contents, stored from the snapshot's top. Returns the snapshot id."""
    out = restic(binary, repo, "backup", ".", "--json", "--time", when, cwd=src)
    for line in out.splitlines():
        message = json.loads(line)
        if message.get("message_type") == "summary":
            return message["snapshot_id"]
    raise SystemExit(f"restic backup printed no summary:\n{out}")


def expected_lines(number, snapshot_id, src):
    lines = [f"snapshot\t{number}\t{snapshot_id[:8]}"]
    for folder, _, names in sorted(os.walk(src)):
        for name in sorted(names):
            path = os.path.join(folder, name)
            with open(path, "rb") as f:
                digest = hashlib.sha256(f.read()).hexdigest()
            lines.append(f"file\t{number}\t{digest}\t{os.path.relpath(path, src)}")
    with open(os.path.join(src, "big.bin"), "rb") as f:
        f.seek(RANGE_OFFSET)
        digest = hashlib.sha256(f.read(RANGE_LENGTH)).hexdigest()
    lines.append(f"range\t{number}\t{digest}\tbig.bin\t{RANGE_OFFSET}\t{RANGE_LENGTH}")
    return lines


def main():
    binary = find_restic()
    case_dir = os.path.join(ROOT, "testdata", "backup_mount")
    with tempfile.TemporaryDirectory() as work:
        src = os.path.join(work, "source")
        tree(src)
        with open(os.path.join(src, "big.bin"), "wb") as f:
            f.write(random.Random(2026).randbytes(BIG_BYTES))

        drive = os.path.join(work, "drive")
        repo = os.path.join(drive, "otg_backup_repo")
        os.makedirs(os.path.join(drive, "not_a_backup"))
        for name, text in (
            ("config", "A file named config does not make a repository.\n"),
            ("readme.txt", "No restic repository here.\n"),
        ):
            with open(os.path.join(drive, "not_a_backup", name), "w") as f:
                f.write(text)

        restic(binary, repo, "init", "--repository-version", "2")
        first = backup(binary, repo, src, "2026-01-01 10:00:00")
        lines = expected_lines(1, first, src)

        with open(os.path.join(src, "added_later.txt"), "w") as f:
            f.write("Only in the second snapshot.\n")
        with open(os.path.join(src, "file with spaces.txt"), "w") as f:
            f.write("Changed before the second snapshot.\n")
        second = backup(binary, repo, src, "2026-01-01 11:00:00")
        lines += expected_lines(2, second, src)
        restic(binary, repo, "check", "--read-data")

        img = os.path.join(case_dir, "test.img")
        with open(img, "wb") as f:
            f.truncate(SLOT_BYTES)
        sh("mkfs.fat", "-F", "32", "-n", "OTGBACKUP", img)
        udisks_populate(img, drive)

    with open(os.path.join(case_dir, "password.txt"), "w") as f:
        f.write(PASSWORD + "\n")
    with open(os.path.join(case_dir, "backup_expected.txt"), "w") as f:
        f.write("\n".join(lines) + "\n")
    print(f"backup_mount: snapshots {first[:8]} and {second[:8]} on plain FAT32")


if __name__ == "__main__":
    main()
