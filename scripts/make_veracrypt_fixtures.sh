#!/usr/bin/env bash
# Regenerates app/src/test/resources/veracrypt/: one fixture per VeraCrypt cipher the app
# supports, with each supported header KDF, made by VeraCrypt itself (docs/TEST_DATA.md).
#
#   scripts/make_veracrypt_fixtures.sh            # needs veracrypt >= 1.26.24 (Argon2) on PATH
#
# Each fixture is 1024 bytes cut from a 2 MiB file container that VeraCrypt formatted as FAT:
#   0..511     the volume header sector (salt + encrypted header)
#   512..1023  the first sector of the data area, at byte 131072 — a FAT boot sector,
#              encrypted with the volume's master key and XTS data unit 256.
# That is enough to check both halves of an unlock against the real thing: the header key
# derivation and cascade (it must decrypt to "VERA"), and the master-key layout and data
# path (the boot sector must end in 55 AA). VeracryptCascadeTest reads them.
#
# A password of at least 20 characters is what lets VeraCrypt accept PIM 1, which keeps
# the tests fast: 16000 PBKDF2 iterations, or Argon2id over 64 MiB with 3 passes.
# No root: VeraCrypt formats FAT inside a file container itself. Every run produces new
# random keys, so regenerating changes every byte; nothing else may depend on them.
set -euo pipefail
cd "$(dirname "$0")/.."
out=app/src/test/resources/veracrypt
password=otgmaster-test-password-2026
ciphers=(AES Serpent Twofish AES-Twofish AES-Twofish-Serpent Serpent-AES Serpent-Twofish-AES Twofish-Serpent)
hashes=(SHA-512 Argon2)

command -v veracrypt >/dev/null || { echo "veracrypt not on PATH" >&2; exit 1; }
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$out"
for c in "${ciphers[@]}"; do
    for h in "${hashes[@]}"; do
        vol="$tmp/$c-$h.hc"
        veracrypt --text --non-interactive --create "$vol" --size=2M --volume-type=normal \
            --encryption="$c" --hash="$h" --filesystem=fat --password="$password" --pim=1 \
            --random-source=/dev/urandom >/dev/null
        { dd if="$vol" bs=512 count=1 status=none; dd if="$vol" bs=512 skip=256 count=1 status=none; } \
            > "$out/$c-$h.bin"
        echo "$out/$c-$h.bin"
    done
done
veracrypt --text --version > "$out/VERSION"
