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

A case with erase.txt (Format/Encrypt from a mounted card) is checked differently:
fsck clean, flower.jpg gone, after_erase.txt as the test wrote it, the label kept on a
Format, and — for a full erase — no byte of the old flower.jpg left on the raw drive.

A case with journal.txt (an ext4 drive repaired by replaying its journal) is plain
ext4: e2fsck clean, needs_recovery cleared and the journal empty, journal_replayed.txt
replayed, the block the journal revoked still zero, after_repair.txt as the test wrote it.

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


def read_erase_spec(case_dir):
    spec = {}
    with open(os.path.join(case_dir, "erase.txt")) as f:
        for line in f:
            if "=" in line:
                k, v = line.strip().split("=", 1)
                spec[k] = v
    return spec


def vc_decrypt(slot, password, pim, out):
    """The data area of an AES / SHA-512 VeraCrypt volume, decrypted in Python."""
    from fill_veracrypt_volume import open_header
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


def old_plaintext_left(img):
    """True if flower.jpg's first 512 bytes still sit on a sector boundary of `img`."""
    with open(os.path.join(ROOT, "testdata/flower.jpg"), "rb") as f:
        head = f.read(512)
    with open(img, "rb") as f:
        while chunk := f.read(1 << 20):
            for i in range(0, len(chunk) - 511, 512):
                if chunk[i] == head[0] and chunk[i:i + 512] == head:
                    return True
    return False


def check_erased_files(img, expected_content):
    """After Format/Encrypt: flower.jpg gone, after_erase.txt as the test wrote it."""
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
            names = sorted(os.listdir(root))
            if "flower.jpg" in names:
                problems.append("flower.jpg is still listed after the erase")
            try:
                with open(os.path.join(root, "after_erase.txt"), "rb") as f:
                    got = f.read().decode(errors="replace")
                if got != expected_content:
                    problems.append(f"after_erase.txt reads {got!r}, the test wrote {expected_content!r}")
            except OSError as e:
                problems.append(f"after_erase.txt: {e} (listing: {names})")
            extra = [n for n in names if n not in ("after_erase.txt", "System Volume Information")]
            if extra:
                problems.append(f"files left over from before the erase: {extra}")
        finally:
            run("udisksctl", "unmount", "-b", dev, "--no-user-interaction")
    finally:
        run("udisksctl", "loop-delete", "-b", dev, "--no-user-interaction")
    return problems


