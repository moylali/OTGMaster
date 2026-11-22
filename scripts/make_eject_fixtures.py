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
    "unmount_idle": (VC, "unmount_idle", "Unmount with nothing in use: no dialog, and it unmounts straight away."),
    "unmount_busy_wait": (VC, "unmount_busy_wait", "Unmount with a file open: the transfer dialog stays up, Unmount happens when the file closes, the dialog closes by itself, no notification."),
    "unmount_busy_dismiss": (VC, "unmount_busy_dismiss", "Dismiss on Unmount's transfer dialog: the card says it is waiting, and a notification comes after the Unmount."),
    "unmount_after_destroy": (VC, "unmount_after_destroy", "Dismiss on Unmount's transfer dialog, then Android destroys the app's screen: the Unmount still happens when the file closes, and its notification comes, without the screen coming back."),
    "unmount_busy_cancel": (VC, "unmount_busy_cancel", "Cancel unmount on the transfer dialog: the drive stays mounted after the file closes."),
    "unmount_card_cancel": (VC, "unmount_card_cancel", "Cancel unmount on the card, after Dismiss: the drive stays mounted after the file closes."),
    "unmount_during_copy": (VC, "unmount_during_copy", "Unmount while a copy writes to the partition: it waits, the copy completes, and the file reads back whole after a remount."),
    "usb_mode_always": (VC, "usb_mode_always", "USB access: the default opens a drive by itself, and each option can be chosen in Settings."),
    "usb_mode_manual": (VC, "usb_mode_manual", "USB access, Manual: nothing is opened until Scan, and not again after the last Unmount."),
    "usb_mode_manual_auto_mount": (PLAIN, "usb_mode_manual_auto_mount", "USB access, Manual: a plain drive mounts by itself only after Scan."),
    "usb_mode_wait_free": (VC, "usb_mode_wait_free", "USB access, Wait for Android: a drive Android left alone is opened after the wait, with no Scan."),
    "usb_mode_wait_held": (VC, "usb_mode_wait_held", "USB access, Wait for Android: a drive Android took stays with it."),
    "usb_mode_scan_warning": (VC, "usb_mode_scan_warning", "USB access, Wait for Android: Scan warns before taking a drive from Android."),
    "slider_hint": (VC, "slider_hint", "The first time the slider closes with a drive mounted, a hint points at the Drives icon; a tap dismisses it and it does not come back."),
    "notification_slider": (VC, "notification_slider", "A tap on the mounted-drives notification opens the app on the slider of mounted drives."),
    "credentials_device_lock": (VC, "credentials_device_lock", "Saved passwords open only with the phone's lock: saved under a PIN with no prompt, unreadable 30 s after the last check, and auto mount unlocks after the PIN."),
    "plain_read_only_default": (PLAIN, "plain_read_only_default", "Read-only mode on: a plain drive mounts read-only by itself, and no file can be created on it."),
    "report_issue": (PLAIN, "report_issue", "Report issue: the details and a random string reach a GitHub issue, which is then deleted; a file name in the log arrives masked, and a line added in the review screen arrives too."),
    "no_space_fat32": (PLAIN, "no_space", "A write bigger than a plain FAT32 drive fails with \"Not enough space\" in the log; a file written before is intact, and the checker is clean."),
    "no_space_exfat": ("encrypt_plain_exfat", "no_space", "A write bigger than a plain exFAT drive fails with \"Not enough space\" in the log; a file written before is intact, and the checker is clean."),
    "no_space_ext4": ("format_plain_ext4", "no_space", "A write bigger than a plain ext4 drive fails with \"Not enough space\" in the log; a file written before is intact, and e2fsck finds no blocks the failed write left used."),
    "no_space_ntfs": ("format_plain_ntfs", "no_space", "A write bigger than a plain NTFS drive fails with \"Not enough space\" in the log; a file written before is intact, and the checker is clean."),
    # DocumentsProvider, one API per case (E2EAutomatedTest.runProviderFlow).
    "provider_roots": (VC, "provider_roots", "Provider queryRoots: one root per mounted drive, with its title, create and isChild flags, and free space within the volume."),
    "provider_children": (VC, "provider_children", "Provider queryChildDocuments: the root lists flower.jpg with its type, size, and write and delete flags."),
    "provider_document": (VC, "provider_document", "Provider queryDocument: the root is a folder, flower.jpg's row matches its listing, and a missing file gets no row."),
    "provider_open_read": (VC, "provider_open_read", "Provider openDocument for reading: flower.jpg reads byte for byte as testdata/flower.jpg."),
    "provider_create_write": (VC, "provider_create_write", "Provider createDocument and openDocument for writing: a file, a folder and a file in it are created, written and read back, and an overwrite truncates."),
    "provider_rename": (VC, "provider_rename", "Provider renameDocument: the new name is listed with the same content, and the old name and id are gone."),
    "provider_delete": (VC, "provider_delete", "Provider deleteDocument: a file and a folder with a file in it are deleted, and neither is listed or answers afterwards."),
    "provider_tree": (VC, "provider_tree", "Provider isChildDocument and findDocumentPath through a folder grant: a file inside reads, one outside is refused, and the path runs folder to file."),
    "provider_busy_roots": ("exfat", "provider_busy_roots", "Provider queryRoots while the exFAT volume's lock is held: answered within 3 s, with the last free space read (ANR of 11-22)."),
    "provider_busy_children": ("exfat", "provider_busy_children", "Provider queryChildDocuments while the exFAT volume's lock is held: a loading listing within 6 s, a change notification once the lock is free, then the full listing."),
    "provider_busy_document": ("exfat", "provider_busy_document", "Provider queryDocument while the exFAT volume's lock is held: a file listed before answers within 6 s, and an unseen one is refused within 16 s, under Android's 20 s limit."),
    "provider_proxy_isolation": ("partitioned_mbr", "provider_proxy_isolation", "Two partitions mounted: a read waiting on one partition's lock does not hold up a read on the other."),
}

# Cases that compare a file read through the provider with testdata/flower.jpg.
VERIFY_FLOWER = {"provider_open_read"}

# Cases the runner checks on GitHub afterwards: the repository the issue should appear in.
GITHUB_ISSUE = {"report_issue": "moylali/OTGMaster"}
# What the issue's Logs section must hold, and must not (E2EAutomatedTest.REPORT_*).
REPORT_LOG_HAS = ["H***ays/B***jpg", "edited-in-review"]
REPORT_LOG_LACKS = ["Barcelona"]

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
        if name in VERIFY_FLOWER:
            open(os.path.join(dst, "verify_flower.txt"), "w").close()
        if name in GITHUB_ISSUE:
            with open(os.path.join(dst, "github_issue.txt"), "w") as out:
                out.write(GITHUB_ISSUE[name] + "\n")
            with open(os.path.join(dst, "log_has.txt"), "w") as out:
                out.write("".join(s + "\n" for s in REPORT_LOG_HAS))
            with open(os.path.join(dst, "log_lacks.txt"), "w") as out:
                out.write("".join(s + "\n" for s in REPORT_LOG_LACKS))
        if flow == "no_space":
            # The runner's host check: the checker, before_full.txt, and no nospace.bin.
            with open(os.path.join(dst, "no_space.txt"), "w") as out:
                out.write("true\n")
        print(name, "from", base)


if __name__ == "__main__":
    main()
