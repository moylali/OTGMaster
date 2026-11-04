#!/usr/bin/env python3
"""Builds the E2E cases that make backups through the app: testdata/backup_<flow>.

E2EAutomatedTest.runBackupFlow reads backup_flow.txt to know which flow to drive; each one is
described in that case's description.txt, and docs/TEST_DATA.md §6g says how they fit.
scripts/verify_e2e_volume.py then judges the drive with no app in the way: the filesystem's
own checker, and desktop restic on every repository left on it.

One drive layout serves every flow:

    drive_src/      a tree a backup reads from the drive
    other_src/      a second source
    self_src/       one file; a job targets this same folder
    empty/          no files
    shared/         two jobs use it as their target
    desktop_repo/   a repository desktop restic made, which no job knows
    not_a_backup/   has a file named config, but no keys

phone_src/ is pushed with the case and copied to the phone's storage by the test. Its hashes
are in phone_expected.txt, as drive_src's are in drive_expected.txt: "<sha256>\t<path>".

backup_run and the filesystem cases are the same flow on FAT32, exFAT, ext4 and NTFS, and
inside LUKS2 and VeraCrypt (those two reuse the support matrix's images, so run
make_e2e_matrix.py first).

    python3 scripts/make_backup_run_fixtures.py
"""

import hashlib
import os
import random
import shutil
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

from make_backup_fixture import find_restic, restic, SLOT_BYTES  # noqa: E402
from make_e2e_matrix import copy_into, sh, udisks_populate  # noqa: E402

DESKTOP_PASSWORD = "otg-backup-e2e-2026"

# name: (flow, seconds allowed, what it proves)
FLOWS = {
    "backup_run": ("run_full", 3000, "Make backups through the real form and pickers: Incremental and Plain, phone to drive and drive to phone. Back up now stores nothing new, one changed file stores one. Verify reads every blob, Restore compares by hash outside the app, Mount reads the repository the app wrote. Keep rules, a target inside its source, an empty source, Cancel, too big, nearly full with both answers, Remove. Host: filesystem checker clean, restic check --read-data on every repository, copies by hash."),
    "backup_due_prompt": ("due_prompt", 1200, "A due backup when a drive mounts: a notification and the bell, no dialog, nothing started. The bell's list opens the dialog. Not now snoozes a day, the same mount does not ask twice, each of 5, 10, 15 and 30 minutes sets its time, Start now runs in its dialog, a partition of this device that is not mounted is named, manual and not-due backups do not ask, and the real replug asks."),
    "backup_delayed_start": ("delayed_start", 1500, "Later: a real 5 minute wait on the real alarm, 15 minutes with the time moved, 30 minutes with the drive pulled while waiting (dropped, logged, replug does not bring it back), and an end missing when it fires."),
    "backup_due_mounted": ("due_mounted", 1200, "Coming due with the drive already mounted: a notification, no run. Dismissed it returns after 20 hours. Not now, Later, Start now and its body, through their intents. The drive pulled swaps it for the connect reminder. A phone-only backup stays automatic."),
    "backup_reminder": ("reminder", 900, "Coming due with no drive: the reminder at the due time, its wording, the first-run title, 20 hours between, nothing for manual or not-due backups, a good run clears it."),
    "backup_target_checks": ("target_checks", 1500, "Create's form and refusals: no folders, swap, Learn more, same folder, passwords that differ, inside another job, same as another job, a repository as target, Plain or a wrong password on a repository. The right password continues desktop's repository, whose snapshots survive (host). A to B and C to B coexist."),
    "backup_progress_dialog": ("progress_dialog", 1500, "The progress dialog from Create, Back up now, the row's link and the notification. Hide goes to the main screen with the run and notification going, Cancel stops it, the result waits until closed."),
    "backup_mount_progress": ("mount_progress", 900, "Mount backup goes straight to the Files picker, with no tab in Manage backups. A reminder's card and its History entry have Mount, which does nothing with the target's drive pulled and works again on replug. Two rows on one folder, its History entry and the picker make one mount. Each tap opens the slider and flashes the card, already mounted or not, even after the slider was closed mid flash. A wrong saved password asks. One of this phone's backups mounts with its saved password, others after asking; signs of progress after choosing a folder and in the password dialog. Each lands in the slider on Backups."),
    "backup_schedule_status": ("schedule_status", 1200, "The frequency dropdown on a reminder's card, and what its row says: Next with a delay and a snooze, Due now, Due now and which drive, Backup in progress, Waiting for another backup, Corrupted."),
    "backup_remove_schedule": ("remove_schedule", 900, "Remove reminder: warns about the password, Cancel keeps it, Remove clears job, password, delay and notifications, keeps the backup, and releases a folder grant only once nothing uses it."),
    "backup_manual_history": ("manual_history", 900, "A manual backup: offered on the card, not under Reminders, its newest History entry carries Run again, Verify, Restore, Show password and Remove, and it never asks."),
    "backup_show_password": ("show_password", 600, "Show password: refused with no screen lock, nothing after a cancelled check, shown after the PIN, the same from the Remove dialog."),
    "backup_recover_access": ("recover_access", 900, "What a new phone lacks: a missing password is entered and checked against the repository, a lost phone-folder grant is chosen again, and both then back up."),
    "backup_verify_damaged": ("verify_damaged", 1500, "Corrupted: Verify on a damaged pack and a changed copy, the state on the row and in History, no more runs asked, the offer with the source away, Recreate keeps the damaged folder and makes a good backup, a missing repository, damage found by a restore raises the offer. Host: the damaged repositories fail restic check and the new ones pass."),
    "backup_interrupted": ("interrupted", 900, "The drive pulled mid-backup: a failure, not Corrupted. Replugged, the next run completes and every blob verifies. Host: restic check on what is left."),
    "encrypt_form_rules": ("encrypt_form", 600, "The Encrypt form puts each problem on its field, with the reason beneath: a short password with a low PIM marks both, SHA-512's limit is 485 and Argon2's is 12 as desktop VeraCrypt has them, passwords or PIMs that differ. Encrypt does not go ahead. Nothing is written."),
}
# name: (filesystem, flow) on a plain drive
FILESYSTEMS = {"backup_run_exfat": "exfat", "backup_run_ext4": "ext4", "backup_run_ntfs": "ntfs"}
MAIN_SCREEN = "The main screen around a mount: an unlock the form did not start shows as in progress on the form's button, a tap and a second request do not start another, and the partition mounts once. Manage backups is below the form and the bell left of Settings. The mount opens the slider on Drives; Back, Close and a drag close it, the top bar icon and a swipe to the left reopen it, Backups lists nothing. A wide screen keeps its pane with the same tabs."
# name: (matrix case whose image it reuses)
CONTAINERS = {"backup_run_luks2": "m_luks2_ext4", "backup_run_veracrypt": "m_vc_exfat"}
# name: (matrix case whose image it reuses, flow, seconds allowed, what it proves)
OTHER = {"main_screen": ("m_vc_exfat", "main_screen", 900, MAIN_SCREEN)}
CORE = "Incremental and Plain from the phone onto {}, through the real form: Back up now, Verify, Restore by hash, Mount. Host: filesystem checker clean, restic check --read-data, copies by hash."


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)


