#!/usr/bin/env python3
"""
Rebuilds a partition image from a VolumeDumpReceiver dump (.otgdump).

The dump leaves out all-zero megabytes; they come back as holes in a sparse file,
so the image costs only the space of the data in it. Run the filesystem's own
checkers on the result:

    python3 scripts/undump_volume.py NTFSPLAIN.otgdump ntfs.img
    python3 scripts/ntfs_check.py ntfs.img && ntfsfix -n ntfs.img
"""
import struct
import sys


def main(src, dst):
    with open(src, "rb") as f, open(dst, "wb") as out:
        if f.read(8) != b"OTGDUMP1":
            raise SystemExit(f"{src}: not an OTG dump")
        (total,) = struct.unpack("<Q", f.read(8))
        out.truncate(total)
        records = data = 0
        while True:
            hdr = f.read(12)
            if not hdr:
                break
            if len(hdr) != 12:
                raise SystemExit(f"{src}: truncated record header")
            offset, length = struct.unpack("<QI", hdr)
            chunk = f.read(length)
            if len(chunk) != length or offset + length > total:
                raise SystemExit(f"{src}: truncated or out-of-range record at {offset}")
            out.seek(offset)
            out.write(chunk)
            records += 1
            data += length
    print(f"{dst}: {total} bytes, {data} bytes of data in {records} records")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    main(sys.argv[1], sys.argv[2])
