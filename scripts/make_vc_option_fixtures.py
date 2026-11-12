#!/usr/bin/env python3
"""Builds one E2E case per option of the VeraCrypt forms, each changing a single option.

The owner's rule: one test per choice in the form, not every combination. Choices another
case already covers are not repeated (docs/TEST_DATA.md §6h lists which covers what).

Encrypt form: enc_opt_*. Each starts as plain FAT32 and goes through testEraseOperations:
Encrypt, remount through the unlock form, host check.

Unlock form: unlock_opt_*. Volumes made by desktop VeraCrypt, so the app is opening what it
did not write. Without root, VeraCrypt's own formatter makes FAT16 at this size, which the
app detects and refuses to mount: reaching "Detected filesystem: FAT16" proves the header and
the data area decrypted with that form's choices, as the fat16 case does. unlock_opt_read_only
is the fat32 case's mountable volume with the read-only switch on.

    python3 scripts/make_vc_option_fixtures.py
"""
import os
import shutil
import subprocess

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TD = os.path.join(ROOT, "testdata")
PASSWORD = "otgmaster-test-password-2026"
ENCRYPT_PASSWORD = "otgmaster-encrypt-test-2026"

# name: erase.txt additions, and what this one case changes
ENCRYPT = {
    "enc_opt_baseline": ({"quick": "true"}, "The baseline: AES, SHA-512, no PIM, a password, exFAT, quick."),
    "enc_opt_serpent": ({"cipher": "Serpent"}, "Cipher: Serpent."),
    "enc_opt_twofish": ({"cipher": "Twofish"}, "Cipher: Twofish."),
    "enc_opt_aes_twofish": ({"cipher": "AES-Twofish"}, "Cipher: AES-Twofish."),
    "enc_opt_aes_twofish_serpent": ({"cipher": "AES-Twofish-Serpent"}, "Cipher: AES-Twofish-Serpent."),
    "enc_opt_serpent_aes": ({"cipher": "Serpent-AES"}, "Cipher: Serpent-AES."),
    "enc_opt_twofish_serpent": ({"cipher": "Twofish-Serpent"}, "Cipher: Twofish-Serpent."),
    "enc_opt_keyfile": ({"keyfiles": "enc1.key"}, "Credentials: a password and one keyfile."),
    "enc_opt_keyfiles_2": ({"keyfiles": "enc1.key,enc2.key"}, "Credentials: a password and two keyfiles."),
    "enc_opt_keyfiles_only": ({"keyfiles": "enc1.key,enc2.key", "password": "none"}, "Credentials: two keyfiles and no password."),
}
# name: cipher, keyfiles, password, pim ("" for none), what this one case changes
UNLOCK = {
    "unlock_opt_aes_twofish": ("AES-Twofish", [], PASSWORD, "1", "Cipher: AES-Twofish."),
    "unlock_opt_serpent_twofish_aes": ("Serpent-Twofish-AES", [], PASSWORD, "1", "Cipher: Serpent-Twofish-AES."),
    "unlock_opt_twofish_serpent": ("Twofish-Serpent", [], PASSWORD, "1", "Cipher: Twofish-Serpent."),
    "unlock_opt_no_pim": ("AES", [], PASSWORD, "", "PIM: left blank, VeraCrypt's default."),
    "unlock_opt_keyfiles_2": ("AES", ["a.key", "b.key"], PASSWORD, "1", "Credentials: a password and two keyfiles."),
    "unlock_opt_keyfiles_only": ("AES", ["a.key", "b.key"], "none", "1", "Credentials: two keyfiles and no password."),
}


def fresh(name):
    d = os.path.join(TD, name)
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d)
    return d


def put(d, name, text):
    with open(os.path.join(d, name), "w") as f:
        f.write(text + "\n")


def key(path):
    with open(path, "wb") as f:
        f.write(os.urandom(64))