def source_tree(top, seed):
    rnd = random.Random(seed)
    write(os.path.join(top, "a.txt"), f"Top-level file {seed}.\n".encode())
    write(os.path.join(top, "data.bin"), rnd.randbytes(1 << 20))
    write(os.path.join(top, "file with spaces.txt"), b"A name with spaces.\n")
    write(os.path.join(top, "sub", "b.bin"), rnd.randbytes(3 << 20))
    write(os.path.join(top, "sub", "deeper", "c.txt"), b"Two folders down.\n")


def expected_lines(top):
    lines = []
    for folder, _, names in sorted(os.walk(top)):
        for name in sorted(names):
            path = os.path.join(folder, name)
            with open(path, "rb") as f:
                lines.append(f"{hashlib.sha256(f.read()).hexdigest()}\t{os.path.relpath(path, top)}")
    return lines


def drive_layout(top, binary):
    source_tree(os.path.join(top, "drive_src"), 1)
    write(os.path.join(top, "other_src", "other.txt"), b"The other source.\n")
    write(os.path.join(top, "self_src", "only.txt"), b"The only file here.\n")
    os.makedirs(os.path.join(top, "empty"))
    write(os.path.join(top, "shared", ".keep"), b"")
    write(os.path.join(top, "not_a_backup", "config"), b"A file named config is not a repository.\n")
    repo = os.path.join(top, "desktop_repo")
    env_src = os.path.join(os.path.dirname(top), "desktop_src")
    write(os.path.join(env_src, "from_desktop.txt"), b"Backed up by desktop restic.\n")
    os.environ["RESTIC_PASSWORD_OVERRIDE"] = DESKTOP_PASSWORD
    run = lambda *a, **k: sh_restic(binary, repo, *a, **k)
    run("init", "--repository-version", "2")
    run("backup", ".", "--time", "2026-01-01 10:00:00", cwd=env_src)
    write(os.path.join(env_src, "second.txt"), b"Second snapshot.\n")
    run("backup", ".", "--time", "2026-01-01 11:00:00", cwd=env_src)
    return run("snapshots", "--json", "--no-lock")


