#!/usr/bin/env python3
"""
Encrypts a plain image into, or decrypts it out of, a LUKS1/LUKS2 image file —
without root, and without the app's code.

cryptsetup supplies the master key (`luksDump --dump-volume-key`) and the data
segment's offset, sector size and IV start; Python's `cryptography` does
aes-xts-plain64 with one XTS data unit per sector and the tweak counted in
sectors (LUKS2 always uses large-sector IVs). Opening the image for real needs
dm-crypt and root; this is the same transform without either.

    python3 scripts/luks_payload.py encrypt LUKS.img PASSWORD PLAIN.img
    python3 scripts/luks_payload.py decrypt LUKS.img PASSWORD OUT.img
"""
import re
import subprocess
import sys

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes


def layout(image, password):
    dump = subprocess.run(["cryptsetup", "luksDump", "--dump-volume-key", "--batch-mode", "--disable-locks", image],
                          input=password, capture_output=True, text=True)
    if "MK dump:" not in dump.stdout:
        raise SystemExit(f"cryptsetup could not open {image}:\n{dump.stderr}")
    key = bytes.fromhex("".join(re.findall(r"\b[0-9a-f]{2}\b", dump.stdout.split("MK dump:")[1])))
    plain = subprocess.run(["cryptsetup", "luksDump", "--disable-locks", image],
                           capture_output=True, text=True).stdout
    mode = re.search(r"Cipher mode:\s+(\S+)", plain) or re.search(r"cipher:\s+aes-(\S+)", plain)
    if not mode or "xts-plain64" not in mode.group(1):
        raise SystemExit("only aes-xts-plain64 is supported")
    if "Version:" in plain and re.search(r"Version:\s+2", plain):
        offset = int(re.search(r"offset:\s+(\d+) \[bytes\]", plain).group(1))
        sector = int(re.search(r"sector:\s+(\d+) \[bytes\]", plain).group(1))
        iv = re.search(r"IV:\s+(\d+)", plain)
        iv_start = int(iv.group(1)) if iv else 0
    else:
        offset = int(re.search(r"Payload offset:\s+(\d+)", plain).group(1)) * 512
        sector, iv_start = 512, 0
    return key, offset, sector, iv_start


def crypt(encrypt, key, sector, first, data):
    out = bytearray()
    for i in range(0, len(data), sector):
        c = Cipher(algorithms.AES(key), modes.XTS((first + i // sector).to_bytes(16, "little")))
        op = c.encryptor() if encrypt else c.decryptor()
        out += op.update(data[i:i + sector]) + op.finalize()
    return bytes(out)


def main(action, image, password, other):
    key, offset, sector, iv_start = layout(image, password)
    if action == "encrypt":
        with open(other, "rb") as src, open(image, "r+b") as dst:
            pos = 0
            while chunk := src.read(1 << 20):
                if len(chunk) % sector:
                    raise SystemExit(f"plain image is not a whole number of {sector}-byte sectors")
                dst.seek(offset + pos)
                dst.write(crypt(True, key, sector, iv_start + pos // sector, chunk))
                pos += len(chunk)
    elif action == "decrypt":
        with open(image, "rb") as src, open(other, "wb") as dst:
            src.seek(offset)
            pos = 0
            while chunk := src.read(1 << 20):
                chunk = chunk[: len(chunk) // sector * sector]
                dst.write(crypt(False, key, sector, iv_start + pos // sector, chunk))
                pos += len(chunk)
    else:
        raise SystemExit(__doc__)
    print(f"{action}ed {image}: payload at {offset}, {sector}-byte sectors, {len(key) * 8}-bit key")


if __name__ == "__main__":
    if len(sys.argv) != 5:
        raise SystemExit(__doc__)
    main(*sys.argv[1:])
