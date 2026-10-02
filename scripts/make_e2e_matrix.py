#!/usr/bin/env python3
"""
Builds the E2E support matrix without root: every filesystem the app reads and
writes, inside every container it unlocks.

    ext2, ext3, ext4          x  LUKS1, LUKS2, LUKS2-4K, VeraCrypt
    exFAT, FAT32, NTFS        x  LUKS1, LUKS2, LUKS2-4K, VeraCrypt, BitLocker

LUKS2 appears twice: with 512-byte sectors, and with the 4096-byte sectors
cryptsetup has chosen by default since 2.4 (which the app refused before 0.4.1).

One case per pair, testdata/m_<container>_<fs>/, each a write case
(write_test.txt) that also checks flower.jpg byte for byte after the first mount
(verify_flower.txt), so a single run proves unlock, read, write, persistence over
remounts, and delete.

How each layer is made, with no root:
  - ext2/3/4   mkfs.extN -d <tree>          (populates without mounting)
  - FAT32      mkfs.fat, then udisksctl loop-setup + mount as this user
  - exFAT      mkfs.exfat, then udisksctl the same way
  - NTFS       mkntfs, then a user-mode ntfs-3g FUSE mount
  - VeraCrypt  veracrypt --filesystem=none, then fill_veracrypt_volume.py
  - LUKS1/2    cryptsetup luksFormat on the image file (--disable-locks), then
               scripts/luks_payload.py encrypts the filesystem into the payload
               with cryptsetup's key and layout. LUKS2 uses a small Argon2id so the
               emulator unlocks quickly.
  - BitLocker  make_bitlocker_image.py --verify (cryptsetup must accept it)

    python3 scripts/make_e2e_matrix.py            # all 21
    python3 scripts/make_e2e_matrix.py luks2 ext4 # one container x fs
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PASSWORD = "password123"
PIM = 1
RECOVERY = "111111-222222-333333-444444-555555-666666-111111-222222"
FS_BYTES = 40 << 20           # FAT32 needs >= 65525 clusters; 40 MiB at 512 B gives that
# Mounted read-only by the app: no extents, and the driver writes only extent inodes.
READ_ONLY_FS = ("ext2", "ext3")
BITLOCKER_CIPHER = {"fat32": "cbc128", "exfat": "xts256", "ntfs": "xts128"}
TAG = {"vc": "VERACRYPT", "luks1": "LUKS1", "luks2": "LUKS2", "luks2_4k": "LUKS2", "bitlocker": "BITLOCKER"}
NAMES = {"vc": "VeraCrypt AES/SHA-512", "luks1": "LUKS1 aes-xts-plain64 (PBKDF2)",
         "luks2": "LUKS2 aes-xts-plain64 (Argon2id, 512-byte sectors)",
         "luks2_4k": "LUKS2 aes-xts-plain64 (Argon2id, 4096-byte sectors)", "ext2": "ext2", "ext3": "ext3",
         "ext4": "ext4", "exfat": "exFAT", "fat32": "FAT32", "ntfs": "NTFS"}

MATRIX = ([(c, fs) for fs in ("ext2", "ext3", "ext4") for c in ("luks1", "luks2", "luks2_4k", "vc")] +
          [(c, fs) for fs in ("exfat", "fat32", "ntfs")
           for c in ("luks1", "luks2", "luks2_4k", "vc", "bitlocker")])


def sh(*cmd, input=None):
    p = subprocess.run(cmd, input=input, capture_output=True, text=True)
    if p.returncode != 0:
        raise SystemExit(f"{' '.join(cmd)} failed:\n{p.stdout}{p.stderr}")
    return p.stdout


def tree(dirpath):
    """The content every mount case checks (E2EAutomatedTest)."""
    os.makedirs(os.path.join(dirpath, "nested/very/deep/folder"))
    shutil.copy(os.path.join(ROOT, "testdata/flower.jpg"), os.path.join(dirpath, "flower.jpg"))
    open(os.path.join(dirpath, "nested/very/deep/folder/empty_file.txt"), "w").close()
    with open(os.path.join(dirpath, "file with spaces.txt"), "w") as f:
        f.write("Hello World\n")
    with open(os.path.join(dirpath, "unicöde_fîle.txt"), "w") as f:
        f.write("Unicode\n")
    with open(os.path.join(dirpath, "large_file.bin"), "wb") as f:
        f.write(os.urandom(10 * 1024))


def copy_into(src, dst):
    for name in os.listdir(src):
        s = os.path.join(src, name)
        if os.path.isdir(s):
            shutil.copytree(s, os.path.join(dst, name))
        else:
            shutil.copyfile(s, os.path.join(dst, name))


def udisks_populate(img, src):
    out = sh("udisksctl", "loop-setup", "-f", img, "--no-user-interaction")
    dev = re.search(r"/dev/loop\d+", out).group(0)
    try:
        mnt = re.search(r" at (.+?)\.?$", sh("udisksctl", "mount", "-b", dev, "--no-user-interaction").strip()).group(1)
        copy_into(src, mnt)
        sh("sync")
        sh("udisksctl", "unmount", "-b", dev, "--no-user-interaction")
    finally:
        subprocess.run(["udisksctl", "loop-delete", "-b", dev, "--no-user-interaction"], capture_output=True)


def make_fs(fs, img, size, work):
    with open(img, "wb") as f:
        f.truncate(size)
    src = os.path.join(work, "tree")
    shutil.rmtree(src, ignore_errors=True)
    tree(src)
    label = f"OTG{fs.upper()}"[:11]
    if fs in ("ext2", "ext3", "ext4"):
        sh(f"mkfs.{fs}", "-q", "-F", "-m", "0", "-L", label,
           "-E", "lazy_itable_init=0,lazy_journal_init=0", "-d", src, img)
    elif fs == "fat32":
        sh("mkfs.fat", "-F", "32", "-n", label, img)
        udisks_populate(img, src)
    elif fs == "exfat":
        sh("mkfs.exfat", "-L", label, img)
        udisks_populate(img, src)
    elif fs == "ntfs":
        sh("mkntfs", "-F", "-q", "-f", "-L", label, img)
        mnt = os.path.join(work, "mnt")
        os.makedirs(mnt, exist_ok=True)
        sh("ntfs-3g", img, mnt)
        try:
            copy_into(src, mnt)
        finally:
            sh("fusermount", "-u", mnt)
    else:
        raise SystemExit(f"unknown filesystem {fs}")


def build(container, fs, work):
    d = os.path.join(ROOT, "testdata", f"m_{container}_{fs}")
    os.makedirs(d, exist_ok=True)
    for name in os.listdir(d):
        os.remove(os.path.join(d, name))
    img = os.path.join(d, "test.img")
    plain = os.path.join(work, "plain.img")

    if container == "vc":
        sh("veracrypt", "-t", "-c", "--volume-type=normal", img, f"--size={FS_BYTES + (1 << 20)}",
           f"--password={PASSWORD}", "--encryption=AES", "--hash=SHA-512", "--filesystem=none",
           f"--pim={PIM}", "--random-source=/dev/urandom", "--non-interactive")
        fill = os.path.join(ROOT, "scripts/fill_veracrypt_volume.py")
        sh(sys.executable, fill, img, PASSWORD, str(PIM), "--make-plain", plain)
        make_fs(fs, plain, os.path.getsize(plain), work)
        sh(sys.executable, fill, img, PASSWORD, str(PIM), "--fill", plain)
    elif container in ("luks1", "luks2", "luks2_4k"):
        header = (2 << 20) if container == "luks1" else (16 << 20)
        with open(img, "wb") as f:
            f.truncate(header + FS_BYTES)
        args = ["cryptsetup", "luksFormat", "--type", "luks1" if container == "luks1" else "luks2", "--batch-mode", "--disable-locks",
                "--cipher", "aes-xts-plain64", "--key-size", "512"]
        if container == "luks1":
            args += ["--pbkdf", "pbkdf2", "--hash", "sha256", "--iter-time", "500"]
        else:
            args += ["--pbkdf", "argon2id", "--pbkdf-memory", "65536", "--pbkdf-parallel", "1",
                     "--iter-time", "500", "--sector-size", "4096" if container == "luks2_4k" else "512"]
        sh(*args, img, "-", input=PASSWORD)
        dump = sh("cryptsetup", "luksDump", "--dump-volume-key", "--batch-mode", "--disable-locks", img,
                  input=PASSWORD)
        offset = int(re.search(r"Payload offset:\s+(\d+)", dump).group(1)) * 512
        make_fs(fs, plain, os.path.getsize(img) - offset, work)
        sh(sys.executable, os.path.join(ROOT, "scripts/luks_payload.py"), "encrypt", img, PASSWORD, plain)
    elif container == "bitlocker":
        make_fs(fs, plain, FS_BYTES, work)
        sh(sys.executable, os.path.join(ROOT, "scripts/make_bitlocker_image.py"), plain, img,
           "--password", PASSWORD, "--recovery", RECOVERY, "--cipher", BITLOCKER_CIPHER[fs], "--verify")
    else:
        raise SystemExit(f"unknown container {container}")
    os.chmod(img, 0o644)
    os.remove(plain)

    def put(name, text):
        with open(os.path.join(d, name), "w") as f:
            f.write(text + "\n")

    put("password.txt", PASSWORD)
    if container == "vc":
        put("pim.txt", str(PIM))
    if container == "bitlocker":
        put("recovery.txt", RECOVERY)
    put("container.txt", TAG[container])
    if fs in READ_ONLY_FS:
        put("read_only.txt", "true")
    else:
        put("write_test.txt", "true")
        put("verify_flower.txt", "true")
    extra = f" ({BITLOCKER_CIPHER[fs]})" if container == "bitlocker" else ""
    if fs in READ_ONLY_FS:
        put("description.txt",
            f"Support matrix: {NAMES[fs]} inside {NAMES.get(container, 'BitLocker')}. Unlock, read "
            f"flower.jpg; the volume must mount READ-ONLY and refuse a create (the app cannot yet "
            f"write ext2/ext3 without damaging it).")
    else:
        put("description.txt",
            f"Support matrix: {NAMES[fs]} inside {NAMES.get(container, 'BitLocker')}{extra}. Unlock, "
            f"flower.jpg byte for byte, create/write/mkdir, remount, delete, remount"
            + (" (second mount with the recovery key)" if container == "bitlocker" else "") + ".")
    print(f"{d}: {os.path.getsize(img) >> 20} MiB")


def main():
    want = sys.argv[1:]
    cases = [c for c in MATRIX if not want or (want[0] == c[0] and (len(want) < 2 or want[1] == c[1]))]
    if not cases:
        raise SystemExit(__doc__)
    with tempfile.TemporaryDirectory() as work:
        for container, fs in cases:
            build(container, fs, work)


if __name__ == "__main__":
    main()
