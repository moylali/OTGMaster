#!/usr/bin/env python3
"""
Verifies every partition of a device-matrix drive in one go, without root and
without the app's code (docs/TEST_DATA.md §16).

    # before writing: the images just built
    python3 scripts/verify_matrix_drive.py --build ~/matrix/d2

    # after a run: the partitions read off the drive by read_matrix_drive.sh
    python3 scripts/verify_matrix_drive.py --build ~/matrix/d2 --images ~/matrix/d2-read

For each partition, from its raw bytes:

  1. decrypt with code independent of the app — cryptsetup's key and layout with
     Python's AES for LUKS (luks_payload.py) and BitLocker (bitlk_decrypt.py), the
     VeraCrypt header opened in Python; APFS through apfs-fuse;
  2. compare every MiB of the decrypted volume with the baseline (<LABEL>.chunks):
     a read-only partition (APFS, ext2, ext3) must not have changed at all, and on
     a writable one the changed amount is shown beside what the run left behind;
  3. run the filesystem's own checker — e2fsck -fn, fsck.vfat -n, fsck.exfat -n,
     ntfs_check.py + ntfsfix -n, apfsck (plain APFS only; it cannot open an
     encrypted volume);
  4. mount it read-only with the kernel's driver (apfs-fuse for APFS) and check
     every file in the manifest, byte for byte, and that nothing else exists
     outside BENCH/reports/ and the runner's BENCH_* directories.

Prints one row per partition and an overall verdict; --report also writes it as
Markdown. Exit status 0 only when every partition is CLEAN.

--accept makes the current state of each CLEAN partition its new baseline (the old
one is kept as .chunks.prev); a partition with no baseline yet — the Mac-built APFS
ones, on their first check — gets one only this way. Accept only a state you have
reason to trust: a baseline taken over damage records the damage as normal.

APFS needs apfs-fuse and apfsck (docs/TEST_DATA.md §6b): set APFS_FUSE and APFSCK.
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes  # noqa: E402

from verify_e2e_volume import check_structure, fs_type  # noqa: E402

MiB = 1 << 20
FS_BLKID = {"fat32": "vfat", "exfat": "exfat", "ntfs": "ntfs", "ext2": "ext2", "ext3": "ext3", "ext4": "ext4"}
READ_ONLY = ("apfs", "ext2", "ext3")
# Entries a filesystem or the OS that filled it keeps outside BENCH/, which no run made.
SYSTEM = {
    "ext2": {"lost+found"}, "ext3": {"lost+found"}, "ext4": {"lost+found"},
    # .metadata_never_index is prepare_matrix_drive_macos.sh's own; the rest are macOS's.
    "apfs": {".fseventsd", ".Spotlight-V100", ".Trashes", ".TemporaryItems", ".DS_Store", ".VolumeIcon.icns",
             "private-dir", ".metadata_never_index"},
}


def run(*cmd, input=None):
    return subprocess.run(cmd, input=input, capture_output=True, text=True)


def chunk_hashes(path, nbytes=None):
    """SHA-256 of every MiB of the first `nbytes` (default: all) of `path`.

    Bounded by the baseline's length because decryptors may return more than the
    volume: bitlk_decrypt.py includes the metadata tail past the filesystem."""
    nbytes = os.path.getsize(path) if nbytes is None else nbytes
    out = []
    with open(path, "rb") as f:
        while nbytes > 0 and (chunk := f.read(min(MiB, nbytes))):
            out.append(hashlib.sha256(chunk).hexdigest())
            nbytes -= len(chunk)
    return out


def read_baseline(path):
    """(byte length, chunk hashes) from a .chunks file, or None."""
    if not os.path.exists(path):
        return None
    lines = open(path).read().split("\n")
    return int(lines[0].split()[2]), [h for h in lines[1:] if h]


def vc_open(raw, password, pim):
    """(master key, data start, data size, which header). Tries the backup header in
    the volume's last 128 KiB when the primary does not open, as VeraCrypt's own
    "use backup header" does: a damaged primary must be reported as damage, and
    whether the volume is still recoverable is part of that report."""
    from fill_veracrypt_volume import open_header
    try:
        return (*open_header(raw, password, pim), "primary")
    except SystemExit:
        pass
    backup = os.path.join(os.path.dirname(raw), os.path.basename(raw) + ".vcbackup")
    try:
        with open(raw, "rb") as src, open(backup, "wb") as dst:
            src.seek(os.path.getsize(raw) - 128 * 1024)
            dst.write(src.read(512))
        try:
            master, start, size = open_header(backup, password, pim)
        except SystemExit:
            raise RuntimeError("VeraCrypt primary and backup headers both fail to open "
                               "(wrong password/PIM, or both overwritten)")
        return master, start, size, "backup"
    finally:
        if os.path.exists(backup):
            os.remove(backup)


def decrypt_vc_with(raw, master, start, size, out):
    with open(raw, "rb") as src, open(out, "wb") as dst:
        src.seek(start)
        pos = 0
        while pos < size:
            chunk = src.read(min(MiB, size - pos))
            buf = bytearray()
            for i in range(0, len(chunk), 512):
                unit = (start + pos + i) // 512
                d = Cipher(algorithms.AES(master), modes.XTS(unit.to_bytes(16, "little"))).decryptor()
                buf += d.update(chunk[i:i + 512]) + d.finalize()
            dst.write(buf)
            pos += len(chunk)


def decrypt(p, raw, work, meta):
    """The decrypted volume's path; a copy for plain partitions, since checkers may trim it."""
    out = os.path.join(work, "plain.img")
    c = p["container"]
    if c == "plain":
        r = run("cp", "--sparse=always", raw, out)
    elif c in ("luks1", "luks2", "luks2_4k"):
        r = run(sys.executable, os.path.join(HERE, "luks_payload.py"), "decrypt", raw, meta["password"], out)
    elif c == "bitlocker":
        r = run(sys.executable, os.path.join(HERE, "bitlk_decrypt.py"), raw, meta["password"], out)
    elif c == "vc":
        master, start, size, which = vc_open(raw, meta["password"], meta["pim"])
        decrypt_vc_with(raw, master, start, size, out)
        if which != "primary":
            p["_header_note"] = "VeraCrypt primary header DAMAGED; decrypted with the backup header"
        return out
    else:
        raise RuntimeError(f"no decryptor for {c}")
    if r.returncode != 0:
        raise RuntimeError(f"independent decryption failed:\n{(r.stdout + r.stderr).strip()[-600:]}")
    return out


