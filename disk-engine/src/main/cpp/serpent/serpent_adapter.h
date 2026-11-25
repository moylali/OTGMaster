/* New code (not from any upstream) — see PROVENANCE.md. A byte-array interface to the
 * vendored Linux Serpent (serpent_generic.c) for VeraCrypt's XTS sectors. */
#ifndef SERPENT_ADAPTER_H
#define SERPENT_ADAPTER_H

#include <stdint.h>
#include "serpent_kernel_shim.h"

#ifdef __cplusplus
extern "C" {
#endif

/** A 256-bit key's expanded schedule: 132 words, built once per volume. */
typedef struct serpent_ctx serpent_key;

void serpent_set_key_256(const uint8_t key[32], serpent_key* out);
void serpent_encrypt_block(const serpent_key* key, const uint8_t in[16], uint8_t out[16]);
void serpent_decrypt_block(const serpent_key* key, const uint8_t in[16], uint8_t out[16]);

#ifdef __cplusplus
}
#endif

#endif
