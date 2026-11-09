#!/usr/bin/env python3
"""Builds the E2E cases for VeraCrypt hidden volumes, without root (docs/TEST_DATA.md §6i).

Every volume is made by desktop VeraCrypt, so the app is opening what it did not write. The
real test of the XTS tweak is the hidden volume's data: it sits deep in the outer volume, at
an offset only its own header gives.

The outer volume is exFAT, filled up to just below the hidden volume so that a small write
lands clear of it and a larger one has to reach it. The hidden volume is FAT32, holding a file
that exists nowhere else.

    python3 scripts/make_hidden_volume_fixtures.py
"""
import os
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from fill_veracrypt_volume import open_header  # noqa: E402
import make_e2e_matrix as matrix  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TD = os.path.join(ROOT, "testdata")
FILL = os.path.join(ROOT, "scripts/fill_veracrypt_volume.py")

OUTER_PASSWORD = "outer-volume-password-2026"
HIDDEN_PASSWORD = "hidden-volume-password-2026"
PIM = "1"
OUTER_SIZE = "32M"
HIDDEN_SIZE = "8M"
FILLER_BYTES = 20 * 1024 * 1024     # the hidden volume starts at 23.75 MiB of the outer one


def sh(*cmd):
    p = subprocess.run(cmd, capture_output=True, text=True)
    if p.returncode != 0:
        raise SystemExit(f"{cmd[0]} failed: {' '.join(cmd)}\n{p.stdout}{p.stderr}")


def veracrypt_create(img, kind, size, password, cipher="AES", hash_="SHA-512", pim=PIM, fs="none", keyfile=None):
    cmd = ["veracrypt", "-t", "-c", f"--volume-type={kind}", img, f"--size={size}", f"--password={password}",
           f"--encryption={cipher}", f"--hash={hash_}", f"--filesystem={fs}", f"--pim={pim}",
           "--random-source=/dev/urandom", "--non-interactive"]
    if keyfile:
        cmd.append(f"--keyfiles={keyfile}")
    sh(*cmd)


def fill(img, password, offset, work, build):
    plain = os.path.join(work, "plain.img")
    sh(sys.executable, FILL, img, password, PIM, "--header-offset", str(offset), "--make-plain", plain)
    build(plain)
    sh(sys.executable, FILL, img, password, PIM, "--header-offset", str(offset), "--fill", plain)
    os.remove(plain)


def populate(img, work, extra):
    src = os.path.join(work, "tree")
    shutil.rmtree(src, ignore_errors=True)
    matrix.tree(src)
    extra(src)
    matrix.udisks_populate(img, src)


def build_outer(img, work):
    def extra(src):
        with open(os.path.join(src, "decoy.txt"), "w") as f:
            f.write("The outer volume's decoy\n")
        with open(os.path.join(src, "filler.bin"), "wb") as f:
            f.write(os.urandom(FILLER_BYTES))

    sh("mkfs.exfat", "-L", "OUTERVOL", img)
    populate(img, work, extra)


def build_hidden(img, work):
    def extra(src):
        with open(os.path.join(src, "hidden_marker.txt"), "w") as f:
            f.write("Only the hidden volume has this file\n")

    sh("mkfs.fat", "-F", "32", "-n", "HIDDENVOL", img)
    populate(img, work, extra)


def fresh(name):
    d = os.path.join(TD, name)
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d)
    return d


def put(d, name, text):
    with open(os.path.join(d, name), "w") as f:
        f.write(text + "\n")


def case(name, description, base, **files):
    d = fresh(name)
    shutil.copyfile(base, os.path.join(d, "test.img"))
    files.setdefault("password", OUTER_PASSWORD)
    files.setdefault("pim", PIM)
    for key, value in files.items():
        put(d, f"{key}.txt", value)
    put(d, "description.txt", description)
    print(name)
    return d


def hidden_pair(work):
    img = os.path.join(work, "pair.img")
    veracrypt_create(img, "normal", OUTER_SIZE, OUTER_PASSWORD)
    fill(img, OUTER_PASSWORD, 0, work, lambda p: build_outer(p, work))
    veracrypt_create(img, "hidden", HIDDEN_SIZE, HIDDEN_PASSWORD)
    fill(img, HIDDEN_PASSWORD, 65536, work, lambda p: build_hidden(p, work))
    _, start, size = open_header(img, HIDDEN_PASSWORD, int(PIM), 65536)
    print(f"hidden volume data at {start}, {size} bytes")
    return img


def outer_only(work):
    """The filled outer volume, for cases that add their own hidden volume."""
    img = os.path.join(work, "outer.img")
    veracrypt_create(img, "normal", OUTER_SIZE, OUTER_PASSWORD)
    fill(img, OUTER_PASSWORD, 0, work, lambda p: build_outer(p, work))
    return img


