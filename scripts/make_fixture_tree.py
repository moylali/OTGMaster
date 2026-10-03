#!/usr/bin/env python3
"""
Builds the device-matrix read set: a BENCH/ tree of about 3 GB on a mounted volume,
and a manifest that hashes every file in it (docs/TEST_DATA.md §16).

Runs unchanged on Linux and on macOS (stdlib only, Python 3.8+), so every partition
of both matrix drives — including the APFS ones, which only a Mac can fill — carries
the same kind of tree:

    BENCH/large/seq_2100m.bin    random, 2100 MiB: reads cross the 2^31-byte offset
    BENCH/large/seq_256m.bin     random, 256 MiB: the random-read target
    BENCH/dense_short/           10,000 one-byte files, 8.3 names
    BENCH/dense_lfn/             10,000 one-byte files, long names
    BENCH/nested/level_01/…/level_10/leaf_at_depth_10.dat
    BENCH/mixed/                 files of 1 KiB-4 MiB in a tree up to 4 deep, filling
                                 the rest of --size
    BENCH/edge/                  empty file, sizes at cluster boundaries, a 64 MiB
                                 all-zero file, spaces, Unicode, a 200-byte name
    BENCH/reports/               empty: the on-device runner writes reports here
    BENCH/MANIFEST.txt           every file: path<TAB>bytes<TAB>sha256 (relative to
                                 BENCH/), plus a listing hash for each dense
                                 directory, in the format benchFixtures reads

File contents are random (os.urandom), so the manifest, not a seed, is the ground
truth; the layout — names, sizes, tree shape — is derived from --seed, so two
partitions with the same seed have the same shape. Names avoid every character FAT32,
exFAT or NTFS reject, and no two differ only in case, so the tree is valid on all of
them; --case-pair adds case.txt and CASE.txt for a case-sensitive volume.

    python3 scripts/make_fixture_tree.py /Volumes/D1APFSCI --seed D1APFSCI --size 3.5e9
    python3 scripts/make_fixture_tree.py /mnt/d2p1 --seed D2VCFAT32            # 3.0e9

Refuses if BENCH/ already exists. A copy of the manifest is also written to
--manifest-out if given, so the host keeps one the drive cannot corrupt.
"""
import argparse
import hashlib
import os
import random
import sys
import time

MiB = 1 << 20
CHUNK = 4 * MiB


def write_random(path, size):
    """Writes `size` random bytes to `path`; returns the SHA-256."""
    h = hashlib.sha256()
    with open(path, "wb") as f:
        left = size
        while left > 0:
            n = min(CHUNK, left)
            b = os.urandom(n)
            f.write(b)
            h.update(b)
            left -= n
    return h.hexdigest()


def write_bytes(path, data):
    with open(path, "wb") as f:
        f.write(data)
    return hashlib.sha256(data).hexdigest()


def write_zeros(path, size):
    h = hashlib.sha256()
    z = bytes(CHUNK)
    with open(path, "wb") as f:
        left = size
        while left > 0:
            n = min(CHUNK, left)
            # Written, not truncated: a hole would skip the allocation path entirely.
            f.write(z[:n])
            h.update(z[:n])
            left -= n
    return h.hexdigest()


def listing_hash(dirpath):
    """The listing hash benchFixtures computes: sorted name<TAB>size lines, joined by \\n."""
    lines = []
    for name in os.listdir(dirpath):
        p = os.path.join(dirpath, name)
        lines.append(f"{name}\tdir" if os.path.isdir(p) else f"{name}\t{os.path.getsize(p)}")
    lines.sort()
    return hashlib.sha256("\n".join(lines).encode("utf-8")).hexdigest(), len(lines)


