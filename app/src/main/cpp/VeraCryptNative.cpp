#include <jni.h>
#include <android/log.h>
#include "mbedtls/aes.h"
#include "mbedtls/pkcs5.h"
#include "mbedtls/platform_util.h"
#include "serpent_adapter.h"
#include "xts_generic.h"

#define TAG "VeraCryptNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Must stay in sync with VeraCryptCipher.nativeId in VeraCryptCipher.kt
#define CIPHER_AES 0
#define CIPHER_SERPENT 1

static void aesEncryptBlockAdapter(void* ctx, const uint8_t in[16], uint8_t out[16]) {
    mbedtls_aes_crypt_ecb((mbedtls_aes_context*) ctx, MBEDTLS_AES_ENCRYPT, in, out);
}
static void aesDecryptBlockAdapter(void* ctx, const uint8_t in[16], uint8_t out[16]) {
    mbedtls_aes_crypt_ecb((mbedtls_aes_context*) ctx, MBEDTLS_AES_DECRYPT, in, out);
}
static void serpentEncryptBlockAdapter(void* ctx, const uint8_t in[16], uint8_t out[16]) {
    serpent_encrypt_block(*(keySchedule*) ctx, in, out);
}
static void serpentDecryptBlockAdapter(void* ctx, const uint8_t in[16], uint8_t out[16]) {
    serpent_decrypt_block(*(keySchedule*) ctx, in, out);
}

// Decrypts `length` bytes of `input` (a multiple of 16) into `output`, using XTS mode with the
// given 64-byte key (first 32 bytes = data key, last 32 bytes = tweak key, the same split VeraCrypt
// uses regardless of which single (non-cascaded) cipher is selected) and 16-byte little-endian
// data unit. Returns 0 on success.
static int xtsCrypt(int cipher, int direction, const unsigned char* key64, const unsigned char* dataUnit,
                     const unsigned char* input, unsigned char* output, int length) {
    if (cipher == CIPHER_AES) {
        mbedtls_aes_xts_context xts_ctx;
        mbedtls_aes_xts_init(&xts_ctx);
        int ret = direction == MBEDTLS_AES_ENCRYPT
                  ? mbedtls_aes_xts_setkey_enc(&xts_ctx, key64, 512)
                  : mbedtls_aes_xts_setkey_dec(&xts_ctx, key64, 512);
        if (ret != 0) {
            mbedtls_aes_xts_free(&xts_ctx);
            return ret;
        }
        ret = mbedtls_aes_crypt_xts(&xts_ctx, direction, length, dataUnit, input, output);
        mbedtls_aes_xts_free(&xts_ctx);
        return ret;
    } else if (cipher == CIPHER_SERPENT) {
        keySchedule khat1, khat2;
        serpent_set_key_256(key64, khat1);
        serpent_set_key_256(key64 + 32, khat2);
        xts_block_fn dataFn = direction == MBEDTLS_AES_ENCRYPT ? serpentEncryptBlockAdapter : serpentDecryptBlockAdapter;
        xts_generic_crypt(&khat1, &khat2, dataFn, serpentEncryptBlockAdapter, dataUnit, input, output, length);
        // An expanded key schedule is key material: it is enough to decrypt the
        // volume, so leaving it on the stack for the next caller to inherit defeats
        // the point of zeroing the master key elsewhere. mbedtls_aes_xts_free does
        // this for the AES branch; Serpent's schedules are plain arrays and need it
        // done by hand.
        mbedtls_platform_zeroize(khat1, sizeof(khat1));
        mbedtls_platform_zeroize(khat2, sizeof(khat2));
        return 0;
    }
    LOGE("Unknown cipher id: %d", cipher);
    return -1;
}

/*
 * Crypts a run of consecutive 512-byte sectors in place, setting the key schedule
 * up once for the whole run.
 *
 * The per-sector entry points below rebuild it every time: mbedtls_aes_xts_setkey_*
 * expands two AES-256 schedules (data key + tweak key) for every 512 bytes, which
 * is 32 AES blocks of payload. The expansion therefore costs on the order of the
 * encryption itself, and it is pure waste — the master key is fixed for the life of
 * the mount. A 64 KiB read rebuilt it 128 times.
 *
 * Working in place on the caller's array also removes the 512-byte allocation and
 * arraycopy that each per-sector call performed, and collapses 128 JNI crossings
 * into one. The block layer is demonstrably CPU-bound (17.26 MB/s awake against
 * 7.27 MB/s at reduced clocks), so this is on the critical path.
 *
 * direction: 0 = decrypt, 1 = encrypt. Returns 0 on success.
 */
