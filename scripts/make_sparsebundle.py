#!/usr/bin/env python3
"""
Cuts a raw disk image into a macOS sparse bundle (a folder: Info.plist, token, bands/<hex>),
for Container Master's tests, since this host has no hdiutil. Bands that are all zeros are left
out, as hdiutil leaves them, so the reader's missing-band path runs too.

    python3 scripts/make_sparsebundle.py IN.img OUT.sparsebundle [--band-size 1048576]
"""
import argparse
import os

PLIST = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
\t<key>CFBundleInfoDictionaryVersion</key>
\t<string>6.0</string>
\t<key>band-size</key>
\t<integer>{band}</integer>
\t<key>bundle-backingstore-version</key>
\t<integer>1</integer>
\t<key>diskimage-bundle-type</key>
\t<string>com.apple.diskimage.sparsebundle</string>
\t<key>size</key>
\t<integer>{size}</integer>
</dict>
</plist>
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("out")
    ap.add_argument("--band-size", type=int, default=1 << 20)
    a = ap.parse_args()
    size = os.path.getsize(a.src)
    os.makedirs(os.path.join(a.out, "bands"), exist_ok=True)
    with open(os.path.join(a.out, "Info.plist"), "w") as f:
        f.write(PLIST.format(band=a.band_size, size=size))
    open(os.path.join(a.out, "token"), "wb").close()
    with open(a.src, "rb") as src:
        i = 0
        while True:
            band = src.read(a.band_size)
            if not band:
                break
            if band.count(0) != len(band):
                with open(os.path.join(a.out, "bands", format(i, "x")), "wb") as f:
                    f.write(band.rstrip(b"\0") if i % 2 else band)   # some bands short, as a sparse file leaves them
            i += 1


if __name__ == "__main__":
    main()
