#!/usr/bin/env python3
"""
Checks the APFS fixtures made by scripts/make_apfs_images_macos.sh, on Linux,
with implementations independent of Apple's and of the app's:

  - apfsck (linux-apfs/apfsprogs) on the APFS container — structure;
  - apfs-fuse (sgan81/apfs-fuse), mounted as this user and given the password for
    the encrypted volumes — the files: flower.jpg byte for byte, the other fixture
    files, and case sensitivity (case.txt / CASE.txt are two files on a
    case-sensitive volume and one on an insensitive one).

    APFSCK=/path/to/apfsck APFS_FUSE=/path/to/apfs-fuse \\
        python3 scripts/verify_apfs_images.py [testdata/apfs/apfs_ci ...]

Neither tool is packaged for Ubuntu; both build without root:
  apfsprogs:  make -C apfsck   (and -C mkapfs)
  apfs-fuse:  needs libfuse3-dev; cmake .. -DUSE_FUSE3=ON with CXXFLAGS="-include cstdint"
"""
import glob
import hashlib
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import uuid

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APFS_PART_TYPE = uuid.UUID("7C3457EF-0000-11AA-AA11-00306543ECAC").bytes_le
APFSCK = os.environ.get("APFSCK") or shutil.which("apfsck")
APFS_FUSE = os.environ.get("APFS_FUSE") or shutil.which("apfs-fuse")


def apfs_partition(img):
    """(offset, length) of the APFS container: the GPT partition of APFS type, or the whole image."""
    with open(img, "rb") as f:
        f.seek(512)
        hdr = f.read(92)
        if hdr[:8] != b"EFI PART":
            return 0, os.path.getsize(img)
        entries_lba, count, size = struct.unpack_from("<QII", hdr, 72)
        f.seek(entries_lba * 512)
        table = f.read(count * size)
    for i in range(count):
        e = table[i * size:(i + 1) * size]
        if e[:16] == APFS_PART_TYPE:
            first, last = struct.unpack_from("<QQ", e, 32)
            return first * 512, (last - first + 1) * 512
    raise SystemExit(f"{img}: GPT has no APFS partition")


def check(case_dir, work, apfs_fuse):
    img = os.path.join(case_dir, "test.img")
    name = os.path.basename(case_dir)
    password = open(os.path.join(case_dir, "password.txt")).read().strip()
    sensitive = "_cs" in name
    problems = []

    # Structure: apfsck on the container alone.
    off, length = apfs_partition(img)
    container = os.path.join(work, f"{name}.apfs")
    with open(img, "rb") as src, open(container, "wb") as dst:
        src.seek(off)
        dst.write(src.read(length))
    if APFSCK:
        p = subprocess.run([APFSCK, container], capture_output=True, text=True)
        if p.returncode != 0:
            problems.append(f"apfsck rc={p.returncode}: {(p.stdout + p.stderr).strip()[:300]}")
    else:
        problems.append("apfsck not found (set APFSCK)")

    # Files: apfs-fuse, which reads the GPT itself and decrypts with -r.
    mnt = os.path.join(work, f"{name}.mnt")
    os.makedirs(mnt, exist_ok=True)
    p = subprocess.run([apfs_fuse, "-r", password, img, mnt], capture_output=True, text=True)
    if p.returncode != 0:
        problems.append(f"apfs-fuse could not mount: {(p.stdout + p.stderr).strip()[:300]}")
        return problems
    try:
        root = os.path.join(mnt, "root")   # apfs-fuse exposes the volume under root/
        want = hashlib.sha256(open(os.path.join(ROOT, "testdata/flower.jpg"), "rb").read()).hexdigest()
        try:
            got = hashlib.sha256(open(os.path.join(root, "flower.jpg"), "rb").read()).hexdigest()
            if got != want:
                problems.append("flower.jpg differs from testdata/flower.jpg")
        except OSError as e:
            problems.append(f"flower.jpg unreadable: {e}")
        try:
            if open(os.path.join(root, "file with spaces.txt")).read() != "Hello World\n":
                problems.append("'file with spaces.txt' has the wrong content")
        except OSError as e:
            problems.append(f"'file with spaces.txt' unreadable: {e}")
        names = set(os.listdir(root))
        if sensitive:
            if not {"case.txt", "CASE.txt"} <= names:
                problems.append(f"case-sensitive volume lacks case.txt + CASE.txt: {sorted(names)}")
        elif "CASE.txt" in names or open(os.path.join(root, "case.txt")).read() != "UPPER\n":
            problems.append("case-insensitive volume holds two case variants, or case.txt was not overwritten")
    finally:
        subprocess.run(["fusermount", "-u", mnt], capture_output=True)
    return problems


def main():
    if not APFS_FUSE:
        raise SystemExit("apfs-fuse not found (set APFS_FUSE)")
    cases = sys.argv[1:] or sorted(glob.glob(os.path.join(ROOT, "testdata/apfs/apfs_*")))
    if not cases:
        raise SystemExit("no testdata/apfs/ fixtures — run scripts/make_apfs_images_macos.sh on a Mac")
    bad = 0
    with tempfile.TemporaryDirectory() as work:
        for c in cases:
            problems = check(c, work, APFS_FUSE)
            print(f"{'OK  ' if not problems else 'FAIL'} {os.path.basename(c)}")
            for p in problems:
                print(f"     {p}")
            bad += bool(problems)
    print(f"{len(cases) - bad} of {len(cases)} APFS fixtures verified")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
