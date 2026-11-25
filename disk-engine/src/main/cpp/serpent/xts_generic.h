#ifndef XTS_GENERIC_H
#define XTS_GENERIC_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Cipher-agnostic XTS mode (IEEE P1619), matching the same data-unit/tweak
 * conventions as mbedtls_aes_crypt_xts: the "data unit" (here, the sector
 * number) is supplied by the caller as a 16-byte little-endian value; this
 * function encrypts the tweak with the second key, then iterates the
 * GF(2^128) tweak update per consecutive 16-byte block as it processes
 * `length` bytes (which must be a multiple of 16) starting at that data
 * unit's first block.
 *
 * ctx1/ctx2 are opaque per-cipher key-schedule contexts; encrypt_block is
 * used for both the tweak encryption and (for XTS encryption) the data
 * encryption; decrypt_block is used for XTS decryption of the data (the
 * tweak itself is always *encrypted*, even when decrypting data — this is
 * part of the XTS spec, not a bug). */
typedef void (*xts_block_fn)(void* ctx, const uint8_t in[16], uint8_t out[16]);

void xts_generic_crypt(
    void* data_ctx, void* tweak_ctx,
    xts_block_fn data_block_fn, xts_block_fn tweak_encrypt_fn,
    const uint8_t data_unit[16],
    const uint8_t* input, uint8_t* output, int length);

#ifdef __cplusplus
}
#endif

#endif
