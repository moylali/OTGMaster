#!/usr/bin/env python3
"""
Wraps a plain filesystem image in BitLocker (version 2, as Windows 7+ writes it).

Windows is the only tool that creates BitLocker volumes, and cryptsetup's sample
images have their unused ciphertext zeroed (most of the filesystem inside decrypts
to noise), so neither gives a test fixture with a usable filesystem. This builds
one, and --verify then requires cryptsetup — an independent implementation — to
accept it: parse its metadata, release the volume key for the password and for
the recovery password, and decrypt (through scripts/bitlk_decrypt.py) to exactly
the plain image given.

    python3 scripts/make_bitlocker_image.py PLAIN.img OUT.img \\
        --password password123 \\
        --recovery 111111-222222-333333-444444-555555-666666-777777-888888 \\
        --cipher xts128 --verify

Layout: the plain image keeps its offsets; BitLocker's three 64 KiB metadata
areas and the relocated first 8 KiB sit in a tail after it, outside the
filesystem, so nothing the filesystem writes can land on them. Windows puts them
inside the filesystem as reserved files; readers do not care where they are.

--metadata-at M1,M2,M3,H puts them inside instead, at those byte offsets (4 KiB
aligned), as Windows does: the volume is then exactly the plain image's size, and
whatever the plain image had there is lost (it reads as zeros). Format inside
BitLocker is tested on such volumes, since there the new filesystem has to keep
off them.

--togo writes the volume as BitLocker To Go, the form Windows gives removable
drives: its first sector is a FAT32 boot sector ("MSWIN4.1", "FAT32   " at byte 82,
the discovery volume Windows 7 reads it through) with BitLocker's GUID at byte 424
and the metadata offsets after it, instead of the "-FVE-FS-" header. A reader that
checks for a filesystem before it checks for BitLocker takes it for plain FAT32
(#184). The discovery volume's own files are not written: no reader here uses them.
"""
import argparse
import hashlib
import os
import struct
import subprocess
import sys
import time
import uuid
import zlib

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.ciphers.aead import AESCCM

META = 64 * 1024
HEADER = 8192
GUID_NORMAL = bytes([0x3b, 0xd6, 0x67, 0x49, 0x29, 0x2e, 0xd8, 0x4a,
                     0x83, 0x99, 0xf6, 0xa3, 0x39, 0xe3, 0xd0, 0x01])
CIPHERS = {"cbc128": (0x8002, 16), "cbc256": (0x8003, 32), "xts128": (0x8004, 32), "xts256": (0x8005, 64)}


def filetime():
    return int((time.time() + 11644473600) * 10_000_000)


def stretch(initial, salt):
    block = bytearray(32) + initial + salt + bytes(8)
    for i in range(0x100000):
        block[80:88] = struct.pack("<Q", i)
        block[0:32] = hashlib.sha256(block).digest()
    return bytes(block[0:32])


def password_initial(pw):
    return hashlib.sha256(hashlib.sha256(pw.encode("utf-16-le")).digest()).digest()


