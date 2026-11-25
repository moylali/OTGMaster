/*
 * BitLocker cryptography: key stretching, AES-CCM key unwrapping, and the four
 * sector modes BitLocker has used (AES-CBC with an encrypted byte-offset IV, the
 * same with the Elephant diffuser, and AES-XTS), each at 128 and 256 bits.
 *
 * Pure C over mbedtls, no JNI, so the host tests and the app share it unchanged.
 * Format and algorithms follow cryptsetup's lib/bitlk/bitlk.c and the Linux
 * dm-crypt "eboiv" and "elephant" IV generators; the code is written here.
 */
#ifndef OTG_BITLOCKER_CRYPTO_H
#define OTG_BITLOCKER_CRYPTO_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

enum bitlk_mode {
    BITLK_MODE_CBC = 1,          /* AES-CBC, IV = E(K, le64 byte offset) */
    BITLK_MODE_CBC_ELEPHANT = 2, /* the same, with the Elephant diffuser */
    BITLK_MODE_XTS = 3,          /* AES-XTS, tweak = le64 sector number */
};

/*
 * The SHA-256 stretch: 0x100000 rounds over
 * { last_sha256[32], initial_sha256[32], salt[16], le64 count }.
 * `initial` is SHA256(SHA256(UTF-16LE password)) or SHA256(recovery key bytes).
 */
void bitlk_stretch_key(const uint8_t initial[32], const uint8_t salt[16], uint8_t out[32]);

/* AES-CCM decrypt with a 12-byte nonce and 16-byte tag, no AAD. 0 if authentic. */
int bitlk_ccm_decrypt(const uint8_t *key, size_t key_len, const uint8_t nonce[12],
                      const uint8_t tag[16], const uint8_t *in, size_t len, uint8_t *out);

/* Inverse of bitlk_ccm_decrypt; used by the tests to build volumes. */
int bitlk_ccm_encrypt(const uint8_t *key, size_t key_len, const uint8_t nonce[12],
                      const uint8_t *in, size_t len, uint8_t *out, uint8_t tag[16]);

struct bitlk_ctx;

/*
 * Prepares the key schedules for one volume. `key` is the FVEK as BitLocker
 * stores it after the 12-byte header: 16/32 bytes for CBC, 32/64 for XTS, and for
 * Elephant the CBC key followed by the Elephant key (16+16 or 32+32).
 * NULL on a bad mode or length.
 */
struct bitlk_ctx *bitlk_ctx_new(int mode, const uint8_t *key, size_t key_len, uint32_t sector_size);

/* Zeroes and frees every schedule. */
void bitlk_ctx_free(struct bitlk_ctx *ctx);

/*
 * Encrypts (encrypt != 0) or decrypts `len` bytes in place, a whole number of
 * sectors, the first of which is sector `first_sector` in units of the volume's
 * sector size, counted from the start of the volume. 0 on success.
 */
int bitlk_crypt_sectors(const struct bitlk_ctx *ctx, int encrypt, uint64_t first_sector,
                        uint8_t *buf, size_t len);

#ifdef __cplusplus
}
#endif

#endif