def sh_restic(binary, repo, *args, cwd=None):
    import subprocess
    env = dict(os.environ, RESTIC_PASSWORD=DESKTOP_PASSWORD, RESTIC_REPOSITORY=repo)
    p = subprocess.run([binary, "--no-cache", *args], cwd=cwd, env=env, capture_output=True, text=True)
    if p.returncode != 0:
        raise SystemExit(f"restic {' '.join(args)} failed:\n{p.stdout}{p.stderr}")
    return p.stdout


def make_image(fs, img, tree, work):
    with open(img, "wb") as f:
        f.truncate(SLOT_BYTES)
    label = "OTGBACKUP"
    if fs == "fat32":
        sh("mkfs.fat", "-F", "32", "-n", label, img)
        udisks_populate(img, tree)
    elif fs == "exfat":
        sh("mkfs.exfat", "-L", label, img)
        udisks_populate(img, tree)
    elif fs == "ext4":
        sh("mkfs.ext4", "-q", "-F", "-m", "0", "-L", label, "-E", "lazy_itable_init=0,lazy_journal_init=0", "-d", tree, img)
    elif fs == "ntfs":
        sh("mkntfs", "-F", "-q", "-f", "-L", label, img)
        mnt = os.path.join(work, "mnt")
        os.makedirs(mnt, exist_ok=True)
        sh("ntfs-3g", img, mnt)
        try:
            copy_into(tree, mnt)
        finally:
            sh("fusermount", "-u", mnt)
    else:
        raise SystemExit(f"unknown filesystem {fs}")


def case_files(name, flow, seconds, text, phone_lines, drive_lines, snapshots, plain=True):
    d = os.path.join(ROOT, "testdata", name)
    os.makedirs(d, exist_ok=True)
    def put(n, t):
        with open(os.path.join(d, n), "w") as f:
            f.write(t + "\n")
    put("backup_flow.txt", flow)
    put("description.txt", text)
    put("timeout.txt", str(seconds))
    if plain:
        put("plain.txt", "true")
        put("password.txt", "none")
    put("phone_expected.txt", "\n".join(phone_lines))
    put("drive_expected.txt", "\n".join(drive_lines))
    put("desktop_snapshots.json", snapshots)
    return d


def main():
    binary = find_restic()
    with tempfile.TemporaryDirectory() as work:
        phone = os.path.join(work, "phone_src")
        source_tree(phone, 2)
        phone_lines = expected_lines(phone)
        tree = os.path.join(work, "tree")
        os.makedirs(tree)
        snapshots = drive_layout(tree, binary)
        drive_lines = expected_lines(os.path.join(tree, "drive_src"))

        images = {}
        for fs in ("fat32", "exfat", "ext4", "ntfs"):
            images[fs] = os.path.join(work, f"{fs}.img")
            make_image(fs, images[fs], tree, work)

        def place(name, img, flow, seconds, text, plain=True):
            d = case_files(name, flow, seconds, text, phone_lines, drive_lines, snapshots, plain)
            shutil.copyfile(img, os.path.join(d, "test.img"))
            shutil.rmtree(os.path.join(d, "phone_src"), ignore_errors=True)
            shutil.copytree(phone, os.path.join(d, "phone_src"))
            print(f"{name}: {flow}")
            return d

        for name, (flow, seconds, text) in FLOWS.items():
            d = place(name, images["fat32"], flow, seconds, text)
            if name == "backup_verify_damaged":
                with open(os.path.join(d, "expect_damaged.txt"), "w") as f:
                    f.write("E2E_found\n")
        for name, fs in FILESYSTEMS.items():
            place(name, images[fs], "run_core", 1500, CORE.format(f"a plain {fs} drive"))
        for name, source in CONTAINERS.items():
            src = os.path.join(ROOT, "testdata", source)
            if not os.path.exists(os.path.join(src, "test.img")):
                raise SystemExit(f"{source} is missing: run scripts/make_e2e_matrix.py first")
            d = place(name, os.path.join(src, "test.img"), "run_core", 1500, CORE.format(f"{source}'s encrypted drive"), plain=False)
            for extra in ("password.txt", "pim.txt", "container.txt"):
                if os.path.exists(os.path.join(src, extra)):
                    shutil.copyfile(os.path.join(src, extra), os.path.join(d, extra))
        for name, (source, flow, seconds, text) in OTHER.items():
            src = os.path.join(ROOT, "testdata", source)
            if not os.path.exists(os.path.join(src, "test.img")):
                raise SystemExit(f"{source} is missing: run scripts/make_e2e_matrix.py first")
            d = place(name, os.path.join(src, "test.img"), flow, seconds, text, plain=False)
            for extra in ("password.txt", "pim.txt", "container.txt"):
                if os.path.exists(os.path.join(src, extra)):
                    shutil.copyfile(os.path.join(src, extra), os.path.join(d, extra))


if __name__ == "__main__":
    main()
