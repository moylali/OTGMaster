#!/usr/bin/env python3
"""
Writes a UDIF disk image (.dmg) holding a raw disk image, for Container Master's tests: no Mac and
no hdiutil on this host. Chunks of 1 MiB (2,048 sectors), each written as the type the cycle
names, so one image exercises zero-fill, raw, zlib and bzip2 chunks:

    python3 scripts/make_udif.py IN.img OUT.dmg [--types zlib,raw,bzip2,zero]

A chunk that is all zeros is always written as zero fill, as hdiutil does. The layout follows
Apple's UDIF as dmg2img and libmodi document it; qemu-img (in the Android emulator's folder)
reads the result independently: qemu-img convert -f dmg -O raw OUT.dmg check.img.
"""
import argparse
import base64
import bz2
import os
import struct
import zlib

SECTOR = 512
CHUNK_SECTORS = 2048
TYPES = {"zero": 0x00000000, "raw": 0x00000001, "zlib": 0x80000005, "bzip2": 0x80000006}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("out")
    ap.add_argument("--types", default="zlib,raw,bzip2")
    ap.add_argument("--name", default="disk image (Apple_HFS : 1)")
    a = ap.parse_args()
    cycle = a.types.split(",")
    data = open(a.src, "rb").read()
    if len(data) % SECTOR:
        data += b"\0" * (SECTOR - len(data) % SECTOR)
    sectors = len(data) // SECTOR

    fork = bytearray()
    entries = []
    for i, first in enumerate(range(0, sectors, CHUNK_SECTORS)):
        n = min(CHUNK_SECTORS, sectors - first)
        chunk = data[first * SECTOR:(first + n) * SECTOR]
        kind = "zero" if chunk.count(0) == len(chunk) else cycle[i % len(cycle)]
        payload = {"zero": b"", "raw": chunk, "zlib": zlib.compress(chunk, 6), "bzip2": bz2.compress(chunk, 9)}[kind]
        entries.append((TYPES[kind], first, n, len(fork), len(payload)))
        fork += payload
    entries.append((0xFFFFFFFF, sectors, 0, len(fork), 0))

    mish = bytearray()
    mish += struct.pack(">4sIQQQII", b"mish", 1, 0, sectors, 0, 0x208, 0)
    mish += b"\0" * 24                      # reserved
    mish += struct.pack(">II", 2, 32) + b"\0" * 128   # checksum (CRC-32 type, not filled)
    mish += struct.pack(">I", len(entries))
    for t, s, n, off, ln in entries:
        mish += struct.pack(">IIQQQQ", t, 0, s, n, off, ln)

    plist = (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n'
        '<plist version="1.0">\n<dict>\n\t<key>resource-fork</key>\n\t<dict>\n\t\t<key>blkx</key>\n\t\t<array>\n'
        '\t\t\t<dict>\n'
        '\t\t\t\t<key>Attributes</key>\n\t\t\t\t<string>0x0050</string>\n'
        f'\t\t\t\t<key>CFName</key>\n\t\t\t\t<string>{a.name}</string>\n'
        f'\t\t\t\t<key>Data</key>\n\t\t\t\t<data>\n{base64.encodebytes(bytes(mish)).decode()}\t\t\t\t</data>\n'
        '\t\t\t\t<key>ID</key>\n\t\t\t\t<string>0</string>\n'
        f'\t\t\t\t<key>Name</key>\n\t\t\t\t<string>{a.name}</string>\n'
        '\t\t\t</dict>\n\t\t</array>\n\t</dict>\n</dict>\n</plist>\n'
    ).encode()

    xml_off = len(fork)
    koly = bytearray(512)
    struct.pack_into(">4sIII", koly, 0, b"koly", 4, 512, 1)
    struct.pack_into(">QQQ", koly, 16, 0, 0, len(fork))            # running offset, data fork offset, length
    struct.pack_into(">QQ", koly, 40, 0, 0)                        # resource fork
    struct.pack_into(">II", koly, 56, 1, 1)                        # segment 1 of 1
    struct.pack_into(">QQ", koly, 216, xml_off, len(plist))
    struct.pack_into(">I", koly, 488, 1)                           # image variant: device image
    struct.pack_into(">Q", koly, 492, sectors)
    with open(a.out, "wb") as f:
        f.write(fork)
        f.write(plist)
        f.write(koly)


if __name__ == "__main__":
    main()
