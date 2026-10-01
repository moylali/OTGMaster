#!/usr/bin/env python3
"""
Independent structural checker for an NTFS volume (image file or block device).

Linux has no NTFS checker comparable to chkdsk: `ntfsfix -n` checks little beyond
$MFT against $MFTMirr and the volume flags, and ntfsprogs' `ntfsck` is
quarantined upstream and validates record and attribute layout only. Neither
looks at the two things a write path most easily breaks, so this does, parsing
the on-disk structures itself rather than through libntfs-3g (the library under
test):

  * every FILE and INDX record's update sequence (a torn or misplaced write);
  * the $MFT bitmap against each record's in-use flag;
  * the volume $Bitmap against the union of every non-resident runlist —
    clusters in use but marked free (the next allocation overwrites live data),
    clusters marked used but owned by nothing (a leak), and clusters claimed by
    two attributes at once;
  * every $I30 index: each entry names a live record, with the right sequence
    number, that carries that FILE_NAME with this directory as parent; each
    record's FILE_NAME is indexed in its parent; entries in each node are in
    $UpCase collation order; every in-use INDX block is reached exactly once
    from the root and none is reached that the index bitmap marks free;
  * hard link counts, attribute size invariants, and $MFTMirr.

Exit status 0 when clean, 1 when any error is found, 2 if it cannot run.

    scripts/ntfs_check.py IMAGE_OR_DEVICE [--offset BYTES] [-v]
"""

import argparse
import struct
import sys
from collections import defaultdict

AT_STANDARD_INFORMATION = 0x10
AT_ATTRIBUTE_LIST = 0x20
AT_FILE_NAME = 0x30
AT_DATA = 0x80
AT_INDEX_ROOT = 0x90
AT_INDEX_ALLOCATION = 0xA0
AT_BITMAP = 0xB0
AT_END = 0xFFFFFFFF

FILE_NAME_POSIX, FILE_NAME_WIN32, FILE_NAME_DOS, FILE_NAME_WIN32_AND_DOS = 0, 1, 2, 3

MFT_RECORD_IN_USE = 0x1
MFT_RECORD_IS_DIRECTORY = 0x2

FILE_root = 5
FILE_Bitmap = 6
FILE_UpCase = 10
FILE_first_user = 16

INDEX_ENTRY_NODE = 1
INDEX_ENTRY_END = 2


class Volume:
    def __init__(self, path, offset):
        self.f = open(path, "rb")
        self.base = offset
        boot = self.read(0, 512)
        if boot[3:11] != b"NTFS    ":
            raise SystemExit(f"{path}: no NTFS boot sector at offset {offset}")
        self.bps = struct.unpack_from("<H", boot, 0x0B)[0]
        spc = boot[0x0D]
        self.spc = spc if spc <= 0x80 else 1 << (256 - spc)
        self.cluster = self.bps * self.spc
        self.total_sectors = struct.unpack_from("<q", boot, 0x28)[0]
        self.nr_clusters = self.total_sectors // self.spc
        self.mft_lcn = struct.unpack_from("<q", boot, 0x30)[0]
        self.mftmirr_lcn = struct.unpack_from("<q", boot, 0x38)[0]
        c = struct.unpack_from("<b", boot, 0x40)[0]
        self.rec_size = c * self.cluster if c > 0 else 1 << -c
        c = struct.unpack_from("<b", boot, 0x44)[0]
        self.idx_size = c * self.cluster if c > 0 else 1 << -c

    def read(self, off, n):
        self.f.seek(self.base + off)
        b = self.f.read(n)
        if len(b) != n:
            raise IOError(f"short read at {off}")
        return b

    def read_runs(self, runs, length):
        """Reads `length` bytes of an attribute's value from its runlist."""
        out = bytearray()
        for vcn, lcn, count in runs:
            for c in range(count):
                if len(out) >= length:
                    return bytes(out[:length])
                if lcn is None:
                    out += bytes(self.cluster)
                else:
                    out += self.read((lcn + c) * self.cluster, self.cluster)
        return bytes(out[:length]) if len(out) >= length else bytes(out) + bytes(length - len(out))


class Report:
    def __init__(self, verbose, echo=True):
        self.errors = []
        self.verbose = verbose
        self.echo = echo

    def err(self, msg):
        self.errors.append(msg)
        if self.echo:
            print(f"ERROR: {msg}")

    def info(self, msg):
        if self.verbose:
            print(msg)