def erase_main(slot, case_dir):
    """Host check for a Format/Encrypt case (testEraseOperations)."""
    spec = read_erase_spec(case_dir)
    action = spec["action"]
    full = spec.get("quick", "false") != "true"
    plain_case = os.path.exists(os.path.join(case_dir, "plain.txt"))
    name = os.path.basename(os.path.normpath(case_dir))
    expected = f"OTGMaster after {action} ({name})"
    problems, what = [], []
    with tempfile.TemporaryDirectory() as work:
        snapshot = os.path.join(work, "slot.img")
        with open(slot, "rb") as src, open(snapshot, "wb") as dst:
            while chunk := src.read(1 << 20):
                dst.write(chunk)
        if full and old_plaintext_left(snapshot):
            problems.append("a full erase left the old flower.jpg's bytes on the drive")
        what.append("old plaintext gone" if full else "quick (old data may remain)")

        if action == "format" and plain_case:
            fs_img, label_want = snapshot, "OTGFAT32" if "fat32" in name else "OTGEXFAT"
            label = run("blkid", "-p", "-o", "value", "-s", "LABEL", fs_img).stdout.strip()
            if label != label_want:
                problems.append(f"label {label!r} after Format, was {label_want!r}")
            what.append(f"label {label!r} kept")
        elif action == "format":
            password = open(os.path.join(case_dir, "password.txt")).read().strip()
            pim = int(open(os.path.join(case_dir, "pim.txt")).read().strip())
            fs_img = os.path.join(work, "plain.img")
            vc_decrypt(snapshot, password, pim, fs_img)
            what.append("same VeraCrypt password opens it")
        elif spec.get("cipher") == "AES" and spec.get("hash") == "SHA-512":
            fs_img = os.path.join(work, "plain.img")
            vc_decrypt(snapshot, spec["password"], int(spec.get("pim", "0") or 0), fs_img)
            what.append("new VeraCrypt header opened and data decrypted in Python")
        else:
            # Argon2 / cascades: no host tool decrypts these. The volume must at least
            # not be readable as a plain filesystem any more.
            if fs_type(snapshot):
                problems.append(f"the drive still reads as plain {fs_type(snapshot)} after Encrypt")
            fs_img = None
            what.append(f"{spec.get('cipher')}/{spec.get('hash')}: not host-decryptable, checked opaque")

        if fs_img is not None:
            fs = fs_type(fs_img)
            want_fs = {"exfat": "exfat", "fat32": "vfat"}.get(spec.get("fs", ""), None)
            if action == "encrypt" and want_fs and fs != want_fs:
                problems.append(f"filesystem inside is {fs!r}, asked for {want_fs!r}")
            problems += check_structure(fs, fs_img)
            problems += check_erased_files(fs_img, expected)
            what.append(f"{fs} checker clean, flower.jpg gone, after_erase.txt present")
    if problems:
        print(f"HOST CHECK FAILED {name} ({action}):")
        for p in problems:
            print(f"  - {p}")
        sys.exit(1)
    print(f"HOST CHECK OK {name} ({action}): " + "; ".join(what))


def journal_main(slot, case_dir):
    """After the journal repair (E2EAutomatedTest.runJournalRepairCase)."""
    name = os.path.basename(os.path.normpath(case_dir))
    blocks = dict(l.strip().split("=", 1) for l in open(os.path.join(case_dir, "journal_blocks.txt")) if "=" in l)
    problems = []
    with tempfile.TemporaryDirectory() as work:
        img = os.path.join(work, "slot.img")
        with open(slot, "rb") as src, open(img, "wb") as dst:   # a stable copy
            while chunk := src.read(1 << 20):
                dst.write(chunk)
        problems += check_structure("ext4", img)
        header = run("dumpe2fs", "-h", img).stdout
        if "needs_recovery" in header:
            problems.append("needs_recovery is still set")
        start = re.search(r"^Journal start:\s+(\d+)", header, re.M)
        if not start or start.group(1) != "0":
            problems.append(f"the journal is not empty (Journal start: {start.group(1) if start else '?'})")
        cat = lambda path: subprocess.run(["debugfs", "-R", f"cat {path}", img], capture_output=True).stdout
        if cat("/journal_replayed.txt") != b"REPLAYED: yes\n":
            problems.append(f"journal_replayed.txt reads {cat('/journal_replayed.txt')!r}, not the replayed block")
        want = b"OTGMaster wrote this after the journal repair"
        if cat("/after_repair.txt") != want:
            problems.append(f"after_repair.txt reads {cat('/after_repair.txt')!r}")
        with open(img, "rb") as f:
            f.seek(int(blocks["revoked"]) * 4096)
            if any(f.read(4096)):
                problems.append(f"block {blocks['revoked']} was written: the journal revoked it")
    if problems:
        print(f"HOST CHECK FAILED {name} (plain, ext4):")
        for p in problems:
            print(f"  - {p}")
        sys.exit(1)
    print(f"HOST CHECK OK {name} (plain, ext4): e2fsck clean, journal replayed and empty, "
          f"revoked block untouched, after_repair.txt present")


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    slot, case_dir = sys.argv[1:]
    if os.path.exists(os.path.join(case_dir, "erase.txt")):
        return erase_main(slot, case_dir)
    if os.path.exists(os.path.join(case_dir, "journal.txt")):
        return journal_main(slot, case_dir)
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
