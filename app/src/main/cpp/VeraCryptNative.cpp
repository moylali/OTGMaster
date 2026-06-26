#include <jni.h>
#include <android/log.h>
#include "mbedtls/aes.h"
#include "mbedtls/pkcs5.h"
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
        return 0;
    }
    LOGE("Unknown cipher id: %d", cipher);
    return -1;
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
        env->ReleaseByteArrayElements(jPassword, pwd, JNI_ABORT);
        env->ReleaseByteArrayElements(jSalt, salt, JNI_ABORT);
        env->ReleaseByteArrayElements(jEncHeader, encHeader, JNI_ABORT);
        return nullptr;
    }

    unsigned char decHeader[448];
    unsigned char data_unit[16] = {0}; // Tweak for header is 0
    ret = xtsCrypt(cipher, MBEDTLS_AES_DECRYPT, headerKey, data_unit, (const unsigned char*)encHeader, decHeader, 448);

    env->ReleaseByteArrayElements(jPassword, pwd, 0);
    env->ReleaseByteArrayElements(jSalt, salt, 0);
    env->ReleaseByteArrayElements(jEncHeader, encHeader, 0);

    if (ret != 0) {
        LOGE("Header XTS decrypt failed: %d", ret);
        return nullptr;
    }

    jbyteArray jDecHeader = env->NewByteArray(448);
    env->SetByteArrayRegion(jDecHeader, 0, 448, (jbyte*)decHeader);
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
