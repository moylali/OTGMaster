/*
 * JNI surface of bitlocker_crypto.c. The volume format (FVE metadata, VMKs, the
 * relocated boot sectors) is parsed in Kotlin; only the cryptography is here.
 */
#include <jni.h>
#include <string.h>

extern "C" {
#include "bitlocker_crypto.h"
#include "mbedtls/platform_util.h"
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_fayaz_otgmaster_bitlocker_BitLockerNative_stretchKey(JNIEnv *env, jclass,
        jbyteArray jInitial, jbyteArray jSalt) {
    if (env->GetArrayLength(jInitial) != 32 || env->GetArrayLength(jSalt) != 16) return nullptr;
    uint8_t initial[32], salt[16], out[32];
    env->GetByteArrayRegion(jInitial, 0, 32, (jbyte *) initial);
    env->GetByteArrayRegion(jSalt, 0, 16, (jbyte *) salt);
    bitlk_stretch_key(initial, salt, out);
    jbyteArray result = env->NewByteArray(32);
    if (result) env->SetByteArrayRegion(result, 0, 32, (const jbyte *) out);
    mbedtls_platform_zeroize(initial, sizeof(initial));
    mbedtls_platform_zeroize(out, sizeof(out));
    return result;
}

/* Returns the plaintext, or null if the tag does not verify (wrong key). */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_fayaz_otgmaster_bitlocker_BitLockerNative_ccmDecrypt(JNIEnv *env, jclass,
        jbyteArray jKey, jbyteArray jNonce, jbyteArray jTag, jbyteArray jData) {
    jsize keyLen = env->GetArrayLength(jKey), len = env->GetArrayLength(jData);
    if (env->GetArrayLength(jNonce) != 12 || env->GetArrayLength(jTag) != 16 ||
            (keyLen != 16 && keyLen != 32)) return nullptr;
    jbyte *key = env->GetByteArrayElements(jKey, nullptr);
    jbyte *nonce = env->GetByteArrayElements(jNonce, nullptr);
    jbyte *tag = env->GetByteArrayElements(jTag, nullptr);
    jbyte *data = env->GetByteArrayElements(jData, nullptr);
    uint8_t *out = new uint8_t[len > 0 ? len : 1];
    int rc = bitlk_ccm_decrypt((uint8_t *) key, keyLen, (uint8_t *) nonce, (uint8_t *) tag,
                               (uint8_t *) data, len, out);
    jbyteArray result = nullptr;
    if (rc == 0) {
        result = env->NewByteArray(len);
        if (result) env->SetByteArrayRegion(result, 0, len, (const jbyte *) out);
    }
    mbedtls_platform_zeroize(out, len);
    delete[] out;
    env->ReleaseByteArrayElements(jKey, key, JNI_ABORT);
    env->ReleaseByteArrayElements(jNonce, nonce, JNI_ABORT);
    env->ReleaseByteArrayElements(jTag, tag, JNI_ABORT);
    env->ReleaseByteArrayElements(jData, data, JNI_ABORT);
    return result;
}

/* Returns ciphertext followed by the 16-byte tag. */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_fayaz_otgmaster_bitlocker_BitLockerNative_ccmEncrypt(JNIEnv *env, jclass,
        jbyteArray jKey, jbyteArray jNonce, jbyteArray jData) {
    jsize keyLen = env->GetArrayLength(jKey), len = env->GetArrayLength(jData);
    if (env->GetArrayLength(jNonce) != 12 || (keyLen != 16 && keyLen != 32)) return nullptr;
    jbyte *key = env->GetByteArrayElements(jKey, nullptr);
    jbyte *nonce = env->GetByteArrayElements(jNonce, nullptr);
    jbyte *data = env->GetByteArrayElements(jData, nullptr);
    uint8_t *out = new uint8_t[len + 16];
    int rc = bitlk_ccm_encrypt((uint8_t *) key, keyLen, (uint8_t *) nonce, (uint8_t *) data, len,
                               out, out + len);
    jbyteArray result = nullptr;
    if (rc == 0) {
        result = env->NewByteArray(len + 16);
        if (result) env->SetByteArrayRegion(result, 0, len + 16, (const jbyte *) out);
    }
    mbedtls_platform_zeroize(out, len + 16);
    delete[] out;
    env->ReleaseByteArrayElements(jKey, key, JNI_ABORT);
    env->ReleaseByteArrayElements(jNonce, nonce, JNI_ABORT);
    env->ReleaseByteArrayElements(jData, data, JNI_ABORT);
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_fayaz_otgmaster_bitlocker_BitLockerNative_newContext(JNIEnv *env, jclass,
        jint mode, jbyteArray jKey, jint sectorSize) {
    jsize keyLen = env->GetArrayLength(jKey);
    jbyte *key = env->GetByteArrayElements(jKey, nullptr);
    struct bitlk_ctx *ctx = bitlk_ctx_new(mode, (uint8_t *) key, keyLen, (uint32_t) sectorSize);
    env->ReleaseByteArrayElements(jKey, key, JNI_ABORT);
    return (jlong) ctx;
}

extern "C" JNIEXPORT void JNICALL
Java_app_fayaz_otgmaster_bitlocker_BitLockerNative_freeContext(JNIEnv *, jclass, jlong handle) {
    bitlk_ctx_free((struct bitlk_ctx *) handle);
}

extern "C" JNIEXPORT jint JNICALL
Java_app_fayaz_otgmaster_bitlocker_BitLockerNative_cryptSectors(JNIEnv *env, jclass, jlong handle,
        jboolean encrypt, jlong firstSector, jbyteArray jBuf, jint offset, jint length) {
    if (!handle || offset < 0 || length < 0 || offset + length > env->GetArrayLength(jBuf)) return -1;
    jbyte *buf = env->GetByteArrayElements(jBuf, nullptr);
    if (!buf) return -1;
    int rc = bitlk_crypt_sectors((const struct bitlk_ctx *) handle, encrypt ? 1 : 0,
                                 (uint64_t) firstSector, (uint8_t *) (buf + offset), (size_t) length);
    env->ReleaseByteArrayElements(jBuf, buf, rc == 0 ? 0 : JNI_ABORT);
    return rc;
}
