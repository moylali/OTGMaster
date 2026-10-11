#include <jni.h>
#include <android/log.h>
#include "mbedtls/md.h"
#include "mbedtls/pkcs5.h"
#include "mbedtls/platform_util.h"
#include "argon2.h"
#include "vc_cascade.h"

#define TAG "VeraCryptNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Must stay in sync with VeraCryptKdf in VeraCryptCipher.kt.
#define KDF_PBKDF2_SHA512 0
#define KDF_ARGON2ID      1

// VeraCrypt's header layout: a 64-byte salt, then 448 encrypted bytes (Volumes.h).
#define HEADER_ENCRYPTED_SIZE 448
// Argon2id's output length is part of what it hashes, so a shorter request is a
// different key, not a prefix of this one. VeraCrypt always derives exactly this many
// bytes (ARGON2_HEADER_KEYDATA_SIZE in Crypto.h) and slices what the cascade needs,
// which is at most 192 for three ciphers.
#define ARGON2_HEADER_KEYDATA_SIZE 192

/*
 * Copies the Java cipher-id array into ids[]. Returns the count, or 0 if it is not
 * 1..VC_MAX_CASCADE ids long.
 */
static int readCipherIds(JNIEnv* env, jintArray jCiphers, int ids[VC_MAX_CASCADE]) {
    jsize n = env->GetArrayLength(jCiphers);
    if (n < 1 || n > VC_MAX_CASCADE) return 0;
    jint tmp[VC_MAX_CASCADE];
    env->GetIntArrayRegion(jCiphers, 0, n, tmp);
    for (int i = 0; i < n; i++) ids[i] = tmp[i];
    return n;
}

/*
 * Crypts a run of consecutive 512-byte sectors in place, setting the key schedules
 * up once for the whole run.
 *
 * Per-sector setup expanded every schedule for every 512 bytes, which is 32 AES
 * blocks of payload: the expansion cost on the order of the encryption itself, and
 * the master key is fixed for the life of the mount. A 64 KiB read rebuilt it 128
 * times. Working in place on the caller's array also removes an allocation and an
 * arraycopy per sector, and collapses 128 JNI crossings into one. The block layer is
 * demonstrably CPU-bound (17.26 MB/s awake against 7.27 MB/s at reduced clocks), so
 * this is on the critical path.
 *
 * ciphers: the cascade in VeraCrypt table order (see vc_cascade.h); one id for a
 * single cipher, which is also how LUKS calls it. direction: 0 = decrypt,
 * 1 = encrypt. Returns 0 on success; the buffer is left untouched on failure.
 */
extern "C" JNIEXPORT jint JNICALL
Java_app_fayaz_otgmaster_veracrypt_VeraCryptNative_cryptSectorsInPlace(
        JNIEnv *env, jobject thiz, jintArray jCiphers, jint direction, jbyteArray jMasterKey,
        jlong startSector, jbyteArray jBuffer, jint offset, jint length) {

    int ids[VC_MAX_CASCADE];
    const int n = readCipherIds(env, jCiphers, ids);
    if (n == 0) {
        LOGE("cryptSectorsInPlace: cascade of %d ciphers", (int) env->GetArrayLength(jCiphers));
        return -1;
    }
    if (length <= 0 || (length % 512) != 0) {
        LOGE("cryptSectorsInPlace: length %d is not a positive multiple of 512", length);
        return -1;
    }
    if (offset < 0 || offset + length > env->GetArrayLength(jBuffer)) {
        LOGE("cryptSectorsInPlace: range [%d,%d) outside buffer", offset, offset + length);
        return -1;
    }

    jsize keyLen = env->GetArrayLength(jMasterKey);
    jbyte* masterKey = env->GetByteArrayElements(jMasterKey, NULL);
    if (!masterKey) return -1;
    VcCascade* cascade = vc_cascade_new(ids, n, (const uint8_t*) masterKey, (size_t) keyLen);
    env->ReleaseByteArrayElements(jMasterKey, masterKey, JNI_ABORT);
    if (!cascade) {
        LOGE("cryptSectorsInPlace: key setup failed (%d ciphers, %d key bytes)", n, (int) keyLen);
        return -1;
    }

    jbyte* buffer = env->GetByteArrayElements(jBuffer, NULL);
    if (!buffer) {
        vc_cascade_free(cascade);
        return -1;
    }
    uint8_t* data = (uint8_t*) (buffer + offset);
    const int sectors = length / 512;
    for (int i = 0; i < sectors; i++) {
        uint8_t* p = data + (size_t) i * 512;
        uint64_t unit = (uint64_t) startSector + (uint64_t) i;
        if (direction == 1) vc_cascade_encrypt_unit(cascade, unit, p, 512);
        else vc_cascade_decrypt_unit(cascade, unit, p, 512);
    }
    vc_cascade_free(cascade);
    // 0 commits the (in-place) result back to the Java array.
    env->ReleaseByteArrayElements(jBuffer, buffer, 0);
    return 0;
}

/*
 * Derives the header key from the password and the header's 64-byte salt with the
 * chosen KDF, and decrypts the header's 448 encrypted bytes with it under the given
 * cascade. Returns those 448 bytes, or null if derivation or setup failed. Whether
 * they decrypted correctly ("VERA" at offset 0) is for the caller to check.
 *
 * iterations is PBKDF2's count, or Argon2id's time cost; memoryKiB is Argon2id's
 * memory cost and is ignored for PBKDF2. An Argon2id failure is thrown as an
 * IllegalStateException carrying Argon2's own message, because the common one —
 * the allocation of hundreds of MiB failing — needs telling apart from a wrong
 * password.
 */
