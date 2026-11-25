#ifndef VC_CASCADE_H
#define VC_CASCADE_H

#include <stddef.h>
#include <stdint.h>

/*
 * VeraCrypt XTS for one cipher or a cascade of up to three, shared by the
 * header decrypt and the sector path (and by LUKS, as a one-cipher cascade).
 *
 * Cipher ids must stay in sync with SingleCipher.nativeId in VeraCryptCipher.kt.
 *
 * `ids` is the cascade in VeraCrypt's own table order (EncryptionAlgorithms[] in
 * src/Common/Crypto.c), which is the reverse of the name it shows: "AES-Twofish"
 * is { TWOFISH, AES }. That order is the encryption order and the key order:
 *
 *   key = primary[0] primary[1] .. primary[n-1]  secondary[0] .. secondary[n-1]
 *
 * 32 bytes each, so 64 * n bytes in all. Encryption applies ids[0] first;
 * decryption applies ids[n-1] first. Every stage of a data unit uses the same
 * tweak (the data unit number), as VeraCrypt's EncryptBuffer does.
 */

#define VC_CIPHER_AES     0
#define VC_CIPHER_SERPENT 1
#define VC_CIPHER_TWOFISH 2

#define VC_MAX_CASCADE 3

typedef struct VcCascade VcCascade;

/* Returns NULL for an unknown id, n outside 1..VC_MAX_CASCADE, or key_len < 64 * n. */
VcCascade* vc_cascade_new(const int* ids, int n, const uint8_t* key, size_t key_len);

/* Zeroes every key schedule, then frees. Accepts NULL. */
void vc_cascade_free(VcCascade* c);

/*
 * Crypts `length` bytes (a multiple of 16) in place as one data unit: the tweak is
 * the 16-byte little-endian `unit_no`. A 512-byte sector is one unit; the 448-byte
 * header is one unit numbered 0.
 */
void vc_cascade_encrypt_unit(const VcCascade* c, uint64_t unit_no, uint8_t* buf, int length);
void vc_cascade_decrypt_unit(const VcCascade* c, uint64_t unit_no, uint8_t* buf, int length);

#endif
