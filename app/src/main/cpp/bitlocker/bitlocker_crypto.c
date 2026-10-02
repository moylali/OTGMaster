#include "bitlocker_crypto.h"

#include <stdlib.h>
#include <string.h>

#include "mbedtls/aes.h"
#include "mbedtls/ccm.h"
#include "mbedtls/platform_util.h"
#include "mbedtls/sha256.h"

#define KDF_ROUNDS 0x100000

void bitlk_stretch_key(const uint8_t initial[32], const uint8_t salt[16], uint8_t out[32])
{
    /* last_sha256 | initial_sha256 | salt | le64 count */
    uint8_t block[32 + 32 + 16 + 8];
    memset(block, 0, sizeof(block));
    memcpy(block + 32, initial, 32);
    memcpy(block + 64, salt, 16);
    for (uint64_t i = 0; i < KDF_ROUNDS; i++) {
        for (int b = 0; b < 8; b++) block[80 + b] = (uint8_t) (i >> (8 * b));
        mbedtls_sha256_ret(block, sizeof(block), block, 0);
    }
    memcpy(out, block, 32);
    mbedtls_platform_zeroize(block, sizeof(block));
}

int bitlk_ccm_decrypt(const uint8_t *key, size_t key_len, const uint8_t nonce[12],
                      const uint8_t tag[16], const uint8_t *in, size_t len, uint8_t *out)
{
    mbedtls_ccm_context ccm;
    mbedtls_ccm_init(&ccm);
    int rc = mbedtls_ccm_setkey(&ccm, MBEDTLS_CIPHER_ID_AES, key, (unsigned) key_len * 8);
    if (rc == 0) rc = mbedtls_ccm_auth_decrypt(&ccm, len, nonce, 12, NULL, 0, in, out, tag, 16);
    mbedtls_ccm_free(&ccm);
    return rc;
}

int bitlk_ccm_encrypt(const uint8_t *key, size_t key_len, const uint8_t nonce[12],
                      const uint8_t *in, size_t len, uint8_t *out, uint8_t tag[16])
{
    mbedtls_ccm_context ccm;
    mbedtls_ccm_init(&ccm);
    int rc = mbedtls_ccm_setkey(&ccm, MBEDTLS_CIPHER_ID_AES, key, (unsigned) key_len * 8);
    if (rc == 0) rc = mbedtls_ccm_encrypt_and_tag(&ccm, len, nonce, 12, NULL, 0, in, out, tag, 16);
    mbedtls_ccm_free(&ccm);
    return rc;
}

struct bitlk_ctx {
    int mode;
    uint32_t sector_size;
    mbedtls_aes_context enc;        /* CBC: encrypt schedule, also makes the IV */
    mbedtls_aes_context dec;        /* CBC: decrypt schedule */
    mbedtls_aes_context elephant;   /* Elephant: sector-key schedule */
    mbedtls_aes_xts_context xts_enc;
    mbedtls_aes_xts_context xts_dec;
};

struct bitlk_ctx *bitlk_ctx_new(int mode, const uint8_t *key, size_t key_len, uint32_t sector_size)
{
    if (sector_size != 512 && sector_size != 4096) return NULL;
    struct bitlk_ctx *c = calloc(1, sizeof(*c));
    if (!c) return NULL;
    c->mode = mode;
    c->sector_size = sector_size;
    mbedtls_aes_init(&c->enc);
    mbedtls_aes_init(&c->dec);
    mbedtls_aes_init(&c->elephant);
    mbedtls_aes_xts_init(&c->xts_enc);
    mbedtls_aes_xts_init(&c->xts_dec);
    int rc = -1;
    switch (mode) {
    case BITLK_MODE_CBC:
        if (key_len == 16 || key_len == 32)
            rc = mbedtls_aes_setkey_enc(&c->enc, key, (unsigned) key_len * 8) |
                 mbedtls_aes_setkey_dec(&c->dec, key, (unsigned) key_len * 8);
        break;
    case BITLK_MODE_CBC_ELEPHANT:
        if (key_len == 32 || key_len == 64) {
            size_t half = key_len / 2;
            rc = mbedtls_aes_setkey_enc(&c->enc, key, (unsigned) half * 8) |
                 mbedtls_aes_setkey_dec(&c->dec, key, (unsigned) half * 8) |
                 mbedtls_aes_setkey_enc(&c->elephant, key + half, (unsigned) half * 8);
        }
        break;
    case BITLK_MODE_XTS:
        if (key_len == 32 || key_len == 64)
            rc = mbedtls_aes_xts_setkey_enc(&c->xts_enc, key, (unsigned) key_len * 8) |
                 mbedtls_aes_xts_setkey_dec(&c->xts_dec, key, (unsigned) key_len * 8);
        break;
    }
    if (rc != 0) {
        bitlk_ctx_free(c);
        return NULL;
    }
    return c;
}

