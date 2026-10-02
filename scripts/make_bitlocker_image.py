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


def build(plain_path, out_path, password, recovery, cipher):
    algo, key_len = CIPHERS[cipher]
    sector = 512
    plain_size = os.path.getsize(plain_path)
    assert plain_size % 4096 == 0, "plain image must be a multiple of 4 KiB"
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
        out.seek(header_offset)
        out.write(crypt(True, cipher, fvek, sector, header_offset // sector, first))
        pos = HEADER
        while pos < plain_size:
            chunk = src.read(min(1 << 20, plain_size - pos))
            out.seek(pos)
            out.write(crypt(True, cipher, fvek, sector, pos // sector, chunk))
            pos += len(chunk)
        for off in meta_offsets:
            out.seek(off)
            out.write(area)
        out.seek(0)
        out.write(boot)
    return fvek


def verify(plain_path, out_path, password, recovery, fvek):
    here = os.path.dirname(os.path.abspath(__file__))
    dump = subprocess.run(["cryptsetup", "bitlkDump", out_path], capture_output=True, text=True)
    if dump.returncode != 0:
        raise SystemExit(f"cryptsetup rejects the volume:\n{dump.stderr}")
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
                if hashlib.sha256(a.read()).digest() != hashlib.sha256(b.read(size)).digest():
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
    a = ap.parse_args()
    if not a.password and not a.recovery:
        ap.error("give --password, --recovery or both")
    fvek = build(a.plain, a.out, a.password, a.recovery, a.cipher)
    print(f"{a.out}: BitLocker {a.cipher}, {os.path.getsize(a.out)} bytes")
    if a.verify:
        verify(a.plain, a.out, a.password, a.recovery, fvek)


if __name__ == "__main__":
    main()
