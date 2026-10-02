
#include <jni.h>
#include <string>
#include <android/log.h>
#include <libfsapfs.h>
#include <libbfio.h>
#include <vector>
#include <cstring>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ApfsNative", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ApfsNative", __VA_ARGS__)

struct KotlinBlockDeviceContext {
    JavaVM *jvm;
    jobject blockDeviceRef;
    jmethodID readBlocksMethod;
    size_t blockSize;
    int64_t blockCount;
    int64_t currentOffset;
};

static int bfio_free_io_handle(intptr_t **io_handle, libcerror_error_t **error) {
    if (io_handle == nullptr || *io_handle == nullptr) return -1;
    KotlinBlockDeviceContext *ctx = reinterpret_cast<KotlinBlockDeviceContext*>(*io_handle);
    JNIEnv *env = nullptr;
    ctx->jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (env) {
        env->DeleteGlobalRef(ctx->blockDeviceRef);
    }
    delete ctx;
    *io_handle = nullptr;
    return 1;
}

static int bfio_clone_io_handle(intptr_t **destination_io_handle, intptr_t *source_io_handle, libcerror_error_t **error) { return -1; }
static int bfio_open(intptr_t *io_handle, int access_flags, libcerror_error_t **error) { return 1; }
static int bfio_close(intptr_t *io_handle, libcerror_error_t **error) { return 1; }

static off64_t bfio_seek_offset(intptr_t *io_handle, off64_t offset, int whence, libcerror_error_t **error) {
    KotlinBlockDeviceContext *ctx = reinterpret_cast<KotlinBlockDeviceContext*>(io_handle);
    if (!ctx) return -1;
    
    int64_t size = ctx->blockCount * ctx->blockSize;
    if (whence == SEEK_SET) ctx->currentOffset = offset;
    else if (whence == SEEK_CUR) ctx->currentOffset += offset;
    else if (whence == SEEK_END) ctx->currentOffset = size + offset;
    
    if (ctx->currentOffset < 0) ctx->currentOffset = 0;
    else if (ctx->currentOffset > size) ctx->currentOffset = size;
    return ctx->currentOffset;
}

static ssize_t bfio_read(intptr_t *io_handle, uint8_t *buffer, size_t size, libcerror_error_t **error) {
    KotlinBlockDeviceContext *ctx = reinterpret_cast<KotlinBlockDeviceContext*>(io_handle);
    if (!ctx) return -1;
    if (size == 0) return 0;
    
    JNIEnv *env = nullptr;
    bool attached = false;
    jint res = ctx->jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        ctx->jvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }

    int64_t startBlock = ctx->currentOffset / ctx->blockSize;
    int64_t endBlock = (ctx->currentOffset + size - 1) / ctx->blockSize;
    int32_t numBlocks = endBlock - startBlock + 1;

    jbyteArray jData = (jbyteArray)env->CallObjectMethod(ctx->blockDeviceRef, ctx->readBlocksMethod, (jlong)startBlock, (jint)numBlocks);
    if (env->ExceptionCheck() || jData == nullptr) {
        env->ExceptionClear();
        if (attached) ctx->jvm->DetachCurrentThread();
        return -1;
    }

    jbyte* dataElements = env->GetByteArrayElements(jData, nullptr);
    jsize dataLength = env->GetArrayLength(jData);

    size_t offsetInFirstBlock = ctx->currentOffset % ctx->blockSize;
    size_t copySize = size;
    if (offsetInFirstBlock + size > dataLength) {
        copySize = dataLength - offsetInFirstBlock;
    }

    memcpy(buffer, dataElements + offsetInFirstBlock, copySize);

    env->ReleaseByteArrayElements(jData, dataElements, JNI_ABORT);
    env->DeleteLocalRef(jData);
    
    if (attached) ctx->jvm->DetachCurrentThread();

    ctx->currentOffset += copySize;
    return copySize;
}

static ssize_t bfio_write(intptr_t *io_handle, const uint8_t *buffer, size_t size, libcerror_error_t **error) { return -1; }
static int bfio_exists(intptr_t *io_handle, libcerror_error_t **error) { return 1; }
static int bfio_is_open(intptr_t *io_handle, libcerror_error_t **error) { return 1; }
static int bfio_get_size(intptr_t *io_handle, size64_t *size, libcerror_error_t **error) {
    KotlinBlockDeviceContext *ctx = reinterpret_cast<KotlinBlockDeviceContext*>(io_handle);
    if (!ctx) return -1;
    *size = ctx->blockCount * ctx->blockSize;
    return 1;
}

struct ApfsNativeContext {
    libbfio_handle_t *bfio_handle;
    libfsapfs_container_t *container;
    libfsapfs_volume_t *volume;
};

