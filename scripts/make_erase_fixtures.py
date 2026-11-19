#!/usr/bin/env python3
"""
Builds the E2E cases for Format and Encrypt on a mounted card (E2EAutomatedTest.
testEraseOperations), without root:

  format_vc_exfat      the exfat case's VeraCrypt AES/SHA-512 exFAT volume (10 MB),
                       Format, quick: same container, same password, empty exFAT.
  format_plain_fat32   plain FAT32, 64 MiB, whole device, Format, full.
  encrypt_plain_fat32  plain FAT32, 64 MiB -> VeraCrypt AES / SHA-512, PIM 1, exFAT inside,
                       full. The host decrypts it with Python's AES afterwards.
  encrypt_plain_exfat  plain exFAT, 64 MiB -> VeraCrypt Serpent-Twofish-AES / Argon2, PIM 1,
                       FAT32 inside, full. No host tool decrypts Argon2 VeraCrypt; the
                       host check is that no byte of the old files is left on the drive.
  format_plain_ext4    plain ext4, 64 MiB, Format, quick: the app's own ext4.
  format_plain_ntfs    plain NTFS, 64 MiB, Format, full: the app's own mkntfs.
  encrypt_plain_ext4   plain ext4 -> VeraCrypt AES / SHA-512, PIM 1, NTFS inside, full.
  encrypt_plain_ntfs   plain NTFS -> VeraCrypt AES / SHA-512, PIM 1, ext4 inside, full.
  encrypt_luks2_ext4   plain FAT32 -> LUKS2 (AES-XTS, Argon2id), ext4 inside, full. The host
                       has cryptsetup unlock the keyslot and decrypts the data in Python.
  format_bitlocker_ntfs  BitLocker XTS-AES-256 around NTFS, 64 MiB, with BitLocker's metadata
                       inside the volume (as Windows puts it, scripts/make_bitlocker_image.py
                       --metadata-at), Format to exFAT, full. The host decrypts it with
                       cryptsetup's key and Python's AES.
  disconnect           the fat32 case's VeraCrypt AES/SHA-512 FAT32 volume (10 MB), for
                       E2EAutomatedTest.runDisconnectCase: not an erase case, but built the same
                       way as format_vc_exfat, so here too. Encrypted, because a plain card
                       has no Unmount, and the disconnected card must take it away.

Each starts holding the usual mount tree (flower.jpg and friends), so the test can see
the old files go. 64 MiB is the emulator's USB slot, and enough for FAT32 (which needs
65,525 clusters; the 10 MB VeraCrypt FAT32 fixtures are below that and Format refuses
them, as it should).

description.txt, erase.txt and plain.txt are committed; this writes the gitignored
rest — test.img, password.txt, and pim.txt for format_vc_exfat and disconnect.

    python3 scripts/make_erase_fixtures.py
"""
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

from make_e2e_matrix import make_fs  # noqa: E402

SLOT_BYTES = 64 << 20
# BitLocker's three metadata areas and the moved first sectors, inside the 64 MiB volume:
# past the NTFS's $MFTMirr and $LogFile at the middle, and checked free before they go in.
BITLOCKER_AT = (44 << 20, 52 << 20, 60 << 20, (52 << 20) + 65536)
BITLOCKER_PASSWORD = "password123"


def make_bitlocker_ntfs(case_dir, work):
    plain = os.path.join(work, "bl-plain.img")
    make_fs("ntfs", plain, SLOT_BYTES, work)
    for at, size in [(a, 65536) for a in BITLOCKER_AT[:3]] + [(BITLOCKER_AT[3], 8192)]:
        p = subprocess.run(["ntfscluster", "-f", "-s", f"{at // 512}-{(at + size) // 512 - 1}", plain],
                           capture_output=True, text=True)
        if p.returncode != 0 or "Inode" in p.stdout:
            raise SystemExit(f"the NTFS uses the sectors BitLocker's metadata would take at {at}:\n{p.stdout}{p.stderr}")
    subprocess.run([sys.executable, os.path.join(HERE, "make_bitlocker_image.py"), plain,
                    os.path.join(case_dir, "test.img"), "--password", BITLOCKER_PASSWORD, "--cipher", "xts256",
                    "--metadata-at", ",".join(str(a) for a in BITLOCKER_AT), "--verify"], check=True)
    with open(os.path.join(case_dir, "password.txt"), "w") as f:
        f.write(BITLOCKER_PASSWORD + "\n")


def main():
    td = os.path.join(ROOT, "testdata")
    for source in ("exfat", "fat32"):
        if not os.path.exists(os.path.join(td, source, "test.img")):
            raise SystemExit(f"testdata/{source}/test.img missing: generate the VeraCrypt fixtures first")
    # password.txt and pim.txt are generated with the image (gitignored), so they travel with it.
    for case, source in (("format_vc_exfat", "exfat"), ("disconnect", "fat32")):
        for name in ("test.img", "password.txt", "pim.txt"):
            shutil.copyfile(os.path.join(td, source, name), os.path.join(td, case, name))
        print(f"{case}: copy of the {source} case")
    # The plain cases have no password; the runner still reads the file.
    plain_cases = (("format_plain_fat32", "fat32"), ("encrypt_plain_fat32", "fat32"),
                   ("encrypt_plain_exfat", "exfat"), ("format_plain_ext4", "ext4"),
                   ("format_plain_ntfs", "ntfs"), ("encrypt_plain_ext4", "ext4"),
                   ("encrypt_plain_ntfs", "ntfs"), ("encrypt_luks2_ext4", "fat32"))
    for case, _ in plain_cases:
        with open(os.path.join(td, case, "password.txt"), "w") as f:
            f.write("unused\n")
    with tempfile.TemporaryDirectory() as work:
        for case, fs in plain_cases:
            img = os.path.join(td, case, "test.img")
            make_fs(fs, img, SLOT_BYTES, work)
            print(f"{case}: plain {fs}, {SLOT_BYTES >> 20} MiB")
        make_bitlocker_ntfs(os.path.join(td, "format_bitlocker_ntfs"), work)
        print("format_bitlocker_ntfs: BitLocker XTS-256 around NTFS, metadata inside")


if __name__ == "__main__":
    main()