def listing_hash(d):
    lines = []
    for name in os.listdir(d):
        q = os.path.join(d, name)
        lines.append(f"{name}\tdir" if os.path.isdir(q) else f"{name}\t{os.path.getsize(q)}")
    lines.sort()
    return hashlib.sha256("\n".join(lines).encode("utf-8")).hexdigest()


def file_sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(4 * MiB):
            h.update(chunk)
    return h.hexdigest()


def keystream_matches(path, label, rel):
    """Whether `path` holds the benchmark bigwrite's content for `rel`: the AES-128-CTR
    keystream (zero IV) under SHA-256("otg-matrix:<label>/<rel>")[:16] (Benchmark.Keystream)."""
    key = hashlib.sha256(f"otg-matrix:{label}/{rel}".encode()).digest()[:16]
    enc = Cipher(algorithms.AES(key), modes.CTR(bytes(16))).encryptor()
    with open(path, "rb") as f:
        while chunk := f.read(4 * MiB):
            if enc.update(bytes(len(chunk))) != chunk:
                return False
    return True


def check_bigwrite(root, label):
    """The benchmark's bigwrite output, checked against what was meant, not read back:
    BENCH_BIG/big.bin against its keystream, and every BENCH_TREE file against the
    EXPECTED.txt the run wrote from its intended content. Returns (summary, problems)."""
    problems, parts = [], []
    big = os.path.join(root, "BENCH_BIG", "big.bin")
    if os.path.exists(big):
        ok = keystream_matches(big, label, "BENCH_BIG/big.bin")
        parts.append(f"big.bin {os.path.getsize(big)} B {'OK' if ok else 'DIFFERS'}")
        if not ok:
            problems.append("BENCH_BIG/big.bin differs from the bytes the run wrote")
    tree = os.path.join(root, "BENCH_TREE")
    exp_path = os.path.join(tree, "EXPECTED.txt")
    if os.path.isdir(tree):
        if not os.path.exists(exp_path):
            problems.append("BENCH_TREE has no EXPECTED.txt (the run did not finish its tree)")
        else:
            expected = {}
            for line in open(exp_path, encoding="utf-8"):
                if line.startswith("#") or not line.strip():
                    continue
                rel, size, sha = line.rstrip("\n").split("\t")
                expected[rel] = (int(size), sha)
            found = set()
            for dirpath, _, files in os.walk(tree):
                for n in files:
                    rel = os.path.relpath(os.path.join(dirpath, n), tree)
                    if rel != "EXPECTED.txt":
                        found.add(rel)
            bad = 0
            for rel, (size, sha) in expected.items():
                f = os.path.join(tree, rel)
                if not os.path.exists(f):
                    problems.append(f"BENCH_TREE/{rel} missing"); bad += 1
                elif os.path.getsize(f) != size or file_sha(f) != sha:
                    problems.append(f"BENCH_TREE/{rel} differs"); bad += 1
            for rel in sorted(found - set(expected)):
                problems.append(f"BENCH_TREE/{rel} exists but the run deleted or never made it"); bad += 1
            parts.append(f"tree {len(expected) - min(bad, len(expected))}/{len(expected)} OK")
    return ", ".join(parts), problems


