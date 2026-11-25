/* New code. Standard, cipher-agnostic IEEE P1619 XTS mode of operation,
 * independent of any vendored cipher source. Validated by cross-checking
 * byte-for-byte against mbedtls_aes_crypt_xts (with AES as the block
 * cipher) for identical key/data-unit/data inputs before being trusted for
 * Serpent. See PROVENANCE.md. */

#include "xts_generic.h"
#include <string.h>

/* Multiply the 128-bit tweak (treated as a little-endian polynomial, byte 0
 * = lowest order) by the primitive element alpha (x), per the XTS spec:
 * a left shift of the integer, with overflow out of the top bit fed back
 * by XORing the reduction polynomial constant 0x87 into byte 0. */
static void gf128_mul_by_alpha(uint8_t t[16]) {
    int i;
    uint8_t carry_in = 0, carry_out;
    for (i = 0; i < 16; i++) {
        carry_out = (uint8_t) ((t[i] >> 7) & 1);
        t[i] = (uint8_t) ((t[i] << 1) | carry_in);
        carry_in = carry_out;
    }
    if (carry_in) {
        t[0] ^= 0x87;
    }
}

void xts_generic_crypt(
    void* data_ctx, void* tweak_ctx,
    xts_block_fn data_block_fn, xts_block_fn tweak_encrypt_fn,
    const uint8_t data_unit[16],
    const uint8_t* input, uint8_t* output, int length) {
    uint8_t tweak[16];
    int offset;

    tweak_encrypt_fn(tweak_ctx, data_unit, tweak);

    for (offset = 0; offset < length; offset += 16) {
        int i;
        uint8_t block[16];
        for (i = 0; i < 16; i++) {
            block[i] = input[offset + i] ^ tweak[i];
        }
        data_block_fn(data_ctx, block, block);
        for (i = 0; i < 16; i++) {
            output[offset + i] = block[i] ^ tweak[i];
        }
        gf128_mul_by_alpha(tweak);
    }
}