def apply_fixups(buf, sector, what, rep):
    """Applies the update sequence array; reports a mismatch (torn write)."""
    buf = bytearray(buf)
    usa_ofs, usa_count = struct.unpack_from("<HH", buf, 4)
    if usa_ofs + usa_count * 2 > len(buf) or usa_count - 1 != len(buf) // sector:
        rep.err(f"{what}: bad update sequence array ({usa_ofs}, {usa_count})")
        return None
    usn = buf[usa_ofs:usa_ofs + 2]
    for i in range(1, usa_count):
        end = i * sector - 2
        if buf[end:end + 2] != usn:
            rep.err(f"{what}: update sequence mismatch in sector {i - 1} (torn write)")
            return None
        buf[end:end + 2] = buf[usa_ofs + 2 * i:usa_ofs + 2 * i + 2]
    return bytes(buf)


def decode_runs(data, ofs):
    """Mapping pairs -> list of (vcn, lcn or None for sparse, length)."""
    runs, vcn, lcn = [], 0, 0
    while ofs < len(data) and data[ofs]:
        h = data[ofs]
        ls, os_ = h & 0xF, h >> 4
        length = int.from_bytes(data[ofs + 1:ofs + 1 + ls], "little", signed=False)
        if os_:
            delta = int.from_bytes(data[ofs + 1 + ls:ofs + 1 + ls + os_], "little", signed=True)
            lcn += delta
            runs.append((vcn, lcn, length))
        else:
            runs.append((vcn, None, length))
        vcn += length
        ofs += 1 + ls + os_
    return runs


class Attr:
    def __init__(self, rec_no, buf, ofs):
        self.rec_no = rec_no
        self.type, self.length = struct.unpack_from("<II", buf, ofs)
        self.non_resident = buf[ofs + 8]
        name_len = buf[ofs + 9]
        name_ofs = struct.unpack_from("<H", buf, ofs + 10)[0]
        self.flags = struct.unpack_from("<H", buf, ofs + 12)[0]
        self.name = buf[ofs + name_ofs:ofs + name_ofs + 2 * name_len].decode("utf-16-le", "replace")
        if self.non_resident:
            (self.lowest_vcn, self.highest_vcn, runs_ofs, self.cu) = struct.unpack_from("<qqHB", buf, ofs + 16)
            self.alloc_size, self.data_size, self.init_size = struct.unpack_from("<qqq", buf, ofs + 40)
            self.runs = decode_runs(buf[ofs:ofs + self.length], runs_ofs)
            self.value = None
        else:
            vlen, vofs = struct.unpack_from("<IH", buf, ofs + 16)
            self.value = buf[ofs + vofs:ofs + vofs + vlen]
            self.runs = []


class Record:
    def __init__(self, no, buf):
        self.no = no
        self.seq, self.links, attrs_ofs, self.flags, self.bytes_used, self.bytes_alloc = \
            struct.unpack_from("<HHHHII", buf, 0x10)
        self.base = struct.unpack_from("<Q", buf, 0x20)[0] & 0xFFFFFFFFFFFF
        self.attrs = []
        ofs = attrs_ofs
        while ofs + 8 <= len(buf):
            t = struct.unpack_from("<I", buf, ofs)[0]
            if t == AT_END:
                break
            length = struct.unpack_from("<I", buf, ofs + 4)[0]
            if length < 16 or ofs + length > len(buf):
                raise ValueError(f"attribute at {ofs} overruns record")
            self.attrs.append(Attr(no, buf, ofs))
            ofs += length

    @property
    def in_use(self):
        return bool(self.flags & MFT_RECORD_IN_USE)


def parse_file_name(v):
    parent = struct.unpack_from("<Q", v, 0)[0]
    flags = struct.unpack_from("<I", v, 0x38)[0]
    name_len, ns = v[0x40], v[0x41]
    name = v[0x42:0x42 + 2 * name_len]
    return parent & 0xFFFFFFFFFFFF, parent >> 48, ns, name, flags


def upcase_key(name_utf16, upcase):
    units = struct.unpack(f"<{len(name_utf16) // 2}H", name_utf16)
    return tuple(upcase[u] if u < len(upcase) else u for u in units)


