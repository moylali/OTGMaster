# Serpent — provenance

**Cipher:** `serpent_generic.c` from the Linux kernel (`crypto/serpent_generic.c`), Dag Arne
Osvik's bitsliced implementation, **GPL-2.0-or-later** (its SPDX header). Taken from
torvalds/linux at commit `adc218676eef25575469234709c2d87185ca223a` (the v6.12 release), on 2026-10-18. Registered in
`docs/VENDOR_FIXES.md` (Serpent section), with its local patches.

It replaced the unoptimised 1998 NIST reference implementation ("floppy 1", public domain),
which moved individual bits through Serpent's permutations and S-boxes: 0.94 MB/s in XTS on
an x86-64 host and about 0.08 MB/s on a phone, where the bitsliced code does 132 MB/s on the
same host. Same cipher, same byte order: `VeraCryptCascadeTest` opens volumes VeraCrypt
made for every Serpent cascade, and `VeraCryptVolumeCreatorTest` has cryptsetup — which
uses this very kernel code for VeraCrypt Serpent volumes — open the ones the app creates.

**Not taken:** the kernel's `include/crypto/serpent.h`, which is GPL-2.0-only.
`serpent_kernel_shim.h` is new code supplying what the .c needs from kernel headers
(u8/u32, rol32/ror32, the unaligned little-endian helpers, the 132-word context and the
three prototypes).

**New code here (not from any upstream):**
- `serpent_kernel_shim.h` — above.
- `serpent_adapter.c/.h` — a byte-array set-key / encrypt-block / decrypt-block interface.
- `xts_generic.c/.h` — IEEE P1619 XTS over any 128-bit block cipher, cross-checked
  byte for byte against mbedtls_aes_crypt_xts with AES before it was trusted for Serpent.