def main():
    with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as work:
        pair = hidden_pair(work)
        flower = {"verify_flower": "true"}
        case("hidden_unlock", "The hidden volume's password opens the hidden volume: its own file, flower.jpg byte for byte, HIDDEN on the card.",
             pair, password=HIDDEN_PASSWORD, expected_volume_tag="HIDDEN", expect_file="hidden_marker.txt", **flower)
        case("hidden_outer_unlock", "The outer volume's password on the same drive opens the outer volume, with no sign of a hidden one.",
             pair, expected_volume_tag="NONE", expect_file="decoy.txt", **flower)
        case("hidden_write", "A hidden volume is written: create, persist over remounts, delete, then the host decrypts it and runs fsck.",
             pair, password=HIDDEN_PASSWORD, write_test="true", hidden_header="true")
        case("hidden_wrong_password", "A password that opens neither volume is refused, after both headers were tried.",
             pair, password="neither-volume-password-2026", hidden_flow="wrong_password")
        common = dict(hidden_password=HIDDEN_PASSWORD, hidden_pim=PIM)
        case("hidden_no_auto_mount", "With Auto mount on, a normal mount saves its password, but a protected mount and a hidden volume's password do not, and a protected mount removes one saved earlier.",
             pair, hidden_flow="no_auto_mount", **common)
        case("hidden_protect_safe", "Protection on, a write clear of the hidden volume: mounts OUTER, the write lands, and the host finds the hidden volume unchanged and both filesystems clean.",
             pair, hidden_flow="protect_safe", hidden_check="outer_clean", **common)
        case("hidden_protect_trigger", "Protection on, a write that reaches the hidden volume: refused, OUTER(!) and READ-ONLY on the card, every later write refused, the warning shown, and the hidden volume unchanged on the host.",
             pair, hidden_flow="protect_trigger", hidden_check="hidden_only", **common)
        case("hidden_protect_wrong_password", "Protection on with a wrong hidden password: the outer volume is not mounted, and the option is off again afterwards.",
             pair, hidden_flow="protect_wrong_password", hidden_password="not-the-hidden-password-2026", hidden_pim=PIM)
        case("hidden_protect_main_is_hidden", "Protection on, with the hidden volume's own password as the main one: refused, as VeraCrypt refuses it.",
             pair, password=HIDDEN_PASSWORD, hidden_flow="protect_main_is_hidden", **common)
        case("hidden_protect_read_only", "Mounting read-only unticks and greys out hidden volume protection, as VeraCrypt's dialog does.",
             pair, hidden_flow="protect_read_only", **common)

        # One case per option of the protection fields, each changing one.
        base = outer_only(work)

        def protected(name, text, cipher="AES", hash_="SHA-512", pim=PIM, key=False, **files):
            d = fresh(name)
            img = os.path.join(d, "test.img")
            shutil.copyfile(base, img)
            keyfile = None
            if key:
                keyfile = os.path.join(d, "hidden.key")
                with open(keyfile, "wb") as f:
                    f.write(os.urandom(64))
            veracrypt_create(img, "hidden", HIDDEN_SIZE, HIDDEN_PASSWORD, cipher, hash_, pim, keyfile=keyfile)
            put(d, "password.txt", OUTER_PASSWORD)
            put(d, "pim.txt", PIM)
            put(d, "hidden_flow.txt", "protect_option")
            put(d, "hidden_password.txt", HIDDEN_PASSWORD)
            if pim != "0":
                put(d, "hidden_pim.txt", pim)
            if cipher != "AES":
                put(d, "hidden_cipher.txt", cipher)
            if hash_ != "SHA-512":
                put(d, "hidden_hash.txt", hash_)
            if key:
                put(d, "hidden_keyfile.txt", "hidden.key")
            put(d, "description.txt", f"Hidden volume protection fields, one option: {text} Mounts as OUTER.")
            print(name)

        protected("hidden_protect_pim", "a PIM of its own.", pim="7")
        protected("hidden_protect_keyfile", "a password and a keyfile.", key=True)
        protected("hidden_protect_cipher", "a cipher unlike the outer volume's.", cipher="Serpent-Twofish-AES")
        protected("hidden_protect_hash", "a hash unlike the outer volume's.", hash_="Argon2")

        # One case per option of the unlock form on a hidden volume. VeraCrypt's own formatter
        # makes FAT16 at this size without root, which the app refuses: reaching that refusal
        # proves the hidden header and the data area both decrypted with those choices.
        def hidden_fat16(name, text, cipher="AES", hash_="SHA-512", pim=PIM, key=False):
            d = fresh(name)
            img = os.path.join(d, "test.img")
            veracrypt_create(img, "normal", OUTER_SIZE, OUTER_PASSWORD)
            keyfile = None
            if key:
                keyfile = os.path.join(d, "test.key")
                with open(keyfile, "wb") as f:
                    f.write(os.urandom(64))
            veracrypt_create(img, "hidden", "16M", HIDDEN_PASSWORD, cipher, hash_, pim, fs="FAT", keyfile=keyfile)
            put(d, "password.txt", HIDDEN_PASSWORD)
            if pim != "0":
                put(d, "pim.txt", pim)
            if cipher != "AES":
                put(d, "cipher.txt", cipher)
            if hash_ != "SHA-512":
                put(d, "hash.txt", hash_)
            put(d, "expects_error.txt", "true")
            put(d, "expected_fs.txt", "FAT16")
            put(d, "description.txt", f"Unlock form on a hidden volume, one option: {text} Reaching the FAT16 refusal proves it decrypted.")
            print(name)

        hidden_fat16("hidden_opt_pim", "a PIM.", pim="7")
        hidden_fat16("hidden_opt_keyfile", "a password and a keyfile.", key=True)
        hidden_fat16("hidden_opt_cipher", "Serpent-Twofish-AES.", cipher="Serpent-Twofish-AES")
        hidden_fat16("hidden_opt_hash", "Argon2.", hash_="Argon2")


if __name__ == "__main__":
    main()
