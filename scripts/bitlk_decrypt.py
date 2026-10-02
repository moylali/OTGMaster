#!/usr/bin/env python3
"""
Decrypts a BitLocker image to a plain image, independently of the app.

Key and layout come from cryptsetup (`bitlkDump --dump-volume-key`, no root
needed for an image file); the sector crypto is Python's `cryptography`. Nothing
here shares code with app/src/main/cpp/bitlocker, so a volume the app wrote that
decrypts to a clean filesystem here was written correctly, not merely in a way
the app's own decryptor happens to agree with.

    python3 scripts/bitlk_decrypt.py IMAGE PASSWORD OUT.img

Supports AES-XTS and AES-CBC (eboiv); Elephant is left to the app's own tests.
"""
import re
import subprocess
import sys

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes


def dump(image, password):
    p = subprocess.run(["cryptsetup", "bitlkDump", "--dump-volume-key", "--batch-mode", image],
                       input=password.encode(), capture_output=True)
    out = p.stdout.decode()
    key_hex = "".join(re.findall(r"\b[0-9a-f]{2}\b", out.split("MK dump:")[1])) if "MK dump:" in out else ""
    info = subprocess.run(["cryptsetup", "bitlkDump", image], capture_output=True).stdout.decode()
    mode = re.search(r"Cipher mode:\s+(\S+)", info).group(1)
    sector = int(re.search(r"Sector size:\s+(\d+)", info).group(1))
    segs = re.findall(r"\d+: (FVE metadata area|Volume header)\s+Offset:\s+(\d+) \[bytes\]\s+Size:\s+(\d+)", info)
    if not key_hex or not segs:
        raise SystemExit(f"cryptsetup could not open {image}:\n{p.stderr.decode()}")
    meta = [(int(o), int(s)) for t, o, s in segs if t == "FVE metadata area"]
    header = [(int(o), int(s)) for t, o, s in segs if t == "Volume header"][0]
    return bytes.fromhex(key_hex), mode, sector, meta, header


def decrypt_sectors(key, mode, sector, first, data):
    out = bytearray()
    for i in range(0, len(data), sector):
        n = first + i // sector
        if mode == "xts-plain64":
            dec = Cipher(algorithms.AES(key), modes.XTS(n.to_bytes(16, "little"))).decryptor()
        elif mode == "cbc-eboiv":
            off = (n * sector).to_bytes(16, "little")
            iv = Cipher(algorithms.AES(key), modes.ECB()).encryptor().update(off)
            dec = Cipher(algorithms.AES(key), modes.CBC(iv)).decryptor()
        else:
            raise SystemExit(f"unsupported mode {mode}")
        out += dec.update(data[i:i + sector]) + dec.finalize()
    return bytes(out)


def main(image, password, out_path):
    key, mode, sector, meta, (hoff, hsize) = dump(image, password)
    holes = sorted(meta + [(hoff, hsize)])
    with open(image, "rb") as f, open(out_path, "wb") as out:
        f.seek(0, 2)
        size = f.tell()
        out.truncate(size)
        # Relocated first sectors.
        f.seek(hoff)
        out.write(decrypt_sectors(key, mode, sector, hoff // sector, f.read(hsize)))
        # Everything else, in place, between the holes (which stay zero).
        pos = hsize
        for start, length in holes + [(size, 0)]:
            while pos < start:
                n = min(start - pos, 1 << 20)
                f.seek(pos)
                out.seek(pos)
                out.write(decrypt_sectors(key, mode, sector, pos // sector, f.read(n)))
                pos += n
            pos = max(pos, start + length)
    print(f"{out_path}: {mode}, {sector}-byte sectors, {len(key) * 8}-bit key")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    main(*sys.argv[1:])