extern "C" JNIEXPORT jlong JNICALL
Java_app_fayaz_otgmaster_fs_apfs_ApfsNative_mount(JNIEnv *env, jclass clazz, jobject blockDevice, jstring password) {
    LOGI("Mounting APFS Volume via libfsapfs...");
    
    jclass bdClass = env->GetObjectClass(blockDevice);
    jmethodID getBlockSize = env->GetMethodID(bdClass, "getBlockSize", "()I");
    jmethodID getBlockCount = env->GetMethodID(bdClass, "getBlockCount", "()J");
    jmethodID readBlocks = env->GetMethodID(bdClass, "readBlocks", "(JI)[B");
    
    KotlinBlockDeviceContext *ctx = new KotlinBlockDeviceContext();
    env->GetJavaVM(&ctx->jvm);
    ctx->blockDeviceRef = env->NewGlobalRef(blockDevice);
    ctx->readBlocksMethod = readBlocks;
    ctx->blockSize = env->CallIntMethod(blockDevice, getBlockSize);
    ctx->blockCount = env->CallLongMethod(blockDevice, getBlockCount);
    ctx->currentOffset = 0;
    
    libbfio_handle_t *bfio_handle = nullptr;
    if (libbfio_handle_initialize(&bfio_handle, reinterpret_cast<intptr_t*>(ctx),
        bfio_free_io_handle, bfio_clone_io_handle, bfio_open, bfio_close,
        bfio_read, bfio_write, bfio_seek_offset, bfio_exists, bfio_is_open, bfio_get_size,
        LIBBFIO_FLAG_OPEN_READ, nullptr) != 1) {
        LOGE("Failed to initialize libbfio handle");
        bfio_free_io_handle(reinterpret_cast<intptr_t**>(&ctx), nullptr);
        return 0;
    }

    libfsapfs_container_t *container = nullptr;
    if (libfsapfs_container_initialize(&container, nullptr) != 1 ||
        libfsapfs_container_open_file_io_handle(container, bfio_handle, LIBFSAPFS_OPEN_READ, nullptr) != 1) {
        LOGE("Failed to open container");
        if (container) libfsapfs_container_free(&container, nullptr);
        libbfio_handle_free(&bfio_handle, nullptr);
        return 0;
    }
    
    int num_volumes = 0;
    if (libfsapfs_container_get_number_of_volumes(container, &num_volumes, nullptr) != 1 || num_volumes == 0) {
        LOGE("No volumes found in container");
        libfsapfs_container_free(&container, nullptr);
        libbfio_handle_free(&bfio_handle, nullptr);
        return 0;
    }

    libfsapfs_volume_t *volume = nullptr;
    if (libfsapfs_container_get_volume_by_index(container, 0, &volume, nullptr) != 1) {
        LOGE("Failed to get volume");
        libfsapfs_container_free(&container, nullptr);
        libbfio_handle_free(&bfio_handle, nullptr);
        return 0;
    }
    
    if (libfsapfs_volume_is_locked(volume, nullptr) == 1 && password != nullptr) {
        const char *pwd = env->GetStringUTFChars(password, 0);
        int res = libfsapfs_volume_set_utf8_password(volume, reinterpret_cast<const uint8_t*>(pwd), strlen(pwd), nullptr);
        env->ReleaseStringUTFChars(password, pwd);
        if (res != 1) {
            LOGE("Password unlock failed");
            libfsapfs_volume_free(&volume, nullptr);
            libfsapfs_container_free(&container, nullptr);
            libbfio_handle_free(&bfio_handle, nullptr);
            return 0;
        }
    }
    
    ApfsNativeContext *nctx = new ApfsNativeContext();
    nctx->bfio_handle = bfio_handle;
    nctx->container = container;
    nctx->volume = volume;
    
    return reinterpret_cast<jlong>(nctx);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_app_fayaz_otgmaster_fs_apfs_ApfsNative_listDirectory(JNIEnv *env, jclass clazz, jlong contextPtr, jstring path) {
    ApfsNativeContext *nctx = reinterpret_cast<ApfsNativeContext*>(contextPtr);
    if (!nctx) return nullptr;
    
    const char *c_path = env->GetStringUTFChars(path, 0);
    libfsapfs_file_entry_t *dir_entry = nullptr;
    if (libfsapfs_volume_get_file_entry_by_utf8_path(nctx->volume, reinterpret_cast<const uint8_t*>(c_path), strlen(c_path), &dir_entry, nullptr) != 1) {
        env->ReleaseStringUTFChars(path, c_path);
        return nullptr;
    }
    env->ReleaseStringUTFChars(path, c_path);
    
    int num_entries = 0;
    if (libfsapfs_file_entry_get_number_of_sub_file_entries(dir_entry, &num_entries, nullptr) != 1) {
        libfsapfs_file_entry_free(&dir_entry, nullptr);
        return nullptr;
    }
    
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(num_entries, stringClass, nullptr);
    
    for (int i = 0; i < num_entries; i++) {
        libfsapfs_file_entry_t *sub_entry = nullptr;
        if (libfsapfs_file_entry_get_sub_file_entry_by_index(dir_entry, i, &sub_entry, nullptr) == 1) {
            size_t name_size = 0;
            if (libfsapfs_file_entry_get_utf8_name_size(sub_entry, &name_size, nullptr) == 1) {
                std::vector<uint8_t> name_buf(name_size);
                if (libfsapfs_file_entry_get_utf8_name(sub_entry, name_buf.data(), name_size, nullptr) == 1) {
                    jstring jname = env->NewStringUTF(reinterpret_cast<const char*>(name_buf.data()));
                    env->SetObjectArrayElement(result, i, jname);
                    env->DeleteLocalRef(jname);
                }
            }
            libfsapfs_file_entry_free(&sub_entry, nullptr);
        }
    }
    
    libfsapfs_file_entry_free(&dir_entry, nullptr);
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_fayaz_otgmaster_fs_apfs_ApfsNative_getFileSize(JNIEnv *env, jclass clazz, jlong contextPtr, jstring path) {
    ApfsNativeContext *nctx = reinterpret_cast<ApfsNativeContext*>(contextPtr);
    if (!nctx) return 0;
    const char *c_path = env->GetStringUTFChars(path, 0);
    libfsapfs_file_entry_t *entry = nullptr;
    if (libfsapfs_volume_get_file_entry_by_utf8_path(nctx->volume, reinterpret_cast<const uint8_t*>(c_path), strlen(c_path), &entry, nullptr) != 1) {
        env->ReleaseStringUTFChars(path, c_path);
        return 0;
    }
    env->ReleaseStringUTFChars(path, c_path);
    size64_t size = 0;
    libfsapfs_file_entry_get_size(entry, &size, nullptr);
    libfsapfs_file_entry_free(&entry, nullptr);
    return (jlong)size;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_fayaz_otgmaster_fs_apfs_ApfsNative_isDirectory(JNIEnv *env, jclass clazz, jlong contextPtr, jstring path) {
    ApfsNativeContext *nctx = reinterpret_cast<ApfsNativeContext*>(contextPtr);
    if (!nctx) return false;
    const char *c_path = env->GetStringUTFChars(path, 0);
    libfsapfs_file_entry_t *entry = nullptr;
    if (libfsapfs_volume_get_file_entry_by_utf8_path(nctx->volume, reinterpret_cast<const uint8_t*>(c_path), strlen(c_path), &entry, nullptr) != 1) {
        env->ReleaseStringUTFChars(path, c_path);
        return false;
    }
    env->ReleaseStringUTFChars(path, c_path);
    int is_dir = 0;
    libfsapfs_file_entry_has_directory_entries(entry, &is_dir, nullptr);
    libfsapfs_file_entry_free(&entry, nullptr);
    return is_dir == 1 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_fayaz_otgmaster_fs_apfs_ApfsNative_readFile(JNIEnv *env, jclass clazz, jlong contextPtr, jstring path, jlong offset, jobject byteBuffer, jint size) {
    ApfsNativeContext *nctx = reinterpret_cast<ApfsNativeContext*>(contextPtr);
    if (!nctx || !byteBuffer) return -1;
    void* buffer = env->GetDirectBufferAddress(byteBuffer);
    if (!buffer) return -1;

    const char *c_path = env->GetStringUTFChars(path, 0);
    libfsapfs_file_entry_t *entry = nullptr;
    if (libfsapfs_volume_get_file_entry_by_utf8_path(nctx->volume, reinterpret_cast<const uint8_t*>(c_path), strlen(c_path), &entry, nullptr) != 1) {
        env->ReleaseStringUTFChars(path, c_path);
        return -1;
    }
    env->ReleaseStringUTFChars(path, c_path);

    ssize_t read_bytes = libfsapfs_file_entry_read_buffer_at_offset(entry, buffer, size, offset, nullptr);
    libfsapfs_file_entry_free(&entry, nullptr);
    
    return (jint)read_bytes;
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_fayaz_otgmaster_fs_apfs_ApfsNative_getVolumeName(JNIEnv *env, jclass clazz, jlong contextPtr) {
    ApfsNativeContext *nctx = reinterpret_cast<ApfsNativeContext*>(contextPtr);
    if (!nctx) return env->NewStringUTF("");
    
    size_t name_size = 0;
    if (libfsapfs_volume_get_utf8_name_size(nctx->volume, &name_size, nullptr) != 1) return env->NewStringUTF("");
    std::vector<uint8_t> name_buf(name_size);
    if (libfsapfs_volume_get_utf8_name(nctx->volume, name_buf.data(), name_size, nullptr) != 1) return env->NewStringUTF("");
    return env->NewStringUTF(reinterpret_cast<const char*>(name_buf.data()));
}

extern "C" JNIEXPORT void JNICALL
Java_app_fayaz_otgmaster_fs_apfs_ApfsNative_unmount(JNIEnv *env, jclass clazz, jlong contextPtr) {
    LOGI("Unmounting APFS Volume...");
    ApfsNativeContext *nctx = reinterpret_cast<ApfsNativeContext*>(contextPtr);
    if (!nctx) return;
    
    if (nctx->volume) libfsapfs_volume_free(&nctx->volume, nullptr);
    if (nctx->container) {
        libfsapfs_container_close(nctx->container, nullptr);
        libfsapfs_container_free(&nctx->container, nullptr);
    }
    if (nctx->bfio_handle) libbfio_handle_free(&nctx->bfio_handle, nullptr);
    
    delete nctx;
}
