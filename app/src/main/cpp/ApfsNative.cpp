#include <jni.h>
#include <string>
#include <android/log.h>
#include <libfsapfs.h>
#include <libbfio.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ApfsNative", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ApfsNative", __VA_ARGS__)

// Future: Custom libbfio pool that proxies read requests back to Kotlin's BlockDevice
// ssize_t block_device_read(libbfio_handle_t *handle, uint8_t *buffer, size_t size, libcerror_error_t **error);

extern "C" JNIEXPORT jlong JNICALL
Java_app_fayaz_otgmaster_fs_apfs_ApfsNative_mount(JNIEnv *env, jclass clazz, jobject blockDevice, jstring password) {
    LOGI("Mounting APFS Volume via libfsapfs...");
    // 1. Setup custom libbfio handle targeting blockDevice
    // 2. Initialize libfsapfs_volume_t
    // 3. Open volume
    // 4. Return ptr
    return 0; // TODO
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_app_fayaz_otgmaster_fs_apfs_ApfsNative_listDirectory(JNIEnv *env, jclass clazz, jlong contextPtr, jstring path) {
    // 1. Cast contextPtr to libfsapfs_volume_t*
    // 2. Traverse file entries
    // 3. Return String[]
    return nullptr; // TODO
}

extern "C" JNIEXPORT void JNICALL
Java_app_fayaz_otgmaster_fs_apfs_ApfsNative_unmount(JNIEnv *env, jclass clazz, jlong contextPtr) {
    LOGI("Unmounting APFS Volume...");
    // libfsapfs_volume_free(...)
}
