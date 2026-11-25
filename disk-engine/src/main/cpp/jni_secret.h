#pragma once
#include <jni.h>
#include <stdint.h>
#include "mbedtls/platform_util.h"

/*
 * A native copy of a secret Java byte array (a password or a key), wiped when it
 * goes out of scope.
 *
 * GetByteArrayElements was used before, released with JNI_ABORT. That call may
 * return a copy, which JNI_ABORT frees without wiping, so master keys stayed in
 * freed native memory (#165). Or it may return a pointer into the Java array
 * itself, which wiping would destroy for a caller that still needs it.
 * GetByteArrayRegion always copies into memory this object owns, so it can
 * always be wiped.
 */
class JniSecret {
public:
    JniSecret(JNIEnv* env, jbyteArray array)
        : n_(array ? env->GetArrayLength(array) : 0), p_(new uint8_t[n_ > 0 ? n_ : 1]) {
        if (n_ > 0) env->GetByteArrayRegion(array, 0, n_, reinterpret_cast<jbyte*>(p_));
    }
    ~JniSecret() {
        mbedtls_platform_zeroize(p_, (size_t) n_);
        delete[] p_;
    }
    JniSecret(const JniSecret&) = delete;
    JniSecret& operator=(const JniSecret&) = delete;

    uint8_t* data() const { return p_; }
    jsize size() const { return n_; }

private:
    jsize n_;
    uint8_t* p_;
};