class FileCheck:
    def __init__(self):
        self.checked = 0
        self.bad: list = []
        self.reports = 0
        self.run_files = 0
        self.run_bytes = 0
        self.unexpected: list = []


def check_files(root, manifest_text, fs):
    """Every manifest entry byte for byte, and nothing unexplained besides."""
    bench = os.path.join(root, "BENCH")
    res = FileCheck()
    known = {"BENCH/MANIFEST.txt"}
    for line in manifest_text.splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        path, size, sha = line.split("\t")
        if path.endswith("/"):
            d = os.path.join(bench, path.rstrip("/"))
            if not os.path.isdir(d):
                res.bad.append(f"{path} missing")
            elif listing_hash(d) != sha:
                res.bad.append(f"{path} listing differs")
            res.checked += 1
            continue
        known.add("BENCH/" + path)
        f = os.path.join(bench, path)
        res.checked += 1
        try:
            if os.path.getsize(f) != int(size):
                res.bad.append(f"{path}: {os.path.getsize(f)} bytes, manifest says {size}")
            elif file_sha(f) != sha:
                res.bad.append(f"{path}: content differs")
        except OSError as e:
            res.bad.append(f"{path}: {e.strerror or e}")
    system = SYSTEM.get(fs, set())
    for dirpath, dirnames, filenames in os.walk(root):
        rel_dir = os.path.relpath(dirpath, root)
        if rel_dir == ".":
            dirnames[:] = [d for d in dirnames if d not in system]
            filenames = [f for f in filenames if f not in system]
        for name in filenames:
            rel = os.path.normpath(os.path.join(rel_dir, name))
            if rel in known:
                continue
            top = rel.split(os.sep)[0]
            if rel.startswith("BENCH/reports/"):
                res.reports += 1
            elif top.startswith("BENCH_"):
                res.run_files += 1
                res.run_bytes += os.path.getsize(os.path.join(dirpath, name))
            else:
                res.unexpected.append(rel)
    return res


class Mount:
    """A read-only mount of `img` as this user: udisksctl for the kernel's drivers, apfs-fuse for APFS."""

    def __init__(self, img, fs, work, password=None):
        self.img, self.fs, self.work, self.password = img, fs, work, password
        self.dev = self.mnt = None

    def __enter__(self):
        if self.fs == "apfs":
            self.mnt = os.path.join(self.work, "mnt")
            os.makedirs(self.mnt)
            r = run(os.environ["APFS_FUSE"], "-r", self.password, self.img, self.mnt)
            if r.returncode != 0:
                raise RuntimeError(f"apfs-fuse could not mount: {(r.stdout + r.stderr).strip()[:300]}")
            return os.path.join(self.mnt, "root")
        # Retried: udisks refuses now and then when several partitions ask at once.
        for attempt in range(5):
            r = run("udisksctl", "loop-setup", "-r", "-f", self.img, "--no-user-interaction")
            if r.returncode == 0:
                break
            time.sleep(2 + attempt * 2)
        m = re.search(r"/dev/loop\d+", r.stdout)
        if not m:
            raise RuntimeError(f"udisksctl loop-setup failed: {r.stdout}{r.stderr}")
        self.dev = m.group(0)
        r = run("udisksctl", "mount", "-b", self.dev, "-o", "ro", "--no-user-interaction")
        # A desktop session may auto-mount the loop device first (read-only too: the
        # loop device is). Use that mount rather than failing.
        m = re.search(r" at (.+?)\.?$", r.stdout.strip()) or re.search(r"already mounted at `(.+?)'", r.stderr)
        if not m:
            raise RuntimeError(f"udisksctl mount failed: {r.stdout}{r.stderr}")
        return m.group(1)

    def __exit__(self, *exc):
        if self.fs == "apfs":
            run("fusermount", "-u", self.mnt)
        elif self.dev:
            run("udisksctl", "unmount", "-b", self.dev, "--no-user-interaction")
            run("udisksctl", "loop-delete", "-b", self.dev, "--no-user-interaction")


