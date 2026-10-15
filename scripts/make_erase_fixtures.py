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
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

from make_e2e_matrix import make_fs  # noqa: E402

SLOT_BYTES = 64 << 20


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
    for case in ("format_plain_fat32", "encrypt_plain_fat32", "encrypt_plain_exfat"):
        with open(os.path.join(td, case, "password.txt"), "w") as f:
            f.write("unused\n")
    with tempfile.TemporaryDirectory() as work:
        for case, fs in (("format_plain_fat32", "fat32"), ("encrypt_plain_fat32", "fat32"),
                         ("encrypt_plain_exfat", "exfat")):
            img = os.path.join(td, case, "test.img")
            make_fs(fs, img, SLOT_BYTES, work)
            print(f"{case}: plain {fs}, {SLOT_BYTES >> 20} MiB")


if __name__ == "__main__":
    main()