void bitlk_ctx_free(struct bitlk_ctx *c)
{
    if (!c) return;
    mbedtls_aes_free(&c->enc);
    mbedtls_aes_free(&c->dec);
    mbedtls_aes_free(&c->elephant);
    mbedtls_aes_xts_free(&c->xts_enc);
    mbedtls_aes_xts_free(&c->xts_dec);
    mbedtls_platform_zeroize(c, sizeof(*c));
    free(c);
}

/* ---- Elephant diffuser (Microsoft, "AES-CBC + Elephant diffuser", 2006) ---- */

static inline uint32_t rd32(const uint8_t *p)
{
    return (uint32_t) p[0] | (uint32_t) p[1] << 8 | (uint32_t) p[2] << 16 | (uint32_t) p[3] << 24;
}

static inline void wr32(uint8_t *p, uint32_t v)
{
    p[0] = (uint8_t) v; p[1] = (uint8_t) (v >> 8); p[2] = (uint8_t) (v >> 16); p[3] = (uint8_t) (v >> 24);
}

#define ROL(x, n) ((uint32_t) ((x) << (n) | (x) >> (32 - (n))))

static void diffuser_a_decrypt(uint32_t *d, int n)
{
    for (int r = 0; r < 5; r++) {
        int i1 = 0, i2 = n - 2, i3 = n - 5;
        while (i1 < n - 1) {
            d[i1] += d[i2] ^ ROL(d[i3], 9);
            i1++; i2++; i3++;
            if (i3 >= n) i3 -= n;
            d[i1] += d[i2] ^ d[i3];
            i1++; i2++; i3++;
            if (i2 >= n) i2 -= n;
            d[i1] += d[i2] ^ ROL(d[i3], 13);
            i1++; i2++; i3++;
            d[i1] += d[i2] ^ d[i3];
            i1++; i2++; i3++;
        }
    }
}

static void diffuser_a_encrypt(uint32_t *d, int n)
{
    for (int r = 0; r < 5; r++) {
        int i1 = n - 1, i2 = n - 2 - 1, i3 = n - 5 - 1;
        while (i1 > 0) {
            d[i1] -= d[i2] ^ d[i3];
            i1--; i2--; i3--;
            d[i1] -= d[i2] ^ ROL(d[i3], 13);
            i1--; i2--; i3--;
            if (i2 < 0) i2 += n;
            d[i1] -= d[i2] ^ d[i3];
            i1--; i2--; i3--;
            if (i3 < 0) i3 += n;
            d[i1] -= d[i2] ^ ROL(d[i3], 9);
            i1--; i2--; i3--;
        }
    }
}

static void diffuser_b_decrypt(uint32_t *d, int n)
{
    for (int r = 0; r < 3; r++) {
        int i1 = 0, i2 = 2, i3 = 5;
        while (i1 < n - 1) {
            d[i1] += d[i2] ^ d[i3];
            i1++; i2++; i3++;
            d[i1] += d[i2] ^ ROL(d[i3], 10);
            i1++; i2++; i3++;
            if (i2 >= n) i2 -= n;
            d[i1] += d[i2] ^ d[i3];
            i1++; i2++; i3++;
            if (i3 >= n) i3 -= n;
            d[i1] += d[i2] ^ ROL(d[i3], 25);
            i1++; i2++; i3++;
        }
    }
}

