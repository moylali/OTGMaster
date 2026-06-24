/* New code (not part of the original 1998 submission) — see PROVENANCE.md.
 * Validated against the unmodified reference's own serpent-test.c binary
 * output for two independently generated test vectors before being trusted
 * for real volume decryption. */

#include "serpent_adapter.h"
#include "serpent-reference.h"

/* word[i] = the 4 bytes at offset 4*i, interpreted little-endian (bytes[4i] is
 * the least-significant byte of word[i]). This matches VeraCrypt's own
 * Serpent.c convention (`a = LE32(in[0])`, sequential word order) — NOT the
 * original 1998 reference's NIST hex-string convention (which uses reversed
 * word order with big-endian-within-word; see serpent-aux.c's
 * wordsToString/stringToWords). VeraCrypt's on-disk format is what this
 * adapter must match, since it decrypts real VeraCrypt volumes. */
static void bytesToWords(const uint8_t* bytes, WORD* w, int numWords) {
    int i;
    for (i = 0; i < numWords; i++) {
        const uint8_t* p = bytes + i * 4;
        w[i] = ((WORD) p[3] << 24) | ((WORD) p[2] << 16) | ((WORD) p[1] << 8) | (WORD) p[0];
    }
}

static void wordsToBytes(const WORD* w, uint8_t* bytes, int numWords) {
    int i;
    for (i = 0; i < numWords; i++) {
        uint8_t* p = bytes + i * 4;
        p[0] = (uint8_t) (w[i]);
        p[1] = (uint8_t) (w[i] >> 8);
        p[2] = (uint8_t) (w[i] >> 16);
        p[3] = (uint8_t) (w[i] >> 24);
    }
}

void serpent_set_key_256(const uint8_t key[32], keySchedule khat_out) {
    KEY userKey;
    bytesToWords(key, userKey, WORDS_PER_KEY);
    makeSubkeys(userKey, khat_out);
}

void serpent_encrypt_block(keySchedule khat, const uint8_t in[16], uint8_t out[16]) {
    BLOCK pt, ct;
    bytesToWords(in, pt, WORDS_PER_BLOCK);
    encryptGivenKHat(pt, khat, ct);
    wordsToBytes(ct, out, WORDS_PER_BLOCK);
}

void serpent_decrypt_block(keySchedule khat, const uint8_t in[16], uint8_t out[16]) {
    BLOCK ct, pt;
    bytesToWords(in, ct, WORDS_PER_BLOCK);
    decryptGivenKHat(ct, khat, pt);
    wordsToBytes(pt, out, WORDS_PER_BLOCK);
}