def collate(a, b, upcase):
    """$I30 collation: upcased comparison, then case-sensitive as a tiebreak."""
    ka, kb = upcase_key(a, upcase), upcase_key(b, upcase)
    if ka != kb:
        return -1 if ka < kb else 1
    ua = struct.unpack(f"<{len(a) // 2}H", a)
    ub = struct.unpack(f"<{len(b) // 2}H", b)
    return (ua > ub) - (ua < ub)


def check(path, offset, rep):
    vol = Volume(path, offset)
    rep.info(f"cluster {vol.cluster}, record {vol.rec_size}, index block {vol.idx_size}, "
             f"{vol.nr_clusters} clusters")

    # $MFT from record 0, then every record.
    raw0 = vol.read(vol.mft_lcn * vol.cluster, vol.rec_size)
    r0 = apply_fixups(raw0, vol.bps, "$MFT record 0", rep)
    if r0 is None:
        return rep
    mft0 = Record(0, r0)
    mft_data = [a for a in mft0.attrs if a.type == AT_DATA and not a.name]
    mft_bmp = [a for a in mft0.attrs if a.type == AT_BITMAP and not a.name]
    if not mft_data or not mft_bmp:
        rep.err("$MFT record has no $DATA or $BITMAP")
        return rep
    # $MFT's own extent records are not handled; a volume small enough for the
    # tests never has one, and one that does is reported rather than misread.
    if any(a.type == AT_ATTRIBUTE_LIST for a in mft0.attrs):
        rep.err("$MFT has an attribute list; this checker does not follow it")
        return rep
    mft_size = mft_data[0].data_size
    mft_raw = vol.read_runs(mft_data[0].runs, mft_size)
    a = mft_bmp[0]
    mft_bitmap = a.value if a.value is not None else vol.read_runs(a.runs, a.data_size)
    n_records = mft_size // vol.rec_size

    records = {}
    for no in range(n_records):
        raw = mft_raw[no * vol.rec_size:(no + 1) * vol.rec_size]
        bit = bool(mft_bitmap[no // 8] & (1 << (no % 8))) if no // 8 < len(mft_bitmap) else False
        if raw[:4] != b"FILE":
            if bit:
                rep.err(f"record {no}: marked in use in the $MFT bitmap but has no FILE signature")
            continue
        fixed = apply_fixups(raw, vol.bps, f"record {no}", rep)
        if fixed is None:
            continue
        try:
            rec = Record(no, fixed)
        except ValueError as e:
            rep.err(f"record {no}: {e}")
            continue
        if rec.in_use != bit:
            rep.err(f"record {no}: in-use flag {rec.in_use} but $MFT bitmap bit {bit}")
        if rec.in_use:
            records[no] = rec

    # $MFTMirr: the first records, byte for byte (fixups included).
    mirr_count = max(4, vol.cluster // vol.rec_size)
    mirr = vol.read(vol.mftmirr_lcn * vol.cluster, mirr_count * vol.rec_size)
    for i in range(mirr_count):
        if mirr[i * vol.rec_size:(i + 1) * vol.rec_size] != mft_raw[i * vol.rec_size:(i + 1) * vol.rec_size]:
            rep.err(f"$MFTMirr record {i} differs from $MFT")

    # Group extension records under their base record.
    attrs_of = defaultdict(list)
    for rec in records.values():
        owner = rec.base if rec.base else rec.no
        if rec.base and rec.base not in records:
            rep.err(f"record {rec.no}: extension of record {rec.base}, which is not in use")
            continue
        attrs_of[owner].extend(rec.attrs)

    # Cluster accounting.
    bmp_rec = attrs_of.get(FILE_Bitmap, [])
    bmp_attr = next((a for a in bmp_rec if a.type == AT_DATA and not a.name), None)
    if bmp_attr is None:
        rep.err("$Bitmap has no $DATA")
        return rep
    bitmap = bmp_attr.value if bmp_attr.value is not None else vol.read_runs(bmp_attr.runs, bmp_attr.data_size)
    owner = {}
    for no, attrs in attrs_of.items():
        for a in attrs:
            if not a.non_resident:
                continue
            for vcn, lcn, count in a.runs:
                if lcn is None:
                    continue
                if lcn < 0 or lcn + count > vol.nr_clusters:
                    # $BadClus:$Bad is sparse; anything real outside the volume is broken.
                    rep.err(f"record {no} attr 0x{a.type:x}{':' + a.name if a.name else ''}: "
                             f"run {lcn}+{count} outside the volume")
                    continue
                for c in range(lcn, lcn + count):
                    if c in owner:
                        rep.err(f"cluster {c} claimed by record {owner[c]} and record {no}")
                    else:
                        owner[c] = no
    leaked = unmarked = 0
    first_leak = first_unmarked = None
    for c in range(vol.nr_clusters):
        set_ = bool(bitmap[c // 8] & (1 << (c % 8)))
        used = c in owner
        if used and not set_:
            unmarked += 1
            first_unmarked = first_unmarked if first_unmarked is not None else c
        elif set_ and not used:
            leaked += 1
            first_leak = first_leak if first_leak is not None else c
    if unmarked:
        rep.err(f"{unmarked} clusters in use but free in $Bitmap (first {first_unmarked}, "
                f"owned by record {owner.get(first_unmarked)}) — the next allocation overwrites them")
    if leaked:
        rep.err(f"{leaked} clusters marked used in $Bitmap but owned by nothing (first {first_leak})")
    rep.info(f"{len(owner)} clusters in use, {len(records)} records in use")

    # Size invariants for unnamed $DATA.
    for no, attrs in attrs_of.items():
        for a in attrs:
            if a.type != AT_DATA or not a.non_resident or a.lowest_vcn != 0:
                continue
            if not (0 <= a.init_size <= a.data_size <= a.alloc_size):
                rep.err(f"record {no}: $DATA sizes init {a.init_size} / data {a.data_size} "
                        f"/ alloc {a.alloc_size} out of order")
            mapped = sum(c for _, _, c in a.runs) * vol.cluster
            extents = [x for x in attrs if x.type == AT_DATA and x.name == a.name and x.non_resident]
            if len(extents) == 1 and not (a.flags & 0x00FF) and mapped != a.alloc_size:
                rep.err(f"record {no}: $DATA allocated {a.alloc_size} but runlist maps {mapped}")

    # $UpCase for collation.
    up_attr = next((a for a in attrs_of.get(FILE_UpCase, []) if a.type == AT_DATA and not a.name), None)
    up_raw = vol.read_runs(up_attr.runs, up_attr.data_size) if up_attr and up_attr.non_resident else b""
    upcase = struct.unpack(f"<{len(up_raw) // 2}H", up_raw) if up_raw else tuple(range(65536))

    # Names each record carries, and the names each directory indexes.
    names = defaultdict(set)   # record -> {(parent, ns, name)}
    for no, attrs in attrs_of.items():
        for a in attrs:
            if a.type == AT_FILE_NAME:
                parent, pseq, ns, name, _ = parse_file_name(a.value)
                names[no].add((parent, ns, name))
                if parent not in records:
                    rep.err(f"record {no}: FILE_NAME parent {parent} is not in use")
        n_links = sum(1 for (_, ns, _) in names[no] if ns != FILE_NAME_DOS)
        if records[no].links != n_links and no >= FILE_first_user:
            rep.err(f"record {no}: link count {records[no].links}, but {n_links} non-DOS names")

    indexed = set()   # (child record, parent, ns, name)
    for no, attrs in attrs_of.items():
        root = next((a for a in attrs if a.type == AT_INDEX_ROOT and a.name == "$I30"), None)
        if root is None:
            continue
        if not (records[no].flags & MFT_RECORD_IS_DIRECTORY):
            rep.err(f"record {no}: has $I30 but is not flagged a directory")
        alloc = next((a for a in attrs if a.type == AT_INDEX_ALLOCATION and a.name == "$I30"), None)
        ibmp_a = next((a for a in attrs if a.type == AT_BITMAP and a.name == "$I30"), None)
        ibmp = b""
        if ibmp_a is not None:
            ibmp = ibmp_a.value if ibmp_a.value is not None else vol.read_runs(ibmp_a.runs, ibmp_a.data_size)
        alloc_raw = vol.read_runs(alloc.runs, alloc.data_size) if alloc else b""
        visited = set()
        block_vcns = vol.idx_size // vol.cluster if vol.idx_size >= vol.cluster else 1
        block_unit = vol.cluster if vol.idx_size >= vol.cluster else 512

        def walk(buf, ofs, end, where):
            prev = None
            while ofs + 16 <= end:
                mref, elen, klen, eflags = struct.unpack_from("<QHHH", buf, ofs)
                if elen < 16 or ofs + elen > end:
                    rep.err(f"dir {no} {where}: index entry at {ofs} overruns its node")
                    return
                if eflags & INDEX_ENTRY_NODE:
                    vcn = struct.unpack_from("<q", buf, ofs + elen - 8)[0]
                    child(vcn, where)
                if eflags & INDEX_ENTRY_END:
                    return
                v = buf[ofs + 16:ofs + 16 + klen]
                parent, pseq, ns, name, _ = parse_file_name(v)
                child_no, child_seq = mref & 0xFFFFFFFFFFFF, mref >> 48
                display = name.decode("utf-16-le", "replace")
                if parent != no:
                    rep.err(f"dir {no}: entry '{display}' says its parent is {parent}")
                rec = records.get(child_no)
                if rec is None:
                    rep.err(f"dir {no}: entry '{display}' points at record {child_no}, which is not in use")
                else:
                    if child_seq and child_seq != rec.seq:
                        rep.err(f"dir {no}: entry '{display}' has sequence {child_seq}, "
                                f"record {child_no} is at {rec.seq} (stale entry)")
                    if (no, ns, name) not in names[child_no]:
                        rep.err(f"dir {no}: entry '{display}' has no matching FILE_NAME in record {child_no}")
                indexed.add((child_no, no, ns, name))
                if prev is not None and collate(prev, name, upcase) >= 0:
                    rep.err(f"dir {no} {where}: '{display}' is out of collation order")
                prev = name
                ofs += elen

        def child(vcn, where):
            if alloc is None:
                rep.err(f"dir {no}: {where} points at index block {vcn} but there is no $INDEX_ALLOCATION")
                return
            bit_no = vcn // block_vcns
            if bit_no in visited:
                rep.err(f"dir {no}: index block {vcn} reached twice")
                return
            visited.add(bit_no)
            if not (bit_no // 8 < len(ibmp) and ibmp[bit_no // 8] & (1 << (bit_no % 8))):
                rep.err(f"dir {no}: index block {vcn} is referenced but free in the index bitmap")
            ofs = vcn * block_unit
            raw = alloc_raw[ofs:ofs + vol.idx_size]
            if raw[:4] != b"INDX":
                rep.err(f"dir {no}: index block {vcn} has no INDX signature")
                return
            fixed = apply_fixups(raw, vol.bps, f"dir {no} index block {vcn}", rep)
            if fixed is None:
                return
            if struct.unpack_from("<q", fixed, 0x10)[0] != vcn:
                rep.err(f"dir {no}: index block at vcn {vcn} records itself as another vcn")
            hdr = 0x18
            eofs, used = struct.unpack_from("<II", fixed, hdr)
            walk(fixed, hdr + eofs, hdr + used, f"block {vcn}")

        v = root.value
        hdr = 0x10
        eofs, used = struct.unpack_from("<II", v, hdr)
        walk(v, hdr + eofs, hdr + used, "root")
        for i in range(len(ibmp) * 8):
            if ibmp[i // 8] & (1 << (i % 8)) and i not in visited:
                if i * block_vcns * block_unit < len(alloc_raw):
                    rep.err(f"dir {no}: index block {i * block_vcns} is in use but unreachable")

    for no, ns_set in names.items():
        for (parent, ns, name) in ns_set:
            if no == FILE_root:
                continue
            if (no, parent, ns, name) not in indexed:
                rep.err(f"record {no}: name '{name.decode('utf-16-le', 'replace')}' is missing "
                        f"from the index of directory {parent}")
    return rep


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("path")
    p.add_argument("--offset", type=int, default=0, help="byte offset of the volume")
    p.add_argument("-v", "--verbose", action="store_true")
    args = p.parse_args()
    try:
        rep = check(args.path, args.offset, Report(args.verbose))
    except (OSError, struct.error) as e:
        print(f"cannot check {args.path}: {e}", file=sys.stderr)
        return 2
    if rep.errors:
        print(f"ntfs_check: {len(rep.errors)} error(s)")
        return 1
    print("ntfs_check: clean")
    return 0


if __name__ == "__main__":
    sys.exit(main())