class Tree:
    def __init__(self, bench):
        self.bench = bench
        self.entries = []    # (relative path, bytes, sha256)
        self.total = 0

    def add(self, rel, size, sha):
        self.entries.append((rel, size, sha))
        self.total += size

    def path(self, rel):
        p = os.path.join(self.bench, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        return p

    def random_file(self, rel, size):
        self.add(rel, size, write_random(self.path(rel), size))

    def data_file(self, rel, data):
        self.add(rel, len(data), write_bytes(self.path(rel), data))


def progress(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def build(mount, seed, target, case_pair, large_mib=2100, dense=10000):
    bench = os.path.join(mount, "BENCH")
    if os.path.exists(bench):
        raise SystemExit(f"{bench} already exists — refusing to add to an existing tree")
    rng = random.Random(seed)
    t = Tree(bench)
    os.makedirs(os.path.join(bench, "reports"))

    small_mib = min(256, large_mib // 2)
    progress(f"large/seq_{large_mib}m.bin ({large_mib} MiB)")
    t.random_file(f"large/seq_{large_mib}m.bin", large_mib * MiB)
    progress(f"large/seq_{small_mib}m.bin")
    t.random_file(f"large/seq_{small_mib}m.bin", small_mib * MiB)

    progress(f"dense_short/ ({dense:,} files)")
    for i in range(1, dense + 1):
        t.data_file(f"dense_short/f{i:05d}.dat", b"x")
    progress(f"dense_lfn/ ({dense:,} files)")
    for i in range(1, dense + 1):
        t.data_file(f"dense_lfn/a_file_with_a_deliberately_long_name_for_lfn_testing_{i:05d}.dat", b"x")

    leaf = "nested/" + "/".join(f"level_{i:02d}" for i in range(1, 11)) + "/leaf_at_depth_10.dat"
    t.random_file(leaf, 4096)

    progress("edge/")
    for size in (0, 1, 511, 512, 513, 4095, 4096, 4097, 65535, 65536, 65537, MiB - 1, MiB, MiB + 1):
        t.random_file(f"edge/size_{size}.bin", size)
    zeros_mib = min(64, large_mib)
    t.add(f"edge/zeros_{zeros_mib}m.bin", zeros_mib * MiB, write_zeros(t.path(f"edge/zeros_{zeros_mib}m.bin"), zeros_mib * MiB))
    t.data_file("edge/file with spaces.txt", b"Hello World\n")
    t.data_file("edge/unicöde_fîle_日本語.txt", "Unicode\n".encode())
    t.data_file("edge/emoji_😀_name.txt", b"emoji\n")
    t.data_file("edge/" + "n" * 196 + ".txt", b"long name\n")
    t.data_file("edge/multi.dot.name.tar.gz", b"dots\n")
    t.data_file("edge/(parens) [brackets] +plus, comma; semi=eq.txt", b"punctuation\n")
    t.random_file("edge/deep tree with spaces/sub dir/ünïcode dir/file.bin", 12345)
    if case_pair:
        t.data_file("edge/case.txt", b"lower\n")
        t.data_file("edge/CASE.txt", b"UPPER\n")

    # mixed/: log-uniform sizes, 1 KiB-4 MiB, in a tree up to four deep, until the
    # read set reaches the target. The leftover is a final file of whatever size
    # remains, so the total lands on the target within a few KiB.
    progress(f"mixed/ (to {target / 1e9:.2f} GB in total)")
    dirs = ["mixed"]
    for _ in range(40):
        parent = rng.choice(dirs)
        if parent.count("/") < 4:
            dirs.append(f"{parent}/d{len(dirs):02d}")
    n = 0
    while t.total < target:
        size = int(2 ** rng.uniform(10, 22))
        size = min(size, int(target - t.total)) or 1
        t.random_file(f"{rng.choice(dirs)}/m{n:04d}_{size}.bin", size)
        n += 1
    progress(f"mixed/: {n} files")

    os.sync()
    lines = [
        "# OTG Master device-matrix fixture (scripts/make_fixture_tree.py)",
        f"# generated: {time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())}",
        f"# seed: {seed}",
        f"# files: {len(t.entries)}, bytes: {t.total}",
        "",
        "# path<TAB>bytes<TAB>sha256, relative to BENCH/",
    ]
    for d in ("dense_short", "dense_lfn"):
        h, count = listing_hash(os.path.join(bench, d))
        lines.append(f"{d}/\t{count} files\t{h}")
    for rel, size, sha in sorted(t.entries):
        lines.append(f"{rel}\t{size}\t{sha}")
    manifest = "\n".join(lines) + "\n"
    with open(os.path.join(bench, "MANIFEST.txt"), "w", encoding="utf-8") as f:
        f.write(manifest)
    os.sync()
    return t, manifest


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("mount", help="mounted volume to fill")
    ap.add_argument("--seed", required=True, help="layout seed; use the volume label")
    ap.add_argument("--size", type=float, default=3.0e9, help="read-set bytes (default 3.0e9)")
    ap.add_argument("--case-pair", action="store_true", help="add case.txt and CASE.txt")
    ap.add_argument("--manifest-out", help="also write the manifest here")
    # Test scale only (build_matrix_drive.py --test-scale): the real tree uses the defaults.
    ap.add_argument("--large-mib", type=int, default=2100, help=argparse.SUPPRESS)
    ap.add_argument("--dense", type=int, default=10000, help=argparse.SUPPRESS)
    a = ap.parse_args()
    if not os.path.isdir(a.mount):
        raise SystemExit(f"{a.mount} is not a directory")
    t0 = time.time()
    t, manifest = build(a.mount, a.seed, a.size, a.case_pair, a.large_mib, a.dense)
    if a.manifest_out:
        with open(a.manifest_out, "w", encoding="utf-8") as f:
            f.write(manifest)
    progress(f"done: {len(t.entries)} files, {t.total / 1e9:.3f} GB, {time.time() - t0:.0f} s")


if __name__ == "__main__":
    sys.exit(main())