static void diffuser_b_encrypt(uint32_t *d, int n)
{
    for (int r = 0; r < 3; r++) {
        int i1 = n - 1, i2 = 2 - 1, i3 = 5 - 1;
        while (i1 > 0) {
            d[i1] -= d[i2] ^ ROL(d[i3], 25);
            i1--; i2--; i3--;
            if (i3 < 0) i3 += n;
            d[i1] -= d[i2] ^ d[i3];
            i1--; i2--; i3--;
            if (i2 < 0) i2 += n;
            d[i1] -= d[i2] ^ ROL(d[i3], 10);
            i1--; i2--; i3--;
            d[i1] -= d[i2] ^ d[i3];
            i1--; i2--; i3--;
        }
    }
}

/* XOR the sector key, then (writing) diffuse, or (reading) un-diffuse then XOR. */
static void elephant(const struct bitlk_ctx *c, uint64_t byte_offset, uint8_t *sec, int encrypt)
{
    uint8_t es[16] = {0}, ks[32];
    for (int b = 0; b < 8; b++) es[b] = (uint8_t) (byte_offset >> (8 * b));
    mbedtls_aes_crypt_ecb((mbedtls_aes_context *) &c->elephant, MBEDTLS_AES_ENCRYPT, es, ks);
    es[15] = 0x80;
    mbedtls_aes_crypt_ecb((mbedtls_aes_context *) &c->elephant, MBEDTLS_AES_ENCRYPT, es, ks + 16);

    int n = (int) (c->sector_size / 4);
    uint32_t w[4096 / 4];
    if (!encrypt) {
        for (int i = 0; i < n; i++) w[i] = rd32(sec + 4 * i);
        diffuser_b_decrypt(w, n);
        diffuser_a_decrypt(w, n);
        for (int i = 0; i < n; i++) wr32(sec + 4 * i, w[i]);
    }
    for (uint32_t i = 0; i < c->sector_size; i++) sec[i] ^= ks[i % 32];
    if (encrypt) {
        for (int i = 0; i < n; i++) w[i] = rd32(sec + 4 * i);
        diffuser_a_encrypt(w, n);
        diffuser_b_encrypt(w, n);
        for (int i = 0; i < n; i++) wr32(sec + 4 * i, w[i]);
    }
    mbedtls_platform_zeroize(ks, sizeof(ks));
    mbedtls_platform_zeroize(w, sizeof(w));
}

int bitlk_crypt_sectors(const struct bitlk_ctx *c, int encrypt, uint64_t first_sector,
                        uint8_t *buf, size_t len)
{
    if (!c || len % c->sector_size != 0) return -1;
    size_t count = len / c->sector_size;
    for (size_t s = 0; s < count; s++) {
        uint8_t *sec = buf + s * c->sector_size;
        uint64_t sector = first_sector + s;
        uint8_t iv[16] = {0};
        int rc;
        if (c->mode == BITLK_MODE_XTS) {
            for (int b = 0; b < 8; b++) iv[b] = (uint8_t) (sector >> (8 * b));
            rc = mbedtls_aes_crypt_xts((mbedtls_aes_xts_context *) (encrypt ? &c->xts_enc : &c->xts_dec),
                                       encrypt ? MBEDTLS_AES_ENCRYPT : MBEDTLS_AES_DECRYPT,
                                       c->sector_size, iv, sec, sec);
        } else {
            uint64_t byte_offset = sector * c->sector_size;
            uint8_t off[16] = {0};
            for (int b = 0; b < 8; b++) off[b] = (uint8_t) (byte_offset >> (8 * b));
            mbedtls_aes_crypt_ecb((mbedtls_aes_context *) &c->enc, MBEDTLS_AES_ENCRYPT, off, iv);
            if (encrypt && c->mode == BITLK_MODE_CBC_ELEPHANT) elephant(c, byte_offset, sec, 1);
            rc = mbedtls_aes_crypt_cbc((mbedtls_aes_context *) (encrypt ? &c->enc : &c->dec),
                                       encrypt ? MBEDTLS_AES_ENCRYPT : MBEDTLS_AES_DECRYPT,
                                       c->sector_size, iv, sec, sec);
            if (rc == 0 && !encrypt && c->mode == BITLK_MODE_CBC_ELEPHANT) elephant(c, byte_offset, sec, 0);
        }
        if (rc != 0) return rc;
    }
    return 0;
}
