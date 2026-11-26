#!/usr/bin/env python3
"""
Checks, on the host and without the app's code, a container file Container Master wrote to.

Container Master's E2E write cases write through the Files-app path (saf-probe's write grant)
into a VeraCrypt or LUKS file opened with Read-only mode off, lock it, and pull the file back.
A write that round-trips through the app's own reader proves little: a wrong IV or offset reads
back fine through the same code. So this:
  1. decrypts the file with tools that share no code with the app: the VeraCrypt header opened
     in Python (verify_e2e_volume.vc_decrypt), or cryptsetup's layout with Python's AES for LUKS
     (luks_payload.py);
  2. runs the filesystem's own checker on the plaintext (verify_e2e_volume.check_structure);
  3. reads the written file and an untouched one out of an ext4 plaintext with debugfs, which
     needs no root, and compares their SHA-256 with what the test expects.

    python3 scripts/check_container_write.py CONTAINER VERACRYPT|LUKS PASSWORD PIM \\
        WRITTEN_NAME WRITTEN_SHA256 UNTOUCHED_NAME UNTOUCHED_SHA256

Prints "CLEAN" and exits 0, or prints each problem and exits 1.
"""
import hashlib
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from verify_e2e_volume import check_structure, fs_type, vc_decrypt  # noqa: E402


def sha_inside(img, name):
    with tempfile.TemporaryDirectory() as d:
        out = os.path.join(d, "f")
        p = subprocess.run(["debugfs", "-R", f"dump /{name} {out}", img], capture_output=True, text=True)
        if not os.path.exists(out):
            return None, (p.stdout + p.stderr).strip()[-300:]
        return hashlib.sha256(open(out, "rb").read()).hexdigest(), None


def main():
    if len(sys.argv) != 9:
        raise SystemExit(__doc__)
    container, kind, password, pim, wname, wsha, uname, usha = sys.argv[1:]
    problems = []
    with tempfile.TemporaryDirectory() as d:
        plain = os.path.join(d, "plain.img")
        if kind == "VERACRYPT":
            vc_decrypt(container, password, int(pim), plain)
        elif kind == "LUKS":
            p = subprocess.run([sys.executable, os.path.join(HERE, "luks_payload.py"), "decrypt", container, password, plain],
                               capture_output=True, text=True)
            if p.returncode != 0:
                raise SystemExit(f"independent decryption failed:\n{p.stdout}{p.stderr}")
        else:
            raise SystemExit(f"unknown container kind {kind}")
        fs = fs_type(plain)
        problems += check_structure(fs, plain)
        if fs not in ("ext2", "ext3", "ext4"):
            problems.append(f"the file check reads ext4 with debugfs; this is {fs or 'unrecognised'}")
        else:
            for name, want in ((wname, wsha), (uname, usha)):
                got, err = sha_inside(plain, name)
                if got != want:
                    problems.append(f"{name}: {got or 'missing'} (expected {want}){': ' + err if err else ''}")
    if problems:
        print("\n".join(problems))
        sys.exit(1)
    print("CLEAN")


if __name__ == "__main__":
    main()