def verify_part(p, build, images, meta, accept, work_root):
    label, fs = p["label"], p["fs"]
    t0 = time.time()
    row = dict(label=label, kind=f"{p['container']}/{fs}", verdict="CLEAN", notes=[])

    def fail(msg):
        row["verdict"] = "FAILED"
        row["notes"].append(msg)

    raw = os.path.join(images, f"{label}.img")
    if not os.path.exists(raw):
        row.update(verdict="NOT CHECKED", notes=["no image (APFS is built on the Mac; read it off the drive)"
                                                  if p["container"] == "apfs" else "no image"])
        return row
    work = tempfile.mkdtemp(prefix=f".verify-{label}-", dir=work_root)
    try:
        # 1. decrypt
        if fs == "apfs":
            vol = raw
            password = meta["password"] if p["apfs"][1] else "unused"
        else:
            vol = decrypt(p, raw, work, meta)
            if p.get("_header_note"):
                fail(p["_header_note"])
            got = fs_type(vol)
            if got != FS_BLKID[fs]:
                fail(f"decrypted volume holds '{got or 'nothing recognisable'}', expected {FS_BLKID[fs]}")
                return row
            password = None

        # 2. baseline, before any checker can trim the image
        base_path = os.path.join(build, f"{label}.chunks")
        baseline = read_baseline(base_path)
        if baseline is None:
            row["changed"] = "no baseline"
            if not accept:
                row["notes"].append("no baseline yet: --accept takes one if the partition is clean")
        else:
            nbytes, base = baseline
            if os.path.getsize(vol) < nbytes:
                fail(f"volume is {os.path.getsize(vol)} bytes, the baseline {nbytes}")
            now = chunk_hashes(vol, nbytes)
            changed = sum(1 for a, b in zip(base, now) if a != b) + abs(len(base) - len(now))
            row["changed"] = f"{changed} MiB"
            if changed and fs in READ_ONLY:
                fail(f"{changed} MiB changed on a partition the app must never write")

        # 3. structure
        if fs == "apfs":
            if not p["apfs"][1]:
                r = run(os.environ.get("APFSCK", "apfsck"), vol)
                row["checker"] = "clean" if r.returncode == 0 else "FAILED"
                if r.returncode != 0:
                    fail(f"apfsck: {(r.stdout + r.stderr).strip()[:300]}")
            else:
                row["checker"] = "n/a (encrypted)"
        else:
            problems = check_structure(FS_BLKID[fs], vol)
            row["checker"] = "clean" if not problems else "FAILED"
            for pr in problems:
                fail(pr)

        # 4. files
        man_path = os.path.join(build, f"{label}.manifest")
        with Mount(vol, fs, work, password) as root:
            if os.path.exists(man_path):
                manifest = open(man_path, encoding="utf-8").read()
            else:
                # Mac-built: the drive's own copy, kept on the host once accepted.
                on_drive = os.path.join(root, "BENCH", "MANIFEST.txt")
                if not os.path.exists(on_drive):
                    raise RuntimeError("no BENCH/MANIFEST.txt on the volume: was it filled by "
                                       "prepare_matrix_drive_macos.sh?")
                manifest = open(on_drive, encoding="utf-8").read()
                row["notes"].append("manifest read from the drive (Mac-built)")
            res = check_files(root, manifest, fs)
            big_summary, big_problems = check_bigwrite(root, label)
        row["files"] = f"{res.checked - len(res.bad)}/{res.checked}"
        row["run_output"] = f"{res.run_files} files, {res.run_bytes / MiB:.0f} MiB; {res.reports} reports"
        if big_summary:
            row["run_output"] += f"; bigwrite: {big_summary}"
        for b in big_problems[:10]:
            fail(b)
        for b in res.bad[:10]:
            fail(b)
        if len(res.bad) > 10:
            fail(f"... and {len(res.bad) - 10} more")
        for u in res.unexpected[:10]:
            fail(f"unexplained file: {u}")
        if len(res.unexpected) > 10:
            fail(f"... and {len(res.unexpected) - 10} more unexplained files")

        if accept and row["verdict"] == "CLEAN":
            if baseline is not None:
                shutil.copy(base_path, base_path + ".prev")
                # Keep the baseline's length: the decrypted view may run past the volume.
                vol_len = baseline[0]
            else:
                vol_len = os.path.getsize(vol)
            with open(base_path, "w") as f:
                f.write(f"# bytes {vol_len}\n" + "\n".join(chunk_hashes(vol, vol_len)) + "\n")
            if not os.path.exists(man_path):
                with open(man_path, "w", encoding="utf-8") as f:
                    f.write(manifest)
            row["notes"].append("accepted as the new baseline")
        return row
    except (Exception, SystemExit) as e:  # noqa: BLE001 — one partition's failure must not hide the others
        fail(f"{type(e).__name__}: {e}")
        return row
    finally:
        row["seconds"] = round(time.time() - t0)
        shutil.rmtree(work, ignore_errors=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--build", required=True, help="the build directory (drive.json, manifests, baselines)")
    ap.add_argument("--images", help="raw partition images read off the drive (default: the built ones)")
    ap.add_argument("--only", action="append")
    ap.add_argument("--accept", action="store_true", help="make each CLEAN partition's state its baseline")
    ap.add_argument("--report", help="also write the result as Markdown here")
    ap.add_argument("-j", "--jobs", type=int, default=4)
    a = ap.parse_args()

    meta = json.load(open(os.path.join(a.build, "drive.json")))
    images = a.images or a.build
    parts = [p for p in meta["partitions"] if not a.only or p["label"] in a.only]
    if any(p["container"] == "apfs" for p in parts) and os.path.exists(os.path.join(images, "D1APFSCI.img")):
        for tool in ("APFS_FUSE", "APFSCK"):
            if not os.environ.get(tool):
                raise SystemExit(f"set {tool} to the built tool (docs/TEST_DATA.md §6b)")

    # Beside the images, not in /tmp: each partition decrypts to a 6 GB working
    # copy, and /tmp is often a RAM-backed tmpfs.
    work_root = tempfile.mkdtemp(prefix=".verify-", dir=images)
    try:
        with concurrent.futures.ProcessPoolExecutor(max_workers=a.jobs) as ex:
            rows = list(ex.map(verify_part, parts, [a.build] * len(parts), [images] * len(parts),
                               [meta] * len(parts), [a.accept] * len(parts), [work_root] * len(parts)))
    finally:
        shutil.rmtree(work_root, ignore_errors=True)

    head = ["Partition", "Container/FS", "Verdict", "Checker", "Files", "Changed vs baseline", "Left by runs", "s"]
    table = [head] + [[r["label"], r["kind"], r["verdict"], r.get("checker", "-"), r.get("files", "-"),
                       r.get("changed", "-"), r.get("run_output", "-"), str(r.get("seconds", ""))] for r in rows]
    widths = [max(len(row[i]) for row in table) for i in range(len(head))]
    for row in table:
        print("  ".join(c.ljust(w) for c, w in zip(row, widths)))
    for r in rows:
        for n in r["notes"]:
            print(f"  {r['label']}: {n}")
    clean = sum(r["verdict"] == "CLEAN" for r in rows)
    overall = "ALL CLEAN" if clean == len(rows) else f"{len(rows) - clean} of {len(rows)} NOT CLEAN"
    print(f"\nDrive {meta['drive']}: {overall}")

    if a.report:
        with open(a.report, "w", encoding="utf-8") as f:
            f.write(f"# Drive {meta['drive']} verification\n\n")
            f.write(f"- Images: `{images}`\n- Build: `{a.build}` ({meta.get('commit', '?')})\n")
            f.write(f"- Date: {time.strftime('%Y-%m-%d %H:%M:%S')}\n- Overall: **{overall}**\n\n")
            f.write("| " + " | ".join(head) + " |\n|" + "---|" * len(head) + "\n")
            for row in table[1:]:
                f.write("| " + " | ".join(row) + " |\n")
            notes = [f"- `{r['label']}`: {n}" for r in rows for n in r["notes"]]
            if notes:
                f.write("\n## Notes\n\n" + "\n".join(notes) + "\n")
    sys.exit(0 if clean == len(rows) else 1)


if __name__ == "__main__":
    main()
