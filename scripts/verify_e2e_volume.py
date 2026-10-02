#!/usr/bin/env python3
"""
Checks, on the host and without the app's code, what an E2E write case left on
the emulator's USB drive.

The E2E test only reads its writes back through the app, and a symmetric bug — a
wrong IV, a write to the wrong offset — round-trips perfectly through the same
code. The emulator's drive is a plain file on this host (the QEMU slot), so after
a write case this:

  1. decrypts it with tools independent of the app — cryptsetup's key and layout
     with Python's AES for LUKS (luks_payload.py) and BitLocker (bitlk_decrypt.py),
     the VeraCrypt header opened in Python for VeraCrypt;
  2. runs the filesystem's own checker — e2fsck -fn, fsck.vfat -n, fsck.exfat -n,
     or ntfs_check.py + ntfsfix -n;
  3. mounts it read-only with the Linux kernel's own driver (udisksctl, no root)
     and checks every file the write case leaves: write_dir/nested.txt,
     write_dir/big_write.bin (regenerated here, 3 MiB), flower.jpg untouched where
     the fixture has it, and write_test.txt deleted.

    python3 scripts/verify_e2e_volume.py SLOT.img testdata/<case>

Exit 0 when everything holds; the problems are printed otherwise.
"""
import hashlib
import os
import re
import struct
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes  # noqa: E402

BIG_NAME = "big_write.bin"
BIG_BLOCKS = 3 * 1024 * 1024 // 32


def big_file_sha():
    md = hashlib.sha256()
    for i in range(BIG_BLOCKS):
        md.update(hashlib.sha256(f"otg-e2e-big-{i}".encode()).digest())
    return md.hexdigest()


def run(*cmd, input=None):
    return subprocess.run(cmd, input=input, capture_output=True, text=True)


def decrypt(slot, case_dir, out):
    """Writes the decrypted volume of `slot` to `out`; returns the container name."""
    password = open(os.path.join(case_dir, "password.txt")).read().strip()
    tag_file = os.path.join(case_dir, "container.txt")
    container = open(tag_file).read().strip() if os.path.exists(tag_file) else "VERACRYPT"
    if container in ("LUKS1", "LUKS2"):
        p = run(sys.executable, os.path.join(HERE, "luks_payload.py"), "decrypt", slot, password, out)
    elif container == "BITLOCKER":
        p = run(sys.executable, os.path.join(HERE, "bitlk_decrypt.py"), slot, password, out)
    elif container == "VERACRYPT":
        from fill_veracrypt_volume import open_header
        pim = int(open(os.path.join(case_dir, "pim.txt")).read().strip())
        master, start, size = open_header(slot, password, pim)
        with open(slot, "rb") as src, open(out, "wb") as dst:
            src.seek(start)
            pos = 0
            while pos < size:
                chunk = src.read(min(1 << 20, size - pos))
                buf = bytearray()
                for i in range(0, len(chunk), 512):
                    unit = (start + pos + i) // 512
                    d = Cipher(algorithms.AES(master), modes.XTS(unit.to_bytes(16, "little"))).decryptor()
                    buf += d.update(chunk[i:i + 512]) + d.finalize()
                dst.write(buf)
                pos += len(chunk)
        return container
    else:
        raise SystemExit(f"unknown container {container}")
    if p.returncode != 0:
        raise SystemExit(f"independent decryption failed:\n{p.stdout}{p.stderr}")
    return container


def fs_type(img):
    return run("blkid", "-p", "-o", "value", "-s", "TYPE", img).stdout.strip()


def trim_ntfs(img):
    """Cuts the image to the NTFS's own size, so ntfsfix finds the backup boot sector at the end."""
    with open(img, "rb") as f:
        boot = f.read(512)
    total = struct.unpack_from("<q", boot, 0x28)[0]
    bps = struct.unpack_from("<H", boot, 0x0B)[0]
    size = (total + 1) * bps
    if os.path.getsize(img) > size:
        with open(img, "r+b") as f:
            f.truncate(size)


