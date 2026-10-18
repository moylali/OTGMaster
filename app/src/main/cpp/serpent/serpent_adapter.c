/* New code (not from any upstream) — see PROVENANCE.md.
 *
 * Byte order: the Linux implementation reads each 16-byte block and the key as
 * little-endian words in sequence (get_unaligned_le32), which is VeraCrypt's own
 * convention — cryptsetup opens VeraCrypt Serpent volumes with this same kernel code.
 * VeraCryptCascadeTest checks it against volumes VeraCrypt made, for every cascade. */

#include "serpent_adapter.h"

void serpent_set_key_256(const uint8_t key[32], serpent_key* out) {
    __serpent_setkey(out, key, 32);
}

void serpent_encrypt_block(const serpent_key* key, const uint8_t in[16], uint8_t out[16]) {
    __serpent_encrypt(key, out, in);
}

void serpent_decrypt_block(const serpent_key* key, const uint8_t in[16], uint8_t out[16]) {
    __serpent_decrypt(key, out, in);
}
