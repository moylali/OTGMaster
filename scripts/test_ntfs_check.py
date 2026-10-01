#!/usr/bin/env python3
"""
Proves scripts/ntfs_check.py can see what it claims to see.

A checker that never fails is indistinguishable from one that checks nothing, so
each case here takes a clean image, damages one structure the way a faulty write
path would, and asserts the checker reports exactly that class of error. Needs
mkntfs and ntfscp (ntfs-3g / ntfsprogs).

    python3 scripts/test_ntfs_check.py
"""

import os
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ntfs_check as nc  # noqa: E402


def quiet_report():
    return nc.Report(False, echo=False)


def run_check(path):
    return nc.check(path, 0, quiet_report()).errors


@unittest.skipUnless(shutil.which("mkntfs") and shutil.which("ntfscp"), "needs mkntfs and ntfscp")
class NtfsCheckSeesDamage(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.mkdtemp()
        cls.clean = os.path.join(cls.dir, "clean.img")
        with open(cls.clean, "wb") as f:
            f.truncate(32 << 20)
        subprocess.run(["mkntfs", "-F", "-q", "-f", cls.clean], check=True, capture_output=True)
        payload = os.path.join(cls.dir, "payload.bin")
        with open(payload, "wb") as f:
            f.write(os.urandom(200_000))
        subprocess.run(["ntfscp", cls.clean, payload, "/victim.bin"], check=True, capture_output=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.dir)

    def setUp(self):
        self.img = os.path.join(self.dir, f"{self._testMethodName}.img")
        shutil.copy(self.clean, self.img)
        self.vol = nc.Volume(self.img, 0)
        # Locate the victim: its record and its first cluster.
        rep = quiet_report()
        mft0 = nc.Record(0, nc.apply_fixups(
            self.vol.read(self.vol.mft_lcn * self.vol.cluster, self.vol.rec_size), self.vol.bps, "", rep))
        self.mft_runs = next(a for a in mft0.attrs if a.type == nc.AT_DATA).runs
        mft_size = next(a for a in mft0.attrs if a.type == nc.AT_DATA).data_size
        for no in range(nc.FILE_first_user, mft_size // self.vol.rec_size):
            off = self.record_offset(no)
            raw = self.vol.read(off, self.vol.rec_size)
            if raw[:4] != b"FILE":
                continue
            rec = nc.Record(no, nc.apply_fixups(raw, self.vol.bps, "", rep))
            for a in rec.attrs:
                if a.type == nc.AT_FILE_NAME and nc.parse_file_name(a.value)[3] == "victim.bin".encode("utf-16-le"):
                    self.victim_no, self.victim_off, self.victim_rec = no, off, rec
                    data = next(x for x in rec.attrs if x.type == nc.AT_DATA)
                    self.victim_lcn = data.runs[0][1]
                    return
        self.fail("victim.bin not found")

    def tearDown(self):
        self.vol.f.close()

    def record_offset(self, no):
        byte = no * self.vol.rec_size
        for vcn, lcn, count in self.mft_runs:
            if vcn * self.vol.cluster <= byte < (vcn + count) * self.vol.cluster:
                return lcn * self.vol.cluster + byte - vcn * self.vol.cluster
        raise AssertionError("record outside $MFT")

    def poke(self, off, data):
        with open(self.img, "r+b") as f:
            f.seek(off)
            f.write(data)

    def bitmap_byte_offset(self, cluster):
        rep = quiet_report()
        raw = self.vol.read(self.record_offset(nc.FILE_Bitmap), self.vol.rec_size)
        rec = nc.Record(nc.FILE_Bitmap, nc.apply_fixups(raw, self.vol.bps, "", rep))
        runs = next(a for a in rec.attrs if a.type == nc.AT_DATA).runs
        byte = cluster // 8
        for vcn, lcn, count in runs:
            if vcn * self.vol.cluster <= byte < (vcn + count) * self.vol.cluster:
                return lcn * self.vol.cluster + byte - vcn * self.vol.cluster
        raise AssertionError("cluster outside $Bitmap")

    def flip_bitmap(self, cluster):
        off = self.bitmap_byte_offset(cluster)
        b = self.vol.read(off, 1)[0] ^ (1 << (cluster % 8))
        self.poke(off, bytes([b]))

    def assert_reports(self, fragment):
        errors = run_check(self.img)
        self.assertTrue(any(fragment in e for e in errors),
                        f"expected an error containing {fragment!r}, got {errors}")

    def test_clean_image_is_clean(self):
        self.assertEqual(run_check(self.img), [])

    def test_used_cluster_marked_free(self):
        self.flip_bitmap(self.victim_lcn)
        self.assert_reports("in use but free in $Bitmap")

    def test_leaked_cluster(self):
        self.flip_bitmap(self.vol.nr_clusters - 10)  # far from anything mkntfs placed
        self.assert_reports("owned by nothing")

    def test_torn_record_write(self):
        end = self.victim_off + self.vol.bps - 2
        self.poke(end, bytes(b ^ 0xFF for b in self.vol.read(end, 2)))
        self.assert_reports("update sequence mismatch")

    def test_stale_index_entry(self):
        # Bump the record's sequence number: its index entry now names an older file.
        seq_off = self.victim_off + 0x10
        seq = struct.unpack("<H", self.vol.read(seq_off, 2))[0]
        self.poke(seq_off, struct.pack("<H", seq + 1))
        self.assert_reports("stale entry")

    def test_name_not_indexed(self):
        # Rename the record's FILE_NAME without touching the index.
        raw = bytearray(self.vol.read(self.victim_off, self.vol.rec_size))
        at = raw.find("victim.bin".encode("utf-16-le"))
        self.assertGreater(at, 0)
        if at // self.vol.bps != (at + 2) // self.vol.bps or (at + 1) % self.vol.bps >= self.vol.bps - 2:
            self.skipTest("name straddles a fixup position")
        self.poke(self.victim_off + at, "V".encode("utf-16-le"))
        self.assert_reports("missing from the index")


if __name__ == "__main__":
    unittest.main()
