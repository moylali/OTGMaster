#include "vc_cascade.h"

#include <stdlib.h>
#include <string.h>

#include "mbedtls/aes.h"
#include "mbedtls/platform_util.h"
#include "serpent_adapter.h"
#include "xts_generic.h"
#include "tomcrypt.h"

namespace {

struct Stage {
    int id;
    union {
        // mbedtls keeps the data-key schedule per direction, so AES holds both;
        // the tweak half of each is the same encryption schedule.
        struct { mbedtls_aes_xts_context enc, dec; } aes;
        struct { keySchedule data, tweak; } serpent;
        struct { symmetric_key data, tweak; } twofish;
    } u;
};

void serpentEncrypt(void* ctx, const uint8_t in[16], uint8_t out[16]) {
    serpent_encrypt_block(*(keySchedule*) ctx, in, out);
}
void serpentDecrypt(void* ctx, const uint8_t in[16], uint8_t out[16]) {
    serpent_decrypt_block(*(keySchedule*) ctx, in, out);
}
void twofishEncrypt(void* ctx, const uint8_t in[16], uint8_t out[16]) {
    twofish_ecb_encrypt(in, out, (symmetric_key*) ctx);
}
void twofishDecrypt(void* ctx, const uint8_t in[16], uint8_t out[16]) {
    twofish_ecb_decrypt(in, out, (symmetric_key*) ctx);
}

void unitTweak(uint64_t unit_no, uint8_t tweak[16]) {
    memset(tweak, 0, 16);
    for (int b = 0; b < 8; b++) tweak[b] = (uint8_t) (unit_no >> (b * 8));
}

/* primary and secondary are this cipher's 32-byte XTS keys. Returns false on failure. */
bool stageInit(Stage* s, int id, const uint8_t* primary, const uint8_t* secondary) {
    s->id = id;
    switch (id) {
        case VC_CIPHER_AES: {
            // mbedtls takes the XTS key as one 64-byte block, data key first.
            uint8_t key[64];
            memcpy(key, primary, 32);
            memcpy(key + 32, secondary, 32);
            mbedtls_aes_xts_init(&s->u.aes.enc);
            mbedtls_aes_xts_init(&s->u.aes.dec);
            bool ok = mbedtls_aes_xts_setkey_enc(&s->u.aes.enc, key, 512) == 0 &&
                      mbedtls_aes_xts_setkey_dec(&s->u.aes.dec, key, 512) == 0;
            mbedtls_platform_zeroize(key, sizeof(key));
            return ok;
        }
        case VC_CIPHER_SERPENT:
            serpent_set_key_256(primary, s->u.serpent.data);
            serpent_set_key_256(secondary, s->u.serpent.tweak);
            return true;
        case VC_CIPHER_TWOFISH:
            return twofish_setup(primary, 32, 0, &s->u.twofish.data) == CRYPT_OK &&
                   twofish_setup(secondary, 32, 0, &s->u.twofish.tweak) == CRYPT_OK;
        default:
            return false;
    }
}

void stageFree(Stage* s) {
    if (s->id == VC_CIPHER_AES) {
        mbedtls_aes_xts_free(&s->u.aes.enc);
        mbedtls_aes_xts_free(&s->u.aes.dec);
    }
    // An expanded key schedule decrypts the volume as well as the key does.
    mbedtls_platform_zeroize(s, sizeof(*s));
}

void stageCrypt(const Stage* s, bool encrypt, const uint8_t tweak[16], uint8_t* buf, int length) {
    Stage* m = const_cast<Stage*>(s);  // the block functions take non-const contexts
    switch (s->id) {
        case VC_CIPHER_AES:
            // mbedtls tolerates input == output, so this is a genuine in-place pass.
            mbedtls_aes_crypt_xts(encrypt ? &m->u.aes.enc : &m->u.aes.dec,
                                  encrypt ? MBEDTLS_AES_ENCRYPT : MBEDTLS_AES_DECRYPT,
                                  (size_t) length, tweak, buf, buf);
            break;
        case VC_CIPHER_SERPENT:
            xts_generic_crypt(&m->u.serpent.data, &m->u.serpent.tweak,
                              encrypt ? serpentEncrypt : serpentDecrypt, serpentEncrypt,
                              tweak, buf, buf, length);
            break;
        case VC_CIPHER_TWOFISH:
            xts_generic_crypt(&m->u.twofish.data, &m->u.twofish.tweak,
                              encrypt ? twofishEncrypt : twofishDecrypt, twofishEncrypt,
                              tweak, buf, buf, length);
            break;
    }
}

}  // namespace

struct VcCascade {
    int n;
    Stage stages[VC_MAX_CASCADE];
};

VcCascade* vc_cascade_new(const int* ids, int n, const uint8_t* key, size_t key_len) {
    if (n < 1 || n > VC_MAX_CASCADE || key_len < (size_t) 64 * n) return nullptr;
    VcCascade* c = (VcCascade*) calloc(1, sizeof(VcCascade));
    if (!c) return nullptr;
    for (int i = 0; i < n; i++) {
        // Primary keys first, all of them, then the secondary (tweak) keys.
        if (!stageInit(&c->stages[i], ids[i], key + 32 * i, key + 32 * (n + i))) {
            c->n = i + 1;  // free what was set up, including the failed stage
            vc_cascade_free(c);
            return nullptr;
        }
    }
    c->n = n;
    return c;
}

void vc_cascade_free(VcCascade* c) {
    if (!c) return;
    for (int i = 0; i < c->n; i++) stageFree(&c->stages[i]);
    mbedtls_platform_zeroize(c, sizeof(*c));
    free(c);
}

void vc_cascade_encrypt_unit(const VcCascade* c, uint64_t unit_no, uint8_t* buf, int length) {
    uint8_t tweak[16];
    unitTweak(unit_no, tweak);
    for (int i = 0; i < c->n; i++) stageCrypt(&c->stages[i], true, tweak, buf, length);
}

void vc_cascade_decrypt_unit(const VcCascade* c, uint64_t unit_no, uint8_t* buf, int length) {
    uint8_t tweak[16];
    unitTweak(unit_no, tweak);
    for (int i = c->n - 1; i >= 0; i--) stageCrypt(&c->stages[i], false, tweak, buf, length);
}
