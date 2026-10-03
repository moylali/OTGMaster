#!/usr/bin/env python3
"""
Builds one device-matrix drive as partition images, without root (docs/TEST_DATA.md §16).

Each partition of the drive (scripts/matrix_layout.py) becomes, in OUT:

    <LABEL>.img        the exact bytes to write into the partition: the container
                       (LUKS, VeraCrypt, BitLocker) holding the filesystem, or the
                       filesystem itself for an unencrypted partition
    <LABEL>.plain.img  the filesystem alone, as built (sparse) — the decrypted view
    <LABEL>.manifest   every file of the read set with its SHA-256 (also on the
                       volume as BENCH/MANIFEST.txt)
    <LABEL>.chunks     SHA-256 of every MiB of the decrypted view: the baseline that
                       verify_matrix_drive.py compares the drive against
    <LABEL>.log        what was run
    drive.json         the layout, sizes and image hashes, for write_matrix_drive.sh

APFS partitions are not built here: only a Mac can (prepare_matrix_drive_macos.sh).

How each layer is made without root — the same tools the E2E matrix uses
(scripts/make_e2e_matrix.py), at full partition size:
    ext2/3/4   the tree staged in a directory, then mkfs.extN -d
    FAT32      mkfs.fat (4 KiB clusters), then a udisksctl loop mount as this user
    exFAT      mkfs.exfat (4 KiB clusters), the same way
    NTFS       mkntfs (4 KiB clusters), then a user-mode ntfs-3g FUSE mount
    LUKS1/2    cryptsetup luksFormat on the image file, then luks_payload.py
    VeraCrypt  veracrypt --filesystem=none, then fill_veracrypt_volume.py
    BitLocker  make_bitlocker_image.py, checked with cryptsetup bitlkDump

    python3 scripts/build_matrix_drive.py --drive 2 --out ~/matrix/d2
    python3 scripts/build_matrix_drive.py --drive 1 --out ~/matrix/d1 --only D1NTFS
    python3 scripts/build_matrix_drive.py --drive 2 --out /tmp/t --test-scale   # small

A full drive needs about 60 GB for the images plus ~3 GB per partition of sparse
plain images, and takes roughly 15-30 minutes with -j 4.
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import make_fixture_tree as tree  # noqa: E402
import matrix_layout as L  # noqa: E402
from make_bitlocker_image import HEADER as BL_HEADER, META as BL_META  # noqa: E402

MiB = 1 << 20


class Scale:
    """Full size, or a small version of everything for testing the pipeline."""

    def __init__(self, test):
        self.test = test
        self.part_bytes = 300 * MiB if test else L.PART_BYTES
        self.large_mib = 40 if test else 2100
        self.dense = 300 if test else 10000

    def read_bytes(self, p):
        return 120e6 if self.test else p["read_bytes"]


class Log:
    def __init__(self, path, label):
        self.f = open(path, "w")
        self.label = label

    def __call__(self, msg):
        line = f"[{time.strftime('%H:%M:%S')}] {self.label}: {msg}"
        print(line, flush=True)
        self.f.write(line + "\n")
        self.f.flush()


def need(m, what):
    """The match, or an error naming what was not found."""
    if m is None:
        raise RuntimeError(f"could not find {what}")
    return m


def sh(log, *cmd, input=None):
    log("$ " + " ".join(cmd))
    p = subprocess.run(cmd, input=input, capture_output=True, text=True)
    if p.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)} failed ({p.returncode}):\n{p.stdout}{p.stderr}")
    return p.stdout


def populate(log, mount, p, scale):
    """The read set, through make_fixture_tree.py's own code."""
    log(f"filling {mount} ({scale.read_bytes(p) / 1e9:.2f} GB)")
    _, manifest = tree.build(mount, p["label"], scale.read_bytes(p), False, scale.large_mib, scale.dense)
    return manifest


