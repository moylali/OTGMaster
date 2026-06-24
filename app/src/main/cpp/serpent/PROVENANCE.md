# Serpent reference implementation — provenance

Source: the unoptimised ANSI C reference implementation ("floppy 1") from the
original 1998 NIST AES-candidate submission for Serpent, by Frank Stajano
(Olivetti Oracle Research Laboratory / Cambridge University Computer
Laboratory), under the direction of the cipher's designers (Ross Anderson,
Eli Biham, Lars Knudsen). Retrieved from the designers' own page at
https://www.cl.cam.ac.uk/~rja14/serpent.html, which states: "Serpent is now
completely in the public domain, and we impose no restrictions on its use."

That page separately notes that the "optimised implementations" bundled in
the same submission package were later relicensed under the GPL — this
vendor directory deliberately uses only the unoptimised reference
implementation, which is unaffected by that relicensing.

Files `serpent-api.h`, `serpent-tables.h`, `serpent-reference.h`,
`serpent-reference.c`, `serpent-aux.h`, `serpent-aux.c` are vendored
unmodified except for one change: `serpent-api.h`'s `WORD` typedef was
changed from `unsigned long` (32-bit on some platforms, 64-bit on others,
including Android's arm64-v8a ABI) to the explicitly-sized `uint32_t`, since
the cipher's bit-indexing macros assume a 32-bit word. This change was
verified to preserve correctness by rebuilding the original, unmodified
`serpent-test.c` harness before and after the change and confirming
identical output for known test inputs.

`serpent_adapter.c`/`.h` is new code (not from the original submission)
providing a plain byte-array `set_key`/`encrypt_block`/`decrypt_block`
interface on top of the unmodified core (`makeSubkeys`, `encryptGivenKHat`,
`decryptGivenKHat`), avoiding the original NIST hex-string API which is too
slow for per-sector use.

The byte-array-to-word-array mapping went through two iterations:

1. The first version matched the original 1998 reference's own NIST
   hex-string convention (`serpent-aux.c`'s `wordsToString`/`stringToWords`:
   reversed word order, big-endian within each word), and was validated
   against the unmodified reference binary's own output for two independently
   generated test vectors — confirming the *core cipher logic* was ported
   correctly, but not yet checked against VeraCrypt's on-disk format.
2. End-to-end testing against a real VeraCrypt-created Serpent volume failed
   ("Magic bytes 'VERA' not found"), because VeraCrypt's own `Serpent.c` uses
   a different convention: sequential word order with little-endian-within-
   word (`a = LE32(in[0])`, confirmed by reading VeraCrypt's source directly).
   The adapter was corrected to match VeraCrypt's convention instead — this
   only changes the byte\<-\>word translation layer, not the vendored
   cryptographic logic, and was re-confirmed via round-trip (encrypt then
   decrypt recovers the original plaintext) plus the real end-to-end mount
   test against `testdata/serpent` in the e2e suite.
