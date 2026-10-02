#!/usr/bin/env python3
"""
Writes a plain filesystem image into a VeraCrypt container's data area.

`veracrypt --create --filesystem=none` needs no root; mounting the container to
format and fill it does. This does the second half without root: it opens the
volume header with the password and PIM (PBKDF2-HMAC-SHA512, AES-XTS), takes the
master key, and XTS-encrypts the plain image into the data area exactly as
VeraCrypt would — data unit = byte offset from the volume start / 512.

    veracrypt -t -c --volume-type=normal vc.img --size=16M --password=P \\
        --encryption=AES --hash=SHA-512 --filesystem=none --pim=1 \\
        --random-source=/dev/urandom --non-interactive
    python3 scripts/fill_veracrypt_volume.py vc.img P 1 --make-plain plain.img
    # ...format and populate plain.img (it has the data area's exact size)...
    python3 scripts/fill_veracrypt_volume.py vc.img P 1 --fill plain.img

AES / SHA-512 only — the combination the E2E fixtures use.
"""
import argparse
import hashlib
import os
import struct

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes


def open_header(path, password, pim):
    with open(path, "rb") as f:
        raw = f.read(512)
    iterations = 15000 + pim * 1000 if pim else 500000
    hk = hashlib.pbkdf2_hmac("sha512", password.encode(), raw[:64], iterations, 64)
    dec = Cipher(algorithms.AES(hk), modes.XTS(bytes(16))).decryptor()
    hdr = dec.update(raw[64:512]) + dec.finalize()
    if hdr[:4] != b"VERA":
        raise SystemExit("wrong password/PIM, or not an AES/SHA-512 VeraCrypt volume")
    area_start, area_size = struct.unpack(">QQ", hdr[44:60])
    return hdr[192:256], area_start, area_size


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("container")
    ap.add_argument("password")
    ap.add_argument("pim", type=int)
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--make-plain", metavar="PLAIN", help="create an empty plain image of the data area's size")
    g.add_argument("--fill", metavar="PLAIN", help="encrypt PLAIN into the data area")
    a = ap.parse_args()
    master, start, size = open_header(a.container, a.password, a.pim)
    if a.make_plain:
        with open(a.make_plain, "wb") as f:
            f.truncate(size)
        print(f"{a.make_plain}: {size} bytes (data area at {start})")
        return
    if os.path.getsize(a.fill) != size:
        raise SystemExit(f"{a.fill} is {os.path.getsize(a.fill)} bytes; the data area is {size}")
    with open(a.fill, "rb") as src, open(a.container, "r+b") as dst:
        pos = 0
        while pos < size:
            chunk = src.read(min(1 << 20, size - pos))
            out = bytearray()
            for i in range(0, len(chunk), 512):
                unit = (start + pos + i) // 512
                enc = Cipher(algorithms.AES(master), modes.XTS(unit.to_bytes(16, "little"))).encryptor()
                out += enc.update(chunk[i:i + 512]) + enc.finalize()
            dst.seek(start + pos)
            dst.write(out)
            pos += len(chunk)
    print(f"{a.container}: {size} bytes written into the data area")


if __name__ == "__main__":
    main()
