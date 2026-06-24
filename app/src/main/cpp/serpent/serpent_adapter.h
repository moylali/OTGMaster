#ifndef SERPENT_ADAPTER_H
#define SERPENT_ADAPTER_H

#include <stdint.h>
#include "serpent-api.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Plain byte-array interface over the unmodified Serpent reference core.
 * Block size is always 16 bytes; key is always 256 bits (32 bytes), matching
 * VeraCrypt's use of Serpent (two 256-bit keys per volume for XTS mode). */

void serpent_set_key_256(const uint8_t key[32], keySchedule khat_out);
void serpent_encrypt_block(keySchedule khat, const uint8_t in[16], uint8_t out[16]);
void serpent_decrypt_block(keySchedule khat, const uint8_t in[16], uint8_t out[16]);

#ifdef __cplusplus
}
#endif

#endif