static jbyteArray cryptHeader(
        JNIEnv *env, jintArray jCiphers, jint kdf, jbyteArray jPassword,
        jbyteArray jSalt, jint iterations, jint memoryKiB, jbyteArray jEncHeader, bool encrypt) {

    int ids[VC_MAX_CASCADE];
    const int n = readCipherIds(env, jCiphers, ids);
    if (n == 0 || env->GetArrayLength(jEncHeader) < HEADER_ENCRYPTED_SIZE) return nullptr;
    const int keyBytes = 64 * n;

    jsize pwdLen = env->GetArrayLength(jPassword);
    jbyte* pwd = env->GetByteArrayElements(jPassword, NULL);
    jsize saltLen = env->GetArrayLength(jSalt);
    jbyte* salt = env->GetByteArrayElements(jSalt, NULL);

    uint8_t headerKey[ARGON2_HEADER_KEYDATA_SIZE];
    int ret;
    const char* argon2Error = nullptr;
    if (kdf == KDF_ARGON2ID) {
        // Parallelism 1, version 0x13: what derive_key_argon2 in VeraCrypt's Pkcs5.c passes.
        ret = argon2id_hash_raw((uint32_t) iterations, (uint32_t) memoryKiB, 1,
                                pwd, (size_t) pwdLen, salt, (size_t) saltLen,
                                headerKey, ARGON2_HEADER_KEYDATA_SIZE);
        if (ret != ARGON2_OK) argon2Error = argon2_error_message(ret);
    } else if (kdf == KDF_PBKDF2_SHA512) {
        // PBKDF2 output is prefix-stable, so deriving only what the cascade needs is
        // the same key VeraCrypt gets from its longer derivation.
        mbedtls_md_context_t md;
        mbedtls_md_init(&md);
        ret = mbedtls_md_setup(&md, mbedtls_md_info_from_type(MBEDTLS_MD_SHA512), 1);
        if (ret == 0) {
            ret = mbedtls_pkcs5_pbkdf2_hmac(&md, (const unsigned char*) pwd, pwdLen,
                                            (const unsigned char*) salt, saltLen,
                                            (unsigned int) iterations, keyBytes, headerKey);
        }
        mbedtls_md_free(&md);
    } else {
        LOGE("cryptHeader: unknown KDF %d", kdf);
        ret = -1;
    }

    // Wipe the native copy of the password before releasing it. JNI_ABORT rather
    // than 0: mode 0 would copy our zeroes back into the caller's array, destroying
    // a password the caller may still need.
    mbedtls_platform_zeroize(pwd, pwdLen);
    env->ReleaseByteArrayElements(jPassword, pwd, JNI_ABORT);
    env->ReleaseByteArrayElements(jSalt, salt, JNI_ABORT);

    if (ret != 0) {
        mbedtls_platform_zeroize(headerKey, sizeof(headerKey));
        if (argon2Error) {
            LOGE("Argon2id failed: %s", argon2Error);
            env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), argon2Error);
        } else {
            LOGE("Header key derivation failed: %d", ret);
        }
        return nullptr;
    }

    VcCascade* cascade = vc_cascade_new(ids, n, headerKey, (size_t) keyBytes);
    mbedtls_platform_zeroize(headerKey, sizeof(headerKey));
    if (!cascade) {
        LOGE("cryptHeader: key setup failed for a %d-cipher cascade", n);
        return nullptr;
    }

    uint8_t decHeader[HEADER_ENCRYPTED_SIZE];
    env->GetByteArrayRegion(jEncHeader, 0, HEADER_ENCRYPTED_SIZE, (jbyte*) decHeader);
    // The header is one data unit, number 0.
    if (encrypt) vc_cascade_encrypt_unit(cascade, 0, decHeader, HEADER_ENCRYPTED_SIZE);
    else vc_cascade_decrypt_unit(cascade, 0, decHeader, HEADER_ENCRYPTED_SIZE);
    vc_cascade_free(cascade);

    jbyteArray jDecHeader = env->NewByteArray(HEADER_ENCRYPTED_SIZE);
    if (jDecHeader) env->SetByteArrayRegion(jDecHeader, 0, HEADER_ENCRYPTED_SIZE, (jbyte*) decHeader);
    // decHeader holds the master key. Leaving it on the stack for whatever runs next
    // undoes the care taken to zero the key on the Kotlin side.
    mbedtls_platform_zeroize(decHeader, sizeof(decHeader));
    return jDecHeader;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_fayaz_otgmaster_veracrypt_VeraCryptNative_decryptHeader(
        JNIEnv *env, jobject thiz, jintArray jCiphers, jint kdf, jbyteArray jPassword,
        jbyteArray jSalt, jint iterations, jint memoryKiB, jbyteArray jEncHeader) {
    return cryptHeader(env, jCiphers, kdf, jPassword, jSalt, iterations, memoryKiB, jEncHeader, false);
}

/*
 * The inverse, for creating a volume: the same key derivation, then the plaintext
 * header's 448 bytes (from offset 64) encrypted as data unit 0. The caller writes the
 * salt before them.
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_fayaz_otgmaster_veracrypt_VeraCryptNative_encryptHeader(
        JNIEnv *env, jobject thiz, jintArray jCiphers, jint kdf, jbyteArray jPassword,
        jbyteArray jSalt, jint iterations, jint memoryKiB, jbyteArray jPlainHeader) {
    return cryptHeader(env, jCiphers, kdf, jPassword, jSalt, iterations, memoryKiB, jPlainHeader, true);
}