def make_fs(log, p, img, size, work, scale):
    """Creates the filesystem on `img` (`size` bytes) and fills it; returns the manifest."""
    fs, label = p["fs"], p["label"]
    with open(img, "wb") as f:
        f.truncate(size)
    if fs in ("ext2", "ext3", "ext4"):
        stage = os.path.join(work, "stage")
        os.makedirs(stage)
        manifest = populate(log, stage, p, scale)
        sh(log, f"mkfs.{fs}", "-q", "-F", "-m", "0", "-L", label,
           "-E", "lazy_itable_init=0,lazy_journal_init=0", "-d", stage, img)
        shutil.rmtree(stage)
        return manifest
    if fs == "ntfs":
        sh(log, "mkntfs", "-F", "-q", "-f", "-c", "4096", "-L", label, img)
        mnt = os.path.join(work, "mnt")
        os.makedirs(mnt)
        sh(log, "ntfs-3g", img, mnt)
        try:
            return populate(log, mnt, p, scale)
        finally:
            sh(log, "fusermount", "-u", mnt)
    if fs == "fat32":
        sh(log, "mkfs.fat", "-F", "32", "-s", "8", "-n", label, img)
    elif fs == "exfat":
        sh(log, "mkfs.exfat", "-c", "4K", "-L", label, img)
    else:
        raise RuntimeError(f"unknown filesystem {fs}")
    # udisks refuses loop-setup now and then when several builds ask at once
    # (seen at -j 10); a retry a moment later succeeds.
    for attempt in range(5):
        try:
            out = sh(log, "udisksctl", "loop-setup", "-f", img, "--no-user-interaction")
            break
        except RuntimeError:
            if attempt == 4:
                raise
            time.sleep(2 + attempt * 2)
    dev = need(re.search(r"/dev/loop\d+", out), "the loop device").group(0)
    try:
        r = subprocess.run(["udisksctl", "mount", "-b", dev, "--no-user-interaction"], capture_output=True, text=True)
        # A desktop session may auto-mount the new loop device first; use that mount.
        mnt = need(re.search(r" at (.+?)\.?$", r.stdout.strip()) or re.search(r"already mounted at `(.+?)'", r.stderr),
                   f"the mount point ({r.stdout}{r.stderr})").group(1)
        try:
            return populate(log, mnt, p, scale)
        finally:
            sh(log, "sync")
            sh(log, "udisksctl", "unmount", "-b", dev, "--no-user-interaction")
    finally:
        subprocess.run(["udisksctl", "loop-delete", "-b", dev, "--no-user-interaction"], capture_output=True)


def luks_offset(log, img):
    dump = sh(log, "cryptsetup", "luksDump", "--disable-locks", img)
    m = re.search(r"offset:\s+(\d+) \[bytes\]", dump)
    return int(m.group(1)) if m else int(need(re.search(r"Payload offset:\s+(\d+)", dump), "the LUKS offset").group(1)) * 512


def chunk_hashes(path):
    """The baseline verify_matrix_drive.py compares: the volume's length, then the
    SHA-256 of every MiB of it, one per line."""
    out = [f"# bytes {os.path.getsize(path)}"]
    with open(path, "rb") as f:
        while chunk := f.read(MiB):
            out.append(hashlib.sha256(chunk).hexdigest())
    return "\n".join(out) + "\n"


def file_sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(16 * MiB):
            h.update(chunk)
    return h.hexdigest()


