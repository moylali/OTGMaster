#include <jni.h>
#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

extern "C" {
#include "exfat.h"
#include "otg_io_stats.h"
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
Java_app_fayaz_otgmaster_exfat_ExFatNative_mount(JNIEnv *env, jobject thiz, jobject blockDevice, jboolean readOnly) {
    jobject globalBlockDevice = env->NewGlobalRef(blockDevice);
    
    struct exfat* ef = (struct exfat*) malloc(sizeof(struct exfat));
    if (!ef) {
        env->DeleteGlobalRef(globalBlockDevice);
        return 0;
    }
    memset(ef, 0, sizeof(struct exfat));
    
    char spec[64];
    snprintf(spec, sizeof(spec), "%lld", (long long) globalBlockDevice);
    
    int rc = exfat_mount(ef, spec, readOnly ? "ro" : "");
    if (rc != 0) {
        LOGE("exfat_mount failed with %d", rc);
        if (rc == -ENODEV) {
            env->DeleteGlobalRef(globalBlockDevice);
        }
        free(ef);
        return 0;
    }
    
    LOGI("exfat mounted successfully%s", readOnly ? " (read-only)" : "");
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

extern "C" JNIEXPORT jstring JNICALL
Java_app_fayaz_otgmaster_exfat_ExFatNative_getLabel(JNIEnv *env, jclass clazz, jlong exfatPtr) {
    struct exfat* ef = (struct exfat*) exfatPtr;
    if (!ef) return env->NewStringUTF("");
    /* libexfat keeps the volume label entry's name, already converted to UTF-8. */
    jstring label = env->NewStringUTF(exfat_get_label(ef));
    if (!label) { env->ExceptionClear(); return env->NewStringUTF(""); }
    return label;
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
        // Returning `array` here handed the caller `count` NULL elements against a
        // non-null Kotlin type. NULL means "failed" and an empty directory is a
        // zero-length array, so the two are already distinguishable — say failed.
        LOGE("exfat_opendir (fill pass) failed");
        return NULL;
    }
    int i = 0;
    while ((child = exfat_readdir(&it)) != NULL) {
        if (i >= count) {
            // More entries than the counting pass saw. Better to fail than to
            // silently drop the tail of a directory listing.
            LOGE("readDir: directory grew between passes (%d > %d)", i + 1, count);
            exfat_closedir(ef, &it);
            return NULL;
        }
        jobject jChild = createExFatNode(env, child);
        env->SetObjectArrayElement(array, i++, jChild);
        env->DeleteLocalRef(jChild);
    }
    exfat_closedir(ef, &it);
    
    if (i != count) {
        // Fewer than counted: the tail would be NULL elements. Same reasoning.
        LOGE("readDir: filled %d of %d entries", i, count);
        return NULL;
    }
    return array;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_fayaz_otgmaster_exfat_ExFatNative_readFile(JNIEnv *env, jclass clazz, jlong exfatPtr, jlong nodePtr, jlong offset, jint size, jbyteArray buffer, jint bufferOffset) {
    struct exfat* ef = (struct exfat*) exfatPtr;
    struct exfat_node* node = (struct exfat_node*) nodePtr;
    
    if (!ef || !node || (node->attrib & EXFAT_ATTRIB_DIR)) return -1;
    
    // Read straight into the Java array.
    //
    // This used to malloc(size), read into it, SetByteArrayRegion it across, and
    // free it — and the Kotlin caller then copied the result into the destination
    // ByteBuffer, for three copies and two allocations per read. libexfat issues
    // roughly 100,000 preads for a single large directory listing, so that churn is
    // not incidental.
    if (bufferOffset < 0 || size < 0 ||
            bufferOffset + size > env->GetArrayLength(buffer)) {
        return -1;
    }
    jbyte* elements = env->GetByteArrayElements(buffer, NULL);
    if (!elements) return -1;

    ssize_t bytesRead = exfat_generic_pread(
            ef, node, (char*) (elements + bufferOffset), size, offset);

    // 0 commits the data back; JNI_ABORT on failure avoids paying for a copy-back
    // when the array holds nothing useful.
    env->ReleaseByteArrayElements(buffer, elements, bytesRead > 0 ? 0 : JNI_ABORT);

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

    int rc = (node->attrib & EXFAT_ATTRIB_DIR) ? exfat_rmdir(ef, node) : exfat_unlink(ef, node);
    if (rc != 0) return rc;

    // Free the clusters now. libexfat frees them in exfat_cleanup_node, after the
    // last reference is put — and every Kotlin ExFatFile holds one, released on
    // close() or, for the handles a listing hands out, whenever the GC finalizes
    // them. Until then the space stays allocated, and an unmount first loses it
    // for good: the node is already detached from the tree, so exfat_unmount
    // never visits it and the bitmap keeps the clusters marked used with no file
    // owning them (fsck.exfat 1.3.2 does not report that). On a full drive the
    // next write failed with ENOSPC right after a delete that had "succeeded".
    //
    // An unlinked node is never flushed (exfat_flush_node skips a node with no
    // parent), so clearing is_dirty only silences the zero-reference warning;
    // exfat_cleanup_node's own truncate then finds size 0 and just frees the node.
    int trc = exfat_truncate(ef, node, 0, true);
    if (trc != 0) {
        // The entry is gone either way; the final put retries the truncate.
        LOGE("deleteNode: freeing clusters failed with %d", trc);
    }
    node->is_dirty = false;
    return 0;
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

/* ---------------------------------------------------------------------------
 * Debug-only I/O instrumentation (OTG_IO_STATS, set from the debug build type).
 *
 * Counted in io.c, exported here: io.c is compiled into the `exfat` STATIC
 * library, where a JNI entry point is referenced by nothing at link time and the
 * linker drops it. This file is a direct source of libveracrypt-native.so, so
 * these exports survive. The Kotlin declarations live in src/debug
 * (ExFatIoStats), so a release build cannot reference symbols that were never
 * compiled.
 * ------------------------------------------------------------------------- */
#ifdef OTG_IO_STATS
extern "C" JNIEXPORT void JNICALL
Java_app_fayaz_otgmaster_exfat_ExFatIoStats_reset(JNIEnv* env, jclass clazz) {
    (void) env; (void) clazz;
    otg_io_reset();
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_app_fayaz_otgmaster_exfat_ExFatIoStats_snapshot(JNIEnv* env, jclass clazz) {
    (void) clazz;
    jlong out[4 + OTG_IO_BUCKETS];
    out[0] = (jlong) otg_pread_calls;
    out[1] = (jlong) otg_pread_bytes;
    out[2] = (jlong) otg_pwrite_calls;
    out[3] = (jlong) otg_pwrite_bytes;
    for (int i = 0; i < OTG_IO_BUCKETS; i++) out[4 + i] = (jlong) otg_size_hist[i];
    jlongArray arr = env->NewLongArray(4 + OTG_IO_BUCKETS);
    if (arr != nullptr) env->SetLongArrayRegion(arr, 0, 4 + OTG_IO_BUCKETS, out);
    return arr;
}
#endif /* OTG_IO_STATS */
