#!/usr/bin/env python3
"""Builds the E2E cases for Eject and the USB access setting (docs/TEST_DATA.md).

These cases test what the app does with a drive, so they reuse existing volumes: each case is
a copy of another case's image and credentials, plus a flow.

    python3 scripts/make_eject_fixtures.py
"""
import os
import shutil

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TD = os.path.join(ROOT, "testdata")

VC = "fat32"
PLAIN = "format_plain_fat32"

# name: (base case, flow, what it proves)
CASES = {
    "eject_single": (VC, "single", "Eject with one partition and nothing in use: no dialog, the card goes, and the drive is not opened again."),
    "eject_rescan": (VC, "rescan", "After Eject, Scan USB devices opens the drive again."),
    "eject_multi_confirm": ("partitioned_mbr", "multi_confirm", "Eject with two partitions asks first, Cancel changes nothing, and a partition waiting on the unlock form goes as well."),
    "eject_vc": ("exfat", "message_encrypted", "Eject of a VeraCrypt volume, and the warning that Android may call it corrupted."),
    "eject_luks": ("m_luks2_fat32", "message_encrypted", "Eject of a LUKS volume, and the warning that Android may call it corrupted."),
    "eject_bitlocker": ("m_bitlocker_fat32", "message_encrypted", "Eject of a BitLocker volume, and the warning that Android may call it corrupted."),
    "eject_plain": (PLAIN, "message_plain", "Eject of a plain FAT32 drive, and the advice to use Android's Eject."),
    "eject_busy_wait": (VC, "busy_wait", "A file open: the transfer dialog stays up, Eject happens when the file closes, the dialog closes by itself, no notification."),
    "eject_busy_dismiss": (VC, "busy_dismiss", "Dismiss on the transfer dialog: the card says it is waiting, and a notification comes after the Eject."),
    "eject_busy_cancel": (VC, "busy_cancel", "Cancel eject on the transfer dialog: the drive stays mounted after the file closes."),
    "eject_card_cancel": (VC, "card_cancel", "Cancel eject on the card, after Dismiss: the drive stays mounted after the file closes."),
    "eject_new_transfer": (VC, "new_transfer", "A second file opened while waiting: Eject waits for that one too."),
    "eject_unplug_pending": (VC, "unplug_pending", "The drive goes away while Eject waits: the Eject is dropped and the dialog closes."),
    "usb_mode_always": (VC, "usb_mode_always", "USB access: the default opens a drive by itself, and each option can be chosen in Settings."),
    "usb_mode_manual": (VC, "usb_mode_manual", "USB access, Manual: nothing is opened until Scan, and not again after the last Unmount."),
    "usb_mode_manual_auto_mount": (PLAIN, "usb_mode_manual_auto_mount", "USB access, Manual: a plain drive mounts by itself only after Scan."),
    "usb_mode_wait_free": (VC, "usb_mode_wait_free", "USB access, Wait for Android: a drive Android left alone is opened after the wait, with no Scan."),
    "usb_mode_wait_held": (VC, "usb_mode_wait_held", "USB access, Wait for Android: a drive Android took stays with it."),
    "usb_mode_scan_warning": (VC, "usb_mode_scan_warning", "USB access, Wait for Android: Scan warns before taking a drive from Android."),
}

COPIED = ["test.img", "password.txt", "pim.txt", "cipher.txt", "hash.txt", "plain.txt", "container.txt", "recovery.txt", "boot.txt"]


def main():
    for name, (base, flow, what) in CASES.items():
        src = os.path.join(TD, base)
        dst = os.path.join(TD, name)
        os.makedirs(dst, exist_ok=True)
        for f in COPIED:
            p = os.path.join(src, f)
            if os.path.exists(p):
                shutil.copyfile(p, os.path.join(dst, f))
        with open(os.path.join(dst, "eject_flow.txt"), "w") as out:
            out.write(flow + "\n")
        with open(os.path.join(dst, "description.txt"), "w") as out:
            out.write(what + "\n")
        with open(os.path.join(dst, "timeout.txt"), "w") as out:
            out.write("600\n")
        print(name, "from", base)


if __name__ == "__main__":
    main()