def build_part(p, out, scale):
    label, c = p["label"], p["container"]
    log = Log(os.path.join(out, f"{label}.log"), label)
    img = os.path.join(out, f"{label}.img")
    plain = os.path.join(out, f"{label}.plain.img")
    work = tempfile.mkdtemp(prefix=f".work-{label}-", dir=out)
    t0 = time.time()
    try:
        size = scale.part_bytes
        if c == "plain":
            manifest = make_fs(log, p, plain, size, work, scale)
            sh(log, "cp", "--sparse=always", plain, img)
        elif c in ("luks1", "luks2", "luks2_4k"):
            with open(img, "wb") as f:
                f.truncate(size)
            args = ["cryptsetup", "luksFormat", "--type", "luks1" if c == "luks1" else "luks2", "--batch-mode",
                    "--disable-locks", "--cipher", "aes-xts-plain64", "--key-size", "512"]
            if c == "luks1":
                args += ["--pbkdf", "pbkdf2", "--hash", "sha256", "--iter-time", "500"]
            else:
                # 64 MiB of Argon2id memory: what a phone unlocks in seconds (as the E2E matrix).
                args += ["--pbkdf", "argon2id", "--pbkdf-memory", "65536", "--pbkdf-parallel", "1",
                         "--iter-time", "500", "--sector-size", "4096" if c == "luks2_4k" else "512"]
            sh(log, *args, img, "-", input=L.PASSWORD)
            manifest = make_fs(log, p, plain, size - luks_offset(log, img), work, scale)
            sh(log, sys.executable, os.path.join(HERE, "luks_payload.py"), "encrypt", img, L.PASSWORD, plain)
        elif c == "vc":
            sh(log, "veracrypt", "-t", "-c", "--volume-type=normal", img, f"--size={size}",
               f"--password={L.PASSWORD}", "--encryption=AES", "--hash=SHA-512", "--filesystem=none",
               f"--pim={L.PIM}", "--random-source=/dev/urandom", "--quick", "--non-interactive")
            fill = os.path.join(HERE, "fill_veracrypt_volume.py")
            sh(log, sys.executable, fill, img, L.PASSWORD, str(L.PIM), "--make-plain", plain)
            manifest = make_fs(log, p, plain, os.path.getsize(plain), work, scale)
            sh(log, sys.executable, fill, img, L.PASSWORD, str(L.PIM), "--fill", plain)
        elif c == "bitlocker":
            plain_size = (size - 3 * BL_META - BL_HEADER) // 4096 * 4096
            manifest = make_fs(log, p, plain, plain_size, work, scale)
            sh(log, sys.executable, os.path.join(HERE, "make_bitlocker_image.py"), plain, img,
               "--password", L.PASSWORD, "--recovery", L.RECOVERY, "--cipher", p["bl"])
            with open(img, "r+b") as f:
                f.truncate(size)        # the partition's full size; the tail is unused
            sh(log, "cryptsetup", "bitlkDump", img)     # cryptsetup must accept the header
        else:
            raise RuntimeError(f"cannot build container {c} here")
        if os.path.getsize(img) != size:
            raise RuntimeError(f"{img} is {os.path.getsize(img)} bytes, the partition is {size}")
        with open(os.path.join(out, f"{label}.manifest"), "w", encoding="utf-8") as f:
            f.write(manifest)
        log("hashing the baseline")
        with open(os.path.join(out, f"{label}.chunks"), "w") as f:
            f.write(chunk_hashes(plain))
        sha = file_sha(img)
        log(f"done in {time.time() - t0:.0f} s")
        return label, sha
    finally:
        shutil.rmtree(work, ignore_errors=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--drive", type=int, required=True, choices=sorted(L.DRIVES))
    ap.add_argument("--out", required=True)
    ap.add_argument("--only", action="append", help="build only this label (repeatable)")
    ap.add_argument("-j", "--jobs", type=int, default=4)
    ap.add_argument("--test-scale", action="store_true", help="300 MiB partitions, for testing the pipeline")
    a = ap.parse_args()

    scale = Scale(a.test_scale)
    os.makedirs(a.out, exist_ok=True)
    parts = L.DRIVES[a.drive]
    todo = [p for p in parts if p["container"] != "apfs" and (not a.only or p["label"] in a.only)]
    if a.only and len(todo) != len(a.only):
        raise SystemExit(f"--only names a label not buildable on drive {a.drive}: {a.only}")
    for p in todo:
        if os.path.exists(os.path.join(a.out, f"{p['label']}.img")):
            raise SystemExit(f"{p['label']}.img already exists in {a.out} — remove it to rebuild")

    meta_path = os.path.join(a.out, "drive.json")
    meta = json.load(open(meta_path)) if os.path.exists(meta_path) else {}
    if meta and (meta.get("drive") != a.drive or meta.get("part_bytes") != scale.part_bytes):
        raise SystemExit(f"{meta_path} is for another drive or scale")
    shas = {e["label"]: e.get("image_sha256") for e in meta.get("partitions", [])}

    failed = []
    with concurrent.futures.ProcessPoolExecutor(max_workers=a.jobs) as ex:
        futures = {ex.submit(build_part, p, a.out, scale): p["label"] for p in todo}
        for fut in concurrent.futures.as_completed(futures):
            label = futures[fut]
            try:
                _, sha = fut.result()
                shas[label] = sha
            except Exception as e:  # noqa: BLE001 — reported per partition, the rest carry on
                print(f"FAILED {label}: {e}", flush=True)
                failed.append(label)

    commit = subprocess.run(["git", "-C", ROOT, "describe", "--always", "--dirty"],
                            capture_output=True, text=True).stdout.strip()
    entries = []
    for i, p in enumerate(parts, 1):
        built = p["container"] != "apfs" and shas.get(p["label"]) is not None
        entries.append(dict(p, slot=i,
                            image=f"{p['label']}.img" if built else None,
                            image_sha256=shas.get(p["label"]) if built else None))
    meta = dict(drive=a.drive, part_bytes=scale.part_bytes, test_scale=a.test_scale,
                password=L.PASSWORD, pim=L.PIM, recovery=L.RECOVERY,
                built=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), commit=commit, partitions=entries)
    with open(meta_path, "w") as f:
        json.dump(meta, f, indent=2)
    print(f"{meta_path}: {sum(1 for e in entries if e['image'])} of {len(entries)} partitions built")
    if failed:
        raise SystemExit(f"failed: {', '.join(failed)}")


if __name__ == "__main__":
    main()