extern "C" JNIEXPORT jint JNICALL
Java_app_fayaz_otgmaster_veracrypt_VeraCryptNative_cryptSectorsInPlace(
        JNIEnv *env, jobject thiz, jint cipher, jint direction, jbyteArray jMasterKey,
        jlong startSector, jbyteArray jBuffer, jint offset, jint length) {

    if (length <= 0 || (length % 512) != 0) {
        LOGE("cryptSectorsInPlace: length %d is not a positive multiple of 512", length);
        return -1;
    }
    if (env->GetArrayLength(jMasterKey) < 64) {
        LOGE("cryptSectorsInPlace: master key shorter than 64 bytes");
        return -1;
    }
    if (offset < 0 || offset + length > env->GetArrayLength(jBuffer)) {
        LOGE("cryptSectorsInPlace: range [%d,%d) outside buffer", offset, offset + length);
        return -1;
    }

    jbyte* masterKey = env->GetByteArrayElements(jMasterKey, NULL);
    if (!masterKey) return -1;
    jbyte* buffer = env->GetByteArrayElements(jBuffer, NULL);
    if (!buffer) {
        env->ReleaseByteArrayElements(jMasterKey, masterKey, JNI_ABORT);
        return -1;
    }

    const unsigned char* key64 = (const unsigned char*) masterKey;
    unsigned char* data = (unsigned char*) (buffer + offset);
    const int sectors = length / 512;
    const int mbedDir = (direction == 1) ? MBEDTLS_AES_ENCRYPT : MBEDTLS_AES_DECRYPT;
    int rc = 0;

    if (cipher == CIPHER_AES) {
        mbedtls_aes_xts_context xts_ctx;
        mbedtls_aes_xts_init(&xts_ctx);
        rc = (mbedDir == MBEDTLS_AES_ENCRYPT)
             ? mbedtls_aes_xts_setkey_enc(&xts_ctx, key64, 512)
             : mbedtls_aes_xts_setkey_dec(&xts_ctx, key64, 512);
        if (rc == 0) {
            for (int i = 0; i < sectors && rc == 0; i++) {
                unsigned char data_unit[16] = {0};
                uint64_t sectorNum = (uint64_t) startSector + (uint64_t) i;
                for (int b = 0; b < 8; b++) {
                    data_unit[b] = (unsigned char) ((sectorNum >> (b * 8)) & 0xFF);
                }
                unsigned char* p = data + (size_t) i * 512;
                // mbedtls tolerates input == output, so this is a genuine in-place pass.
                rc = mbedtls_aes_crypt_xts(&xts_ctx, mbedDir, 512, data_unit, p, p);
            }
        }
        mbedtls_aes_xts_free(&xts_ctx);
    } else if (cipher == CIPHER_SERPENT) {
        keySchedule khat1, khat2;
        serpent_set_key_256(key64, khat1);
        serpent_set_key_256(key64 + 32, khat2);
        xts_block_fn dataFn = (mbedDir == MBEDTLS_AES_ENCRYPT)
                              ? serpentEncryptBlockAdapter : serpentDecryptBlockAdapter;
        for (int i = 0; i < sectors; i++) {
            unsigned char data_unit[16] = {0};
            uint64_t sectorNum = (uint64_t) startSector + (uint64_t) i;
            for (int b = 0; b < 8; b++) {
                data_unit[b] = (unsigned char) ((sectorNum >> (b * 8)) & 0xFF);
            }
            unsigned char* p = data + (size_t) i * 512;
            xts_generic_crypt(&khat1, &khat2, dataFn, serpentEncryptBlockAdapter,
                              data_unit, p, p, 512);
        }
        mbedtls_platform_zeroize(khat1, sizeof(khat1));
        mbedtls_platform_zeroize(khat2, sizeof(khat2));
    } else {
        LOGE("cryptSectorsInPlace: unknown cipher id %d", cipher);
        rc = -1;
    }

    env->ReleaseByteArrayElements(jMasterKey, masterKey, JNI_ABORT);
    // 0 commits the (in-place) plaintext back to the Java array.
    env->ReleaseByteArrayElements(jBuffer, buffer, rc == 0 ? 0 : JNI_ABORT);
    return rc;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_fayaz_otgmaster_veracrypt_VeraCryptNative_decryptHeader(JNIEnv *env, jobject thiz, jint cipher, jbyteArray jPassword, jbyteArray jSalt, jint iterations, jbyteArray jEncHeader) {
    jsize pwdLen = env->GetArrayLength(jPassword);
    jbyte* pwd = env->GetByteArrayElements(jPassword, NULL);

    jsize saltLen = env->GetArrayLength(jSalt);
    jbyte* salt = env->GetByteArrayElements(jSalt, NULL);

    jsize headerLen = env->GetArrayLength(jEncHeader);
    jbyte* encHeader = env->GetByteArrayElements(jEncHeader, NULL);

    unsigned char headerKey[64];
    mbedtls_md_context_t md_ctx;
    mbedtls_md_init(&md_ctx);
    const mbedtls_md_info_t *md_info = mbedtls_md_info_from_type(MBEDTLS_MD_SHA512);
    mbedtls_md_setup(&md_ctx, md_info, 1);

    int ret = mbedtls_pkcs5_pbkdf2_hmac(&md_ctx, (const unsigned char*)pwd, pwdLen, (const unsigned char*)salt, saltLen, iterations, 64, headerKey);
    mbedtls_md_free(&md_ctx);

    if (ret != 0) {
        LOGE("PBKDF2 failed: %d", ret);
        mbedtls_platform_zeroize(headerKey, sizeof(headerKey));
        env->ReleaseByteArrayElements(jPassword, pwd, JNI_ABORT);
        env->ReleaseByteArrayElements(jSalt, salt, JNI_ABORT);
        env->ReleaseByteArrayElements(jEncHeader, encHeader, JNI_ABORT);
        return nullptr;
    }

    unsigned char decHeader[448];
    unsigned char data_unit[16] = {0}; // Tweak for header is 0
    ret = xtsCrypt(cipher, MBEDTLS_AES_DECRYPT, headerKey, data_unit, (const unsigned char*)encHeader, decHeader, 448);

    // Wipe the native copy of the password before releasing it. JNI_ABORT rather
    // than 0: mode 0 would copy our zeroes back into the caller's array, destroying
    // a password the caller may still need. Nothing here mutates these arrays, so
    // discarding is correct regardless.
    mbedtls_platform_zeroize(pwd, pwdLen);
    env->ReleaseByteArrayElements(jPassword, pwd, JNI_ABORT);
    env->ReleaseByteArrayElements(jSalt, salt, JNI_ABORT);
    env->ReleaseByteArrayElements(jEncHeader, encHeader, JNI_ABORT);

    if (ret != 0) {
        LOGE("Header XTS decrypt failed: %d", ret);
        mbedtls_platform_zeroize(headerKey, sizeof(headerKey));
        mbedtls_platform_zeroize(decHeader, sizeof(decHeader));
        return nullptr;
    }

    jbyteArray jDecHeader = env->NewByteArray(448);
    env->SetByteArrayRegion(jDecHeader, 0, 448, (jbyte*)decHeader);
    // decHeader holds the decrypted VeraCrypt header, and that includes the 64-byte
    // master key. Leaving 448 bytes of it on the stack for whatever runs next undoes
    // the care taken to zero the key on the Kotlin side.
    mbedtls_platform_zeroize(headerKey, sizeof(headerKey));
    mbedtls_platform_zeroize(decHeader, sizeof(decHeader));
    return jDecHeader;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_fayaz_otgmaster_veracrypt_VeraCryptNative_decryptSector(JNIEnv *env, jobject thiz, jint cipher, jbyteArray jMasterKey, jlong sectorNum, jbyteArray jEncSector) {
    jsize keyLen = env->GetArrayLength(jMasterKey);
    jbyte* masterKey = env->GetByteArrayElements(jMasterKey, NULL);

    jsize sectorLen = env->GetArrayLength(jEncSector);
    jbyte* encSector = env->GetByteArrayElements(jEncSector, NULL);

    unsigned char decSector[512];
    unsigned char data_unit[16] = {0};

    // Copy little-endian sector number into data_unit
    for (int i = 0; i < 8; i++) {
        data_unit[i] = (sectorNum >> (i * 8)) & 0xFF;
    }

    int ret = xtsCrypt(cipher, MBEDTLS_AES_DECRYPT, (const unsigned char*)masterKey, data_unit,
                        (const unsigned char*)encSector, decSector, 512);

    env->ReleaseByteArrayElements(jMasterKey, masterKey, JNI_ABORT);
    env->ReleaseByteArrayElements(jEncSector, encSector, JNI_ABORT);

    if (ret != 0) return nullptr;

    jbyteArray result = env->NewByteArray(512);
    env->SetByteArrayRegion(result, 0, 512, (const jbyte*)decSector);
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_fayaz_otgmaster_veracrypt_VeraCryptNative_encryptSector(JNIEnv *env, jobject thiz, jint cipher, jbyteArray jMasterKey, jlong sectorNum, jbyteArray jUnencSector) {
    jsize keyLen = env->GetArrayLength(jMasterKey);
    jbyte* masterKey = env->GetByteArrayElements(jMasterKey, NULL);

    jsize sectorLen = env->GetArrayLength(jUnencSector);
    jbyte* unencSector = env->GetByteArrayElements(jUnencSector, NULL);

    unsigned char encSector[512];
    unsigned char data_unit[16] = {0};

    // Copy little-endian sector number into data_unit
    for (int i = 0; i < 8; i++) {
        data_unit[i] = (sectorNum >> (i * 8)) & 0xFF;
    }

    int ret = xtsCrypt(cipher, MBEDTLS_AES_ENCRYPT, (const unsigned char*)masterKey, data_unit,
                        (const unsigned char*)unencSector, encSector, 512);

    env->ReleaseByteArrayElements(jMasterKey, masterKey, JNI_ABORT);
    env->ReleaseByteArrayElements(jUnencSector, unencSector, JNI_ABORT);

    if (ret != 0) return nullptr;

    jbyteArray result = env->NewByteArray(512);
    env->SetByteArrayRegion(result, 0, 512, (const jbyte*)encSector);
    return result;
}
