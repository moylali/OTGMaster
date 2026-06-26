#include <jni.h>
#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

extern "C" {
#include "exfat.h"
}

#define LOG_TAG "exfat-jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Helper to create ExFatNode
static jobject createExFatNode(JNIEnv* env, struct exfat_node* node) {
    if (!node) return NULL;
    
    jclass clazz = env->FindClass("app/fayaz/otgmaster/exfat/ExFatNode");
    jmethodID constructor = env->GetMethodID(clazz, "<init>", "(JLjava/lang/String;ZJJJ)V");
    
    char name_utf8[EXFAT_UTF8_NAME_BUFFER_MAX];
    exfat_get_name(node, name_utf8);
    jstring jName = env->NewStringUTF(name_utf8);
    
    jboolean isDir = (node->attrib & EXFAT_ATTRIB_DIR) ? JNI_TRUE : JNI_FALSE;
    jlong size = node->size;
    
    jlong mtime = (jlong) node->mtime * 1000;
    jlong atime = (jlong) node->atime * 1000;
    
    jobject jNode = env->NewObject(clazz, constructor, (jlong) node, jName, isDir, size, atime, mtime);
    env->DeleteLocalRef(jName);
    return jNode;
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_fayaz_otgmaster_exfat_ExFatNative_mount(JNIEnv *env, jobject thiz, jobject blockDevice) {
    jobject globalBlockDevice = env->NewGlobalRef(blockDevice);
    
    struct exfat* ef = (struct exfat*) malloc(sizeof(struct exfat));
    if (!ef) {
        env->DeleteGlobalRef(globalBlockDevice);
        return 0;
    }
    memset(ef, 0, sizeof(struct exfat));
    
    char spec[64];
    snprintf(spec, sizeof(spec), "%lld", (long long) globalBlockDevice);
    
    int rc = exfat_mount(ef, spec, "");
    if (rc != 0) {
        LOGE("exfat_mount failed with %d", rc);
        if (rc == -ENODEV) {
            env->DeleteGlobalRef(globalBlockDevice);
        }
        free(ef);
        return 0;
    }
    
    LOGI("exfat mounted successfully");
    return (jlong) ef;
}

extern "C" JNIEXPORT void JNICALL
Java_app_fayaz_otgmaster_exfat_ExFatNative_unmount(JNIEnv *env, jobject thiz, jlong exfatPtr) {
    struct exfat* ef = (struct exfat*) exfatPtr;
    if (ef) {
        exfat_unmount(ef);
        free(ef);
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_fayaz_otgmaster_exfat_ExFatNative_getFreeSpace(JNIEnv *env, jclass clazz, jlong exfatPtr) {
    struct exfat* ef = (struct exfat*) exfatPtr;
    if (!ef || !ef->sb) return 0;
    
    uint32_t freeClusters = exfat_count_free_clusters(ef);
    jlong clusterSize = CLUSTER_SIZE(*(ef->sb));
    return (jlong)freeClusters * clusterSize;
}

extern "C" JNIEXPORT jobject JNICALL
Java_app_fayaz_otgmaster_exfat_ExFatNative_getRootNode(JNIEnv *env, jclass clazz, jlong exfatPtr) {
    struct exfat* ef = (struct exfat*) exfatPtr;
    if (!ef || !ef->root) return NULL;
    
    return createExFatNode(env, ef->root);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_app_fayaz_otgmaster_exfat_ExFatNative_readDir(JNIEnv *env, jclass clazz, jlong exfatPtr, jlong nodePtr) {
    struct exfat* ef = (struct exfat*) exfatPtr;
    struct exfat_node* dir = (struct exfat_node*) nodePtr;
    
    if (!ef || !dir || !(dir->attrib & EXFAT_ATTRIB_DIR)) return NULL;
    
    struct exfat_iterator it;
    if (exfat_opendir(ef, dir, &it) != 0) {
        LOGE("exfat_opendir failed");
        return NULL;
    }
    
    struct exfat_node* child;
    int count = 0;
    while ((child = exfat_readdir(&it)) != NULL) {
        count++;
        exfat_put_node(ef, child);  /* balance exfat_readdir's get_node; node stays in cache */
    }
    
    exfat_closedir(ef, &it);
    
    jclass nodeClass = env->FindClass("app/fayaz/otgmaster/exfat/ExFatNode");
    jobjectArray array = env->NewObjectArray(count, nodeClass, NULL);
    
    if (exfat_opendir(ef, dir, &it) != 0) {
        LOGE("exfat_opendir (fill pass) failed");
        return array;
    }
    int i = 0;
    while ((child = exfat_readdir(&it)) != NULL) {
        jobject jChild = createExFatNode(env, child);
        env->SetObjectArrayElement(array, i++, jChild);
        env->DeleteLocalRef(jChild);
    }
    exfat_closedir(ef, &it);
    
    return array;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_fayaz_otgmaster_exfat_ExFatNative_readFile(JNIEnv *env, jclass clazz, jlong exfatPtr, jlong nodePtr, jlong offset, jint size, jbyteArray buffer) {
    struct exfat* ef = (struct exfat*) exfatPtr;
    struct exfat_node* node = (struct exfat_node*) nodePtr;
    
    if (!ef || !node || (node->attrib & EXFAT_ATTRIB_DIR)) return -1;
    
    char* localBuffer = (char*) malloc(size);
    if (!localBuffer) return -1;
    
    ssize_t bytesRead = exfat_generic_pread(ef, node, localBuffer, size, offset);
    if (bytesRead > 0) {
        env->SetByteArrayRegion(buffer, 0, bytesRead, (jbyte*) localBuffer);
    }
    free(localBuffer);
    
    return (jint) bytesRead;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_fayaz_otgmaster_exfat_ExFatNative_writeFile(JNIEnv *env, jclass clazz, jlong exfatPtr, jlong nodePtr, jlong offset, jint size, jbyteArray buffer) {
    struct exfat* ef = (struct exfat*) exfatPtr;
    struct exfat_node* node = (struct exfat_node*) nodePtr;
    
    if (!ef || !node || (node->attrib & EXFAT_ATTRIB_DIR)) return -1;
    
    jbyte* localBuffer = env->GetByteArrayElements(buffer, NULL);
    if (!localBuffer) return -1;
    
    ssize_t bytesWritten = exfat_generic_pwrite(ef, node, localBuffer, size, offset);
    
    env->ReleaseByteArrayElements(buffer, localBuffer, JNI_ABORT);
    
    return (jint) bytesWritten;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_fayaz_otgmaster_exfat_ExFatNative_setLength(JNIEnv *env, jclass clazz, jlong exfatPtr, jlong nodePtr, jlong length) {
    struct exfat* ef = (struct exfat*) exfatPtr;
    struct exfat_node* node = (struct exfat_node*) nodePtr;
    
    if (!ef || !node) return -1;
    
    return exfat_truncate(ef, node, length, false);
}

extern "C" JNIEXPORT jint JNICALL
Java_app_fayaz_otgmaster_exfat_ExFatNative_createFile(JNIEnv *env, jclass clazz, jlong exfatPtr, jstring path) {
    struct exfat* ef = (struct exfat*) exfatPtr;
    if (!ef) return -1;
    
    const char* nativePath = env->GetStringUTFChars(path, 0);
    int rc = exfat_mknod(ef, nativePath);
    env->ReleaseStringUTFChars(path, nativePath);
    
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_fayaz_otgmaster_exfat_ExFatNative_createDirectory(JNIEnv *env, jclass clazz, jlong exfatPtr, jstring path) {
    struct exfat* ef = (struct exfat*) exfatPtr;
    if (!ef) return -1;
    
    const char* nativePath = env->GetStringUTFChars(path, 0);
    int rc = exfat_mkdir(ef, nativePath);
    env->ReleaseStringUTFChars(path, nativePath);
    
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_fayaz_otgmaster_exfat_ExFatNative_deleteNode(JNIEnv *env, jclass clazz, jlong exfatPtr, jlong nodePtr) {
    struct exfat* ef = (struct exfat*) exfatPtr;
    struct exfat_node* node = (struct exfat_node*) nodePtr;
    
    if (!ef || !node) return -1;
    
    if (node->attrib & EXFAT_ATTRIB_DIR) {
        return exfat_rmdir(ef, node);
    } else {
        return exfat_unlink(ef, node);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_app_fayaz_otgmaster_exfat_ExFatNative_rename(JNIEnv *env, jclass clazz, jlong exfatPtr, jstring oldPath, jstring newPath) {
    struct exfat* ef = (struct exfat*) exfatPtr;
    if (!ef) return -1;
    
    const char* nativeOldPath = env->GetStringUTFChars(oldPath, 0);
    const char* nativeNewPath = env->GetStringUTFChars(newPath, 0);
    
    int rc = exfat_rename(ef, nativeOldPath, nativeNewPath);
    
    env->ReleaseStringUTFChars(oldPath, nativeOldPath);
    env->ReleaseStringUTFChars(newPath, nativeNewPath);
    
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_fayaz_otgmaster_exfat_ExFatNative_flush(JNIEnv *env, jclass clazz, jlong exfatPtr) {
    struct exfat* ef = (struct exfat*) exfatPtr;
    if (!ef) return -1;
    
    exfat_flush_nodes(ef);
    exfat_flush(ef);  /* write cluster allocation bitmap */
    return exfat_fsync(ef->dev);
}

extern "C" JNIEXPORT void JNICALL
Java_app_fayaz_otgmaster_exfat_ExFatNative_putNode(JNIEnv *env, jclass clazz, jlong exfatPtr, jlong nodePtr) {
    struct exfat *ef = (struct exfat *) exfatPtr;
    struct exfat_node *node = (struct exfat_node *) nodePtr;
    if (ef && node) {
        exfat_put_node(ef, node);
        if (node->references == 0 && node->is_unlinked) {
            exfat_cleanup_node(ef, node);
        }
    }
}
