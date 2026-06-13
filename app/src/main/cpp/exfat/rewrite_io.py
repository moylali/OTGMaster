import sys

with open('/tmp/io_tail.c', 'r') as f:
    tail = f.read()

jni_funcs = """
#include "exfat.h"
#include <inttypes.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <errno.h>

struct exfat_dev {
    void* jni_env;
    void* block_device;
    enum exfat_mode mode;
    off_t size; /* in bytes */
};

#include <jni.h>
#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>

#define LOG_TAG "exfat-jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM* g_jvm = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

static JNIEnv* getEnv() {
    JNIEnv* env = NULL;
    if ((*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL);
    }
    return env;
}

struct exfat_dev* exfat_open(const char* spec, enum exfat_mode mode) {
    struct exfat_dev* dev = (struct exfat_dev*) malloc(sizeof(struct exfat_dev));
    if (!dev) return NULL;
    dev->mode = mode;
    dev->size = 0;
    
    long long ptr;
    if (sscanf(spec, "%lld", &ptr) == 1) {
        dev->block_device = (void*) ptr;
    } else {
        free(dev);
        return NULL;
    }
    
    JNIEnv* env = getEnv();
    jclass clazz = (*env)->FindClass(env, "com/otgmaster/exfat/ExFatNative");
    jmethodID getDeviceSize = (*env)->GetStaticMethodID(env, clazz, "getDeviceSize", "(Lcom/otgmaster/block/RawBlockDevice;)J");
    dev->size = (*env)->CallStaticLongMethod(env, clazz, getDeviceSize, (jobject)dev->block_device);
    
    return dev;
}

int exfat_close(struct exfat_dev* dev) {
    JNIEnv* env = getEnv();
    if (dev->block_device) {
        (*env)->DeleteGlobalRef(env, (jobject)dev->block_device);
    }
    free(dev);
    return 0;
}

int exfat_fsync(struct exfat_dev* dev) {
    return 0;
}

enum exfat_mode exfat_get_mode(const struct exfat_dev* dev) {
    return dev->mode;
}

off_t exfat_get_size(const struct exfat_dev* dev) {
    return dev->size;
}

off_t exfat_seek(struct exfat_dev* dev, off_t offset, int whence) {
    return -1;
}

ssize_t exfat_read(struct exfat_dev* dev, void* buffer, size_t size) {
    return -1;
}

ssize_t exfat_write(struct exfat_dev* dev, const void* buffer, size_t size) {
    return -1;
}

ssize_t exfat_pread(struct exfat_dev* dev, void* buffer, size_t size, off_t offset) {
    JNIEnv* env = getEnv();
    jclass clazz = (*env)->FindClass(env, "com/otgmaster/exfat/ExFatNative");
    jmethodID preadMethod = (*env)->GetStaticMethodID(env, clazz, "pread", "(Lcom/otgmaster/block/RawBlockDevice;JI[B)I");
    
    jbyteArray jBuffer = (*env)->NewByteArray(env, size);
    jint result = (*env)->CallStaticIntMethod(env, clazz, preadMethod, (jobject)dev->block_device, (jlong)offset, (jint)size, jBuffer);
    
    if (result > 0) {
        (*env)->GetByteArrayRegion(env, jBuffer, 0, result, (jbyte*)buffer);
    }
    (*env)->DeleteLocalRef(env, jBuffer);
    return result;
}

ssize_t exfat_pwrite(struct exfat_dev* dev, const void* buffer, size_t size, off_t offset) {
    JNIEnv* env = getEnv();
    jclass clazz = (*env)->FindClass(env, "com/otgmaster/exfat/ExFatNative");
    jmethodID pwriteMethod = (*env)->GetStaticMethodID(env, clazz, "pwrite", "(Lcom/otgmaster/block/RawBlockDevice;JI[B)I");
    
    jbyteArray jBuffer = (*env)->NewByteArray(env, size);
    (*env)->SetByteArrayRegion(env, jBuffer, 0, size, (const jbyte*)buffer);
    
    jint result = (*env)->CallStaticIntMethod(env, clazz, pwriteMethod, (jobject)dev->block_device, (jlong)offset, (jint)size, jBuffer);
    
    (*env)->DeleteLocalRef(env, jBuffer);
    return result;
}

"""

with open('/Users/cfayaz/personalws/OTGMaster/app/src/main/cpp/exfat/io.c', 'w') as f:
    f.write(jni_funcs)
    f.write(tail)