def check_structure(fs, img):
    if fs in ("ext2", "ext3", "ext4"):
        p = run("e2fsck", "-fn", img)
    elif fs == "vfat":
        p = run("fsck.vfat", "-n", img)
    elif fs == "exfat":
        p = run("fsck.exfat", "-n", img)
    elif fs == "ntfs":
        trim_ntfs(img)
        p = run(sys.executable, os.path.join(HERE, "ntfs_check.py"), img)
        if p.returncode == 0:
            p = run("ntfsfix", "-n", img)
    else:
        return [f"no checker for filesystem '{fs}'"]
    if p.returncode != 0:
        return [f"{fs} checker rc={p.returncode}:\n{(p.stdout + p.stderr).strip()[-800:]}"]
    return []


def check_files(img, case_dir):
    """Mounts read-only with the kernel's driver as this user and checks the files."""
    out = run("udisksctl", "loop-setup", "-r", "-f", img, "--no-user-interaction")
    m = re.search(r"/dev/loop\d+", out.stdout)
    if not m:
        return [f"udisksctl loop-setup failed: {out.stdout}{out.stderr}"]
    dev = m.group(0)
    problems = []
    try:
        mo = run("udisksctl", "mount", "-b", dev, "-o", "ro", "--no-user-interaction")
        mnt = re.search(r" at (.+?)\.?$", mo.stdout.strip())
        if not mnt:
            return [f"udisksctl mount failed: {mo.stdout}{mo.stderr}"]
        root = mnt.group(1)
        try:
            def read(rel):
                with open(os.path.join(root, rel), "rb") as f:
                    return f.read()
            try:
                if read("write_dir/nested.txt") != b"nested content":
                    problems.append("write_dir/nested.txt has the wrong content")
            except OSError as e:
                problems.append(f"write_dir/nested.txt: {e}")
            try:
                got = hashlib.sha256(read(f"write_dir/{BIG_NAME}")).hexdigest()
                if got != big_file_sha():
                    problems.append(f"write_dir/{BIG_NAME} differs from the bytes the test wrote")
            except OSError as e:
                problems.append(f"write_dir/{BIG_NAME}: {e}")
            if os.path.exists(os.path.join(root, "write_test.txt")):
                problems.append("write_test.txt still exists after its deletion")
            if os.path.exists(os.path.join(root, "flower.jpg")):
                with open(os.path.join(ROOT, "testdata/flower.jpg"), "rb") as f:
                    want = hashlib.sha256(f.read()).hexdigest()
                if hashlib.sha256(read("flower.jpg")).hexdigest() != want:
                    problems.append("flower.jpg changed — the write case damaged an existing file")
        finally:
            run("udisksctl", "unmount", "-b", dev, "--no-user-interaction")
    finally:
        run("udisksctl", "loop-delete", "-b", dev, "--no-user-interaction")
    return problems


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    slot, case_dir = sys.argv[1:]
    with tempfile.TemporaryDirectory() as work:
        snapshot = os.path.join(work, "slot.img")
        with open(slot, "rb") as src, open(snapshot, "wb") as dst:   # a stable copy
            while chunk := src.read(1 << 20):
                dst.write(chunk)
        plain = os.path.join(work, "plain.img")
        container = decrypt(snapshot, case_dir, plain)
        fs = fs_type(plain)
        problems = check_structure(fs, plain)
        problems += check_files(plain, case_dir)
    name = os.path.basename(os.path.normpath(case_dir))
    if problems:
        print(f"HOST CHECK FAILED {name} ({container}, {fs or 'unrecognised'}):")
        for p in problems:
            print(f"  - {p}")
        sys.exit(1)
    print(f"HOST CHECK OK {name} ({container}, {fs}): checker clean, nested.txt, "
          f"{BIG_NAME} (3 MiB) and deletions verified")


if __name__ == "__main__":
    main()
