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
    
    int rc = exfat_mount(ef, spec, "ro");
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
    }
    
    exfat_closedir(ef, &it);
    
    jclass nodeClass = env->FindClass("app/fayaz/otgmaster/exfat/ExFatNode");
    jobjectArray array = env->NewObjectArray(count, nodeClass, NULL);
    
    exfat_opendir(ef, dir, &it);
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