def recovery_initial(rp):
    groups = rp.split("-")
    assert len(groups) == 8 and all(len(g) == 6 and int(g) % 11 == 0 for g in groups), "bad recovery password"
    return hashlib.sha256(b"".join(struct.pack("<H", int(g) // 11) for g in groups)).digest()


def entry(type_, value, payload):
    return struct.pack("<HHHH", 8 + len(payload), type_, value, 1) + payload


def seal(key, plaintext):
    """AES-CCM as BitLocker stores it: nonce(12) | tag(16) | ciphertext."""
    nonce = struct.pack("<QI", filetime(), int.from_bytes(os.urandom(4), "little"))
    ct_tag = AESCCM(key, tag_length=16).encrypt(nonce, plaintext, None)
    return nonce + ct_tag[-16:] + ct_tag[:-16]


def key_datum(algo, key):
    """A key wrapped with its 12-byte header: size, type 0, value 1 (key), version 1, algorithm."""
    return struct.pack("<HHHHI", 12 + len(key), 0, 1, 1, algo) + key


def vmk_entry(protection, salt, wrapping_key, vmk):
    sealed = seal(wrapping_key, key_datum(0x2000, vmk))
    payload = (uuid.uuid4().bytes_le + struct.pack("<QHH", filetime(), 0, protection)
               + entry(0, 3, struct.pack("<I", 0x1000) + salt)      # stretch key
               + entry(0, 5, sealed))                                # sealed VMK
    return entry(2, 8, payload)


def startup_key_vmk_entry(guid, key, vmk):
    """A startup-key (external key, .BEK) protector: the VMK sealed directly under the file's key."""
    payload = guid + struct.pack("<QHH", filetime(), 0, 0x0200) + entry(0, 5, seal(key, key_datum(0x2000, vmk)))
    return entry(2, 8, payload)


def bek_file(guid, key, name):
    """A .BEK file as Windows writes it: a 48-byte metadata header, then one external key
    entry (type 6, value 9) holding its GUID, a time, the 32-byte key and the file's name."""
    inner = (guid + struct.pack("<Q", filetime())
             + entry(0, 1, struct.pack("<I", 0x2000) + key)
             + entry(0, 2, name.encode("utf-16-le") + b"\0\0"))
    body = entry(6, 9, inner)
    size = 48 + len(body)
    return struct.pack("<IIII", size, 1, 48, size) + guid + struct.pack("<IHHQ", 1, 0, 0, filetime()) + body


def crypt(encrypt, mode, key, sector, first_sector, data):
    out = bytearray()
    for i in range(0, len(data), sector):
        n = first_sector + i // sector
        if mode.startswith("xts"):
            c = Cipher(algorithms.AES(key), modes.XTS(n.to_bytes(16, "little")))
        else:
            iv = Cipher(algorithms.AES(key), modes.ECB()).encryptor().update((n * sector).to_bytes(16, "little"))
            c = Cipher(algorithms.AES(key), modes.CBC(iv))
        op = c.encryptor() if encrypt else c.decryptor()
        out += op.update(data[i:i + sector]) + op.finalize()
    return bytes(out)


def build(plain_path, out_path, password, recovery, cipher, inside=None, bek_path=None, togo=False):
    algo, key_len = CIPHERS[cipher]
    sector = 512
    plain_size = os.path.getsize(plain_path)
    assert plain_size % 4096 == 0, "plain image must be a multiple of 4 KiB"
    if inside:
        meta_offsets, header_offset = list(inside[:3]), inside[3]
        total = plain_size
        areas = sorted([(o, META) for o in meta_offsets] + [(header_offset, HEADER)])
        for (o, n), (o2, _) in zip(areas, areas[1:] + [(total, 0)]):
            assert o % 4096 == 0 and o >= HEADER and o + n <= o2, f"metadata at {o} overlaps or is misplaced"
    else:
        meta_offsets = [plain_size + i * META for i in range(3)]
        header_offset = plain_size + 3 * META
        total = header_offset + HEADER

    fvek = os.urandom(key_len)
    vmk = os.urandom(32)
    volume_guid = uuid.uuid4().bytes_le

    entries = b""
    if password:
        salt = os.urandom(16)
        entries += vmk_entry(0x2000, salt, stretch(password_initial(password), salt), vmk)
    if recovery:
        salt = os.urandom(16)
        entries += vmk_entry(0x0800, salt, stretch(recovery_initial(recovery), salt), vmk)
    if bek_path:
        guid = uuid.uuid4().bytes_le
        key = os.urandom(32)
        entries += startup_key_vmk_entry(guid, key, vmk)
        name = str(uuid.UUID(bytes_le=guid)).upper() + ".BEK"
        with open(bek_path, "wb") as f:
            f.write(bek_file(guid, key, name))
    entries += entry(3, 5, seal(vmk, key_datum(algo, fvek)))
    entries += entry(0x0F, 0x0F, struct.pack("<QQ", header_offset, HEADER))
    entries += entry(7, 2, "OTG Master test volume".encode("utf-16-le") + b"\0\0")

    metadata_size = 48 + len(entries)
    block_len = 64 + metadata_size
    block_len += (-block_len) % 16
    head = (b"-FVE-FS-" + struct.pack("<HHHHQII", block_len // 16, 2, 4, 4, total, 0, HEADER // sector)
            + struct.pack("<QQQQ", *meta_offsets, header_offset))
    mdh = (struct.pack("<IIII", metadata_size, 1, 48, metadata_size) + volume_guid
           + struct.pack("<IHHQ", 8, algo, 0, filetime()))
    block = (head + mdh + entries).ljust(block_len, b"\0")
    assert len(head) == 64 and len(mdh) == 48

    validation_hash = struct.pack("<HHHHHH", 44, 0, 1, 0, 0x2005, 0) + hashlib.sha256(block).digest()
    nested = seal(vmk, validation_hash)
    validation = (struct.pack("<HHI", 88, 2, zlib.crc32(block) & 0xFFFFFFFF)
                  + struct.pack("<HHHH", 80, 0, 5, 1) + nested)
    assert len(validation) == 88
    area = (block + validation).ljust(META, b"\0")

    boot = bytearray(512)
    boot[0:3] = b"\xeb\x58\x90"
    if togo:
        # A FAT32 BPB for the discovery volume: 512-byte sectors, 8 per cluster,
        # 32 reserved, one FAT, the volume's sector count, root at cluster 2.
        boot[3:11] = b"MSWIN4.1"
        struct.pack_into("<HBHB", boot, 11, sector, 8, 32, 1)
        boot[21] = 0xF8
        struct.pack_into("<HHII", boot, 24, 0x3F, 0xFF, 0, total // sector)
        struct.pack_into("<IHHIHH", boot, 36, 1, 0, 0, 2, 1, 6)
        boot[64] = 0x80
        boot[66] = 0x29
        boot[71:82] = b"NO NAME    "
        boot[82:90] = b"FAT32   "
        boot[424:440] = GUID_NORMAL
        struct.pack_into("<QQQ", boot, 440, *meta_offsets)
    else:
        boot[3:11] = b"-FVE-FS-"
        struct.pack_into("<HBH", boot, 11, sector, 8, 0)
        boot[21] = 0xF8
        struct.pack_into("<HH", boot, 24, 0x3F, 0xFF)
        boot[160:176] = GUID_NORMAL
        struct.pack_into("<QQQ", boot, 176, *meta_offsets)
    boot[510:512] = b"\x55\xaa"

    with open(plain_path, "rb") as src, open(out_path, "wb") as out:
        out.truncate(total)
        first = src.read(HEADER)
        pos = HEADER
        while pos < plain_size:
            chunk = src.read(min(1 << 20, plain_size - pos))
            out.seek(pos)
            out.write(crypt(True, cipher, fvek, sector, pos // sector, chunk))
            pos += len(chunk)
        # After the data: with --metadata-at these land inside it, over what was there.
        out.seek(header_offset)
        out.write(crypt(True, cipher, fvek, sector, header_offset // sector, first))
        for off in meta_offsets:
            out.seek(off)
            out.write(area)
        out.seek(0)
        out.write(boot)
    return fvek


def verify(plain_path, out_path, password, recovery, fvek, inside=None, bek_path=None):
    here = os.path.dirname(os.path.abspath(__file__))
    dump = subprocess.run(["cryptsetup", "bitlkDump", out_path], capture_output=True, text=True)
    if dump.returncode != 0:
        raise SystemExit(f"cryptsetup rejects the volume:\n{dump.stderr}")
    if bek_path:
        p = subprocess.run(["cryptsetup", "bitlkDump", "--dump-volume-key", "--batch-mode",
                            "--key-file", bek_path, out_path], capture_output=True, text=True)
        got = "".join(p.stdout.split("MK dump:")[1].split()) if "MK dump:" in p.stdout else ""
        if got.lower()[: len(fvek) * 2] != fvek.hex():
            raise SystemExit(f"cryptsetup did not release the volume key for {bek_path}:\n{p.stdout}{p.stderr}")
    for secret in filter(None, [password, recovery]):
        p = subprocess.run(["cryptsetup", "bitlkDump", "--dump-volume-key", "--batch-mode", out_path],
                           input=secret, capture_output=True, text=True)
        got = "".join(p.stdout.split("MK dump:")[1].split()) if "MK dump:" in p.stdout else ""
        if got.lower()[: len(fvek) * 2] != fvek.hex():
            raise SystemExit(f"cryptsetup did not release the volume key for '{secret}':\n{p.stdout}{p.stderr}")
        plain_out = out_path + ".verify"
        try:
            subprocess.run([sys.executable, os.path.join(here, "bitlk_decrypt.py"), out_path, secret, plain_out],
                           check=True, capture_output=True)
            size = os.path.getsize(plain_path)
            with open(plain_path, "rb") as a, open(plain_out, "rb") as b:
                want = bytearray(a.read())
                if inside:   # BitLocker's own areas read as zeros
                    for o, n in [(o, META) for o in inside[:3]] + [(inside[3], HEADER)]:
                        want[o:o + n] = bytes(n)
                if hashlib.sha256(want).digest() != hashlib.sha256(b.read(size)).digest():
                    raise SystemExit("cryptsetup's key and layout do not decrypt to the plain image")
                if any(b.read()):
                    raise SystemExit("the metadata tail does not read as zeros")
        finally:
            if os.path.exists(plain_out):
                os.remove(plain_out)
    print(f"verified against cryptsetup: {', '.join(l.strip() for l in dump.stdout.splitlines() if 'Protection' in l)}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("plain")
    ap.add_argument("out")
    ap.add_argument("--password")
    ap.add_argument("--recovery")
    ap.add_argument("--cipher", choices=sorted(CIPHERS), default="xts128")
    ap.add_argument("--verify", action="store_true")
    ap.add_argument("--startup-key", metavar="FILE.BEK",
                    help="also add a startup-key protector and write its key file here")
    ap.add_argument("--metadata-at", help="M1,M2,M3,H: byte offsets inside the volume, as Windows places them")
    ap.add_argument("--togo", action="store_true", help="BitLocker To Go: a FAT32 discovery boot sector")
    a = ap.parse_args()
    if not a.password and not a.recovery and not a.startup_key:
        ap.error("give --password, --recovery, --startup-key or several")
    inside = [int(x, 0) for x in a.metadata_at.split(",")] if a.metadata_at else None
    if inside is not None and len(inside) != 4:
        ap.error("--metadata-at takes four offsets")
    fvek = build(a.plain, a.out, a.password, a.recovery, a.cipher, inside, a.startup_key, a.togo)
    print(f"{a.out}: BitLocker{' To Go' if a.togo else ''} {a.cipher}, {os.path.getsize(a.out)} bytes")
    if a.verify:
        verify(a.plain, a.out, a.password, a.recovery, fvek, inside, a.startup_key)


if __name__ == "__main__":
    main()
