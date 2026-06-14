#include <jni.h>
#include <android/log.h>
#include "mbedtls/aes.h"
#include "mbedtls/pkcs5.h"

#define TAG "VeraCryptNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_fayaz_otgmaster_veracrypt_VeraCryptNative_decryptHeader(JNIEnv *env, jobject thiz, jbyteArray jPassword, jbyteArray jSalt, jint iterations, jbyteArray jEncHeader) {
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

    mbedtls_aes_xts_context xts_ctx;
    mbedtls_aes_xts_init(&xts_ctx);
    ret = mbedtls_aes_xts_setkey_dec(&xts_ctx, headerKey, 512);

    if (ret != 0) {
        LOGE("AES XTS setkey failed: %d", ret);
        mbedtls_aes_xts_free(&xts_ctx);
        env->ReleaseByteArrayElements(jPassword, pwd, JNI_ABORT);
        env->ReleaseByteArrayElements(jSalt, salt, JNI_ABORT);
        env->ReleaseByteArrayElements(jEncHeader, encHeader, JNI_ABORT);
        return nullptr;
    }

    unsigned char decHeader[448];
    unsigned char data_unit[16] = {0}; // Tweak for header is 0
    ret = mbedtls_aes_crypt_xts(&xts_ctx, MBEDTLS_AES_DECRYPT, 448, data_unit, (const unsigned char*)encHeader, decHeader);

    if (ret != 0) {
        mbedtls_aes_xts_free(&xts_ctx);
        env->ReleaseByteArrayElements(jPassword, pwd, 0);
        env->ReleaseByteArrayElements(jSalt, salt, 0);
        env->ReleaseByteArrayElements(jEncHeader, encHeader, 0);
        return nullptr;
    }

    mbedtls_aes_xts_free(&xts_ctx);
    env->ReleaseByteArrayElements(jPassword, pwd, 0);
    env->ReleaseByteArrayElements(jSalt, salt, 0);
    env->ReleaseByteArrayElements(jEncHeader, encHeader, 0);

    jbyteArray jDecHeader = env->NewByteArray(448);
    env->SetByteArrayRegion(jDecHeader, 0, 448, (jbyte*)decHeader);
    return jDecHeader;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_fayaz_otgmaster_veracrypt_VeraCryptNative_decryptSector(JNIEnv *env, jobject thiz, jbyteArray jMasterKey, jlong sectorNum, jbyteArray jEncSector) {
    jsize keyLen = env->GetArrayLength(jMasterKey);
    jbyte* masterKey = env->GetByteArrayElements(jMasterKey, NULL);

    jsize sectorLen = env->GetArrayLength(jEncSector);
    jbyte* encSector = env->GetByteArrayElements(jEncSector, NULL);

    mbedtls_aes_xts_context xts_ctx;
    mbedtls_aes_xts_init(&xts_ctx);
    mbedtls_aes_xts_setkey_dec(&xts_ctx, (const unsigned char*)masterKey, keyLen * 8);

    unsigned char decSector[512];
    unsigned char data_unit[16] = {0};
    
    // Copy little-endian sector number into data_unit
    for (int i = 0; i < 8; i++) {
        data_unit[i] = (sectorNum >> (i * 8)) & 0xFF;
    }

    int ret = mbedtls_aes_crypt_xts(&xts_ctx, MBEDTLS_AES_DECRYPT, 512, data_unit, (const unsigned char*)encSector, decSector);

    mbedtls_aes_xts_free(&xts_ctx);
    env->ReleaseByteArrayElements(jMasterKey, masterKey, JNI_ABORT);
    env->ReleaseByteArrayElements(jEncSector, encSector, JNI_ABORT);

    if (ret != 0) return nullptr;

    jbyteArray result = env->NewByteArray(512);
    env->SetByteArrayRegion(result, 0, 512, (const jbyte*)decSector);
    return result;
}