def main():
    source = os.path.join(TD, "encrypt_plain_fat32", "test.img")
    if not os.path.exists(source):
        raise SystemExit("testdata/encrypt_plain_fat32/test.img missing: run scripts/make_erase_fixtures.py first")
    for name, (extra, text) in ENCRYPT.items():
        d = fresh(name)
        shutil.copyfile(source, os.path.join(d, "test.img"))
        spec = {"action": "encrypt", "quick": "true", "cipher": "AES", "hash": "SHA-512", "fs": "exfat",
                "password": ENCRYPT_PASSWORD, "label": "ENCOPT"}
        spec.update(extra)
        put(d, "erase.txt", "\n".join(f"{k}={v}" for k, v in spec.items()))
        put(d, "plain.txt", "true")
        put(d, "password.txt", "none")
        put(d, "description.txt", f"Encrypt form, one option: {text} Encrypt, remount through the unlock form, host check.")
        for k in filter(None, spec.get("keyfiles", "").split(",")):
            key(os.path.join(d, k))
        print(name)
    for name, (cipher, keys, password, pim, text) in UNLOCK.items():
        d = fresh(name)
        paths = [os.path.join(d, k) for k in keys]
        for p in paths:
            key(p)
        cmd = ["veracrypt", "-t", "-c", "--volume-type=normal", os.path.join(d, "test.img"), "--size=20M",
               f"--password={'' if password == 'none' else password}", f"--encryption={cipher}", "--hash=SHA-512",
               "--filesystem=FAT", f"--pim={pim or 0}", "--random-source=/dev/urandom", "--non-interactive"]
        if paths:
            cmd.append("--keyfiles=" + ",".join(paths))
        p = subprocess.run(cmd, capture_output=True, text=True)
        if p.returncode != 0:
            raise SystemExit(f"veracrypt failed for {name}:\n{p.stdout}{p.stderr}")
        put(d, "password.txt", password)
        if pim:
            put(d, "pim.txt", pim)
        put(d, "cipher.txt", cipher)
        put(d, "hash.txt", "SHA-512")
        put(d, "expects_error.txt", "true")
        put(d, "expected_fs.txt", "FAT16")
        put(d, "description.txt", f"Unlock form, one option, on a volume desktop VeraCrypt made: {text} Reaching the FAT16 refusal proves it decrypted.")
        print(name)
    # The backup header (review of 2026-11-11, V05): VeraCrypt keeps it in the last 128 KiB
    # of the host volume, and the app looks for it at the end of the device, so this volume
    # fills the E2E slot exactly (64 MiB). Its main header is then overwritten: without the
    # switch it reads as a wrong password, with it the volume decrypts.
    d = fresh("unlock_opt_backup_header")
    img = os.path.join(d, "test.img")
    p = subprocess.run(["veracrypt", "-t", "-c", "--volume-type=normal", img, "--size=64M",
                        f"--password={PASSWORD}", "--encryption=AES", "--hash=SHA-512", "--filesystem=FAT",
                        "--pim=1", "--random-source=/dev/urandom", "--non-interactive"], capture_output=True, text=True)
    if p.returncode != 0:
        raise SystemExit(f"veracrypt failed for unlock_opt_backup_header:\n{p.stdout}{p.stderr}")
    with open(img, "r+b") as f:
        f.write(b"\x5a" * 512)
    put(d, "password.txt", PASSWORD)
    put(d, "pim.txt", "1")
    put(d, "cipher.txt", "AES")
    put(d, "hash.txt", "SHA-512")
    put(d, "backup_header_switch.txt", "true")
    put(d, "expects_error.txt", "true")
    put(d, "expected_fs.txt", "FAT16")
    put(d, "description.txt", "Unlock form, one option: Use backup header, on a 64 MiB volume desktop VeraCrypt made whose main header was then overwritten. Reaching the FAT16 refusal proves the backup header decrypted.")
    print("unlock_opt_backup_header")

    d = fresh("unlock_opt_read_only")
    for n in ("test.img", "password.txt", "pim.txt", "cipher.txt", "hash.txt"):
        shutil.copyfile(os.path.join(TD, "fat32", n), os.path.join(d, n))
    put(d, "read_only.txt", "true")
    put(d, "read_only_switch.txt", "true")
    put(d, "verify_flower.txt", "true")
    put(d, "description.txt", "Unlock form, one option: the read-only switch on. It mounts READ-ONLY and refuses a create.")
    print("unlock_opt_read_only")


if __name__ == "__main__":
    main()
