/*
 * JNI bridge between OTG Master and libfsapfs (read-only APFS).
 *
 * The container is reached through a libbfio handle whose read and seek call back
 * into Kotlin's ApfsNative.pread on the RawBlockDevice the APFS partition sits on —
 * the same shape as the NTFS bridge: libfsapfs never sees a file descriptor.
 *
 * Every entry point below names a file by its APFS inode number (the file entry
 * identifier; the root directory is 2), looks it up, does one thing and frees it
 * again. Nothing native outlives a call except the volume handle, so the Kotlin side
 * has no node lifetimes to get wrong. The one exception is a single cached file
 * entry for readFile, so a sequential read of a large file does not repeat the
 * inode lookup and extent load for every chunk; it is freed on unmount.
 *
 * Encrypted volumes take the password here: libfsapfs unwraps the volume key from
 * the container and volume keybags itself, so there is no separate decrypting block
 * device the way there is for LUKS, VeraCrypt or BitLocker.
 *
 * Built without libyal's multi-threading support: ApfsFileSystem serialises every
 * call.
 */

#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <string.h>
#include <sys/stat.h>
#include <string>
#include <vector>

extern "C" {
#include <common.h>
#include <types.h>
#include <libfsapfs/definitions.h>
#include "libfsapfs_container.h"
#include "libfsapfs_volume.h"
#include "libfsapfs_file_entry.h"
#include "libfsapfs_libbfio.h"
#include "libfsapfs_libcerror.h"
}

#define LOG_TAG "apfs-jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

/* The root directory's inode number (APFS_ROOT_DIR_INO_NUM). */
static const uint64_t ROOT_ID = 2;

/* What probe() reports. Mirrored in ApfsNative.kt. */
enum probe_result {
    PROBE_PLAIN = 0,      /* first volume opens without a password */
    PROBE_ENCRYPTED = 1,  /* first volume is locked: needs its password */
};

struct otg_io {
    JavaVM *vm;
    jobject blockDevice;     /* global ref to the RawBlockDevice */
    jclass nativeClass;      /* global ref to ApfsNative */
    jmethodID preadId;
    int64_t size;            /* partition size in bytes */
    int64_t pos;
    bool open;
};

struct otg_apfs {
    otg_io *io;
    libbfio_handle_t *bfio;
    libfsapfs_container_t *container;
    libfsapfs_volume_t *volume;
    /* One-entry cache for readFile (see the header comment). */
    uint64_t cachedId;
    libfsapfs_file_entry_t *cached;
};

static thread_local int g_lastErrno = 0;

static JNIEnv *envFor(JavaVM *vm) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
#ifdef __ANDROID__
        vm->AttachCurrentThread(&env, nullptr);
#else   /* the desktop JDK's jni.h takes void** (host unit tests) */
        vm->AttachCurrentThread((void **) &env, nullptr);
#endif
    }
    return env;
}

/* Logs libyal's error backtrace, then frees it. */
static void logError(const char *what, libcerror_error_t **error) {
    if (error == nullptr || *error == nullptr) {
        LOGE("%s", what);
        return;
    }
    char buf[1024];
    if (libcerror_error_backtrace_sprint(*error, buf, sizeof(buf)) > 0) LOGE("%s: %s", what, buf);
    else LOGE("%s", what);
    libcerror_error_free(error);
}

/* ------------------------------------------------------------------------ */
/* libbfio I/O handle over the Kotlin block device                          */
/* ------------------------------------------------------------------------ */

static int io_free(intptr_t **io_handle, libcerror_error_t **) {
    /* otg_io belongs to struct otg_apfs, which frees it after the container. */
    *io_handle = nullptr;
    return 1;
}

static int io_clone(intptr_t **, intptr_t *, libcerror_error_t **) {
    return -1;   /* libfsapfs never clones the handle it is given */
}

static int io_open(intptr_t *io_handle, int, libcerror_error_t **) {
    otg_io *io = (otg_io *) io_handle;
    io->open = true;
    io->pos = 0;
    return 1;
}

static int io_close(intptr_t *io_handle, libcerror_error_t **) {
    ((otg_io *) io_handle)->open = false;
    return 0;
}

static ssize_t io_read(intptr_t *io_handle, uint8_t *buffer, size_t size, libcerror_error_t **error) {
    otg_io *io = (otg_io *) io_handle;
    if (size == 0 || io->pos >= io->size) return 0;
    int64_t count = (int64_t) size;
    if (io->pos + count > io->size) count = io->size - io->pos;
    JNIEnv *env = envFor(io->vm);
    jbyteArray jbuf = env->NewByteArray((jsize) count);
    if (!jbuf) {
        env->ExceptionClear();
        libcerror_error_set(error, LIBCERROR_ERROR_DOMAIN_MEMORY, LIBCERROR_MEMORY_ERROR_INSUFFICIENT,
                            "io_read: cannot allocate %lld bytes", (long long) count);
        return -1;
    }
    jint n = env->CallStaticIntMethod(io->nativeClass, io->preadId, io->blockDevice,
                                      (jlong) io->pos, (jint) count, jbuf);
    if (env->ExceptionCheck()) { env->ExceptionClear(); n = -1; }
    if (n > 0) env->GetByteArrayRegion(jbuf, 0, n, (jbyte *) buffer);
    env->DeleteLocalRef(jbuf);
    if (n < 0) {
        libcerror_error_set(error, LIBCERROR_ERROR_DOMAIN_IO, LIBCERROR_IO_ERROR_READ_FAILED,
                            "io_read: %lld bytes at %lld failed", (long long) count, (long long) io->pos);
        return -1;
    }
    io->pos += n;
    return n;
}

static ssize_t io_write(intptr_t *, const uint8_t *, size_t, libcerror_error_t **error) {
    libcerror_error_set(error, LIBCERROR_ERROR_DOMAIN_IO, LIBCERROR_IO_ERROR_WRITE_FAILED,
                        "io_write: APFS is mounted read-only");
    return -1;
}

static off64_t io_seek(intptr_t *io_handle, off64_t offset, int whence, libcerror_error_t **error) {
    otg_io *io = (otg_io *) io_handle;
    int64_t base = whence == SEEK_SET ? 0 : whence == SEEK_CUR ? io->pos : io->size;
    int64_t next = base + offset;
    if (next < 0) {
        libcerror_error_set(error, LIBCERROR_ERROR_DOMAIN_IO, LIBCERROR_IO_ERROR_SEEK_FAILED,
                            "io_seek: negative offset");
        return -1;
    }
    io->pos = next;
    return next;
}

static int io_exists(intptr_t *, libcerror_error_t **) { return 1; }

static int io_is_open(intptr_t *io_handle, libcerror_error_t **) {
    return ((otg_io *) io_handle)->open ? 1 : 0;
}

static int io_get_size(intptr_t *io_handle, size64_t *size, libcerror_error_t **) {
    *size = (size64_t) ((otg_io *) io_handle)->size;
    return 1;
}

/* ------------------------------------------------------------------------ */
/* Handle lifecycle                                                          */
/* ------------------------------------------------------------------------ */

static void freeHandle(JNIEnv *env, otg_apfs *h) {
    libcerror_error_t *error = nullptr;
    if (h->cached) libfsapfs_file_entry_free(&h->cached, nullptr);
    if (h->volume) libfsapfs_volume_free(&h->volume, nullptr);
    if (h->container) {
        libfsapfs_container_close(h->container, nullptr);
        libfsapfs_container_free(&h->container, nullptr);
    }
    if (h->bfio) {
        if (libbfio_handle_free(&h->bfio, &error) != 1) logError("libbfio_handle_free", &error);
    }
    if (h->io) {
        if (h->io->blockDevice) env->DeleteGlobalRef(h->io->blockDevice);
        if (h->io->nativeClass) env->DeleteGlobalRef(h->io->nativeClass);
        delete h->io;
    }
    delete h;
}

/*
 * Opens the container on `device` and its first volume. With a non-null password
 * the volume is unlocked; a wrong one fails with EACCES. With a null password a
 * locked volume is left locked, which probe() reports and mount() refuses.
 */
static otg_apfs *openContainer(JNIEnv *env, jclass clazz, jobject device, jlong size,
                               const char *password, size_t passwordLen) {
    libcerror_error_t *error = nullptr;
    otg_apfs *h = new otg_apfs();
    h->io = new otg_io();
    env->GetJavaVM(&h->io->vm);
    h->io->blockDevice = env->NewGlobalRef(device);
    h->io->nativeClass = (jclass) env->NewGlobalRef(clazz);
    h->io->preadId = env->GetStaticMethodID(clazz, "pread",
            "(Lapp/fayaz/otgmaster/block/RawBlockDevice;JI[B)I");
    h->io->size = size;
    if (!h->io->preadId) { env->ExceptionClear(); g_lastErrno = EINVAL; freeHandle(env, h); return nullptr; }

    if (libbfio_handle_initialize(&h->bfio, (intptr_t *) h->io, io_free, io_clone, io_open, io_close,
                                  io_read, io_write, io_seek, io_exists, io_is_open, io_get_size,
                                  LIBBFIO_FLAG_IO_HANDLE_MANAGED, &error) != 1) {
        logError("libbfio_handle_initialize", &error);
        g_lastErrno = ENOMEM; freeHandle(env, h); return nullptr;
    }
    if (libfsapfs_container_initialize(&h->container, &error) != 1) {
        logError("libfsapfs_container_initialize", &error);
        g_lastErrno = ENOMEM; freeHandle(env, h); return nullptr;
    }
    if (libfsapfs_container_open_file_io_handle(h->container, h->bfio, LIBFSAPFS_OPEN_READ, &error) != 1) {
        logError("libfsapfs_container_open_file_io_handle", &error);
        g_lastErrno = EINVAL; freeHandle(env, h); return nullptr;
    }
    int count = 0;
    if (libfsapfs_container_get_number_of_volumes(h->container, &count, &error) != 1 || count < 1) {
        logError("container has no volume", &error);
        g_lastErrno = ENOENT; freeHandle(env, h); return nullptr;
    }
    if (count > 1) LOGI("container holds %d volumes; mounting the first", count);
    if (libfsapfs_container_get_volume_by_index(h->container, 0, &h->volume, &error) != 1) {
        logError("libfsapfs_container_get_volume_by_index", &error);
        g_lastErrno = EIO; freeHandle(env, h); return nullptr;
    }
    int locked = libfsapfs_volume_is_locked(h->volume, &error);
    if (locked < 0) { logError("libfsapfs_volume_is_locked", &error); g_lastErrno = EIO; freeHandle(env, h); return nullptr; }
    if (locked == 1 && password != nullptr) {
        if (libfsapfs_volume_set_utf8_password(h->volume, (const uint8_t *) password, passwordLen, &error) != 1) {
            logError("libfsapfs_volume_set_utf8_password", &error);
            g_lastErrno = EINVAL; freeHandle(env, h); return nullptr;
        }
        int rc = libfsapfs_volume_unlock(h->volume, &error);
        if (rc != 1) {
            if (rc < 0) logError("libfsapfs_volume_unlock", &error);
            g_lastErrno = EACCES; freeHandle(env, h); return nullptr;
        }
    }
    return h;
}

static otg_apfs *H(jlong handle) { return (otg_apfs *) (intptr_t) handle; }

/* Looks up a file entry by inode number; ENOENT if there is none. */
static libfsapfs_file_entry_t *entryById(otg_apfs *h, jlong id) {
    libcerror_error_t *error = nullptr;
    libfsapfs_file_entry_t *entry = nullptr;
    if ((uint64_t) id == ROOT_ID) {
        if (libfsapfs_volume_get_root_directory(h->volume, &entry, &error) != 1) {
            logError("libfsapfs_volume_get_root_directory", &error);
            g_lastErrno = EIO;
            return nullptr;
        }
        return entry;
    }
    int rc = libfsapfs_volume_get_file_entry_by_identifier(h->volume, (uint64_t) id, &entry, &error);
    if (rc != 1) {
        if (rc < 0) logError("libfsapfs_volume_get_file_entry_by_identifier", &error);
        g_lastErrno = rc == 0 ? ENOENT : EIO;
        return nullptr;
    }
    return entry;
}

/* Builds an ApfsNode for `entry`; null with g_lastErrno set on failure. */
static jobject toNode(JNIEnv *env, libfsapfs_file_entry_t *entry, const char *nameOverride) {
    libcerror_error_t *error = nullptr;
    uint64_t id = 0, size = 0;
    uint16_t mode = 0;
    int64_t ctime = 0, mtime = 0, atime = 0;
    if (libfsapfs_file_entry_get_identifier(entry, &id, &error) != 1 ||
        libfsapfs_file_entry_get_file_mode(entry, &mode, &error) != 1 ||
        libfsapfs_file_entry_get_size(entry, &size, &error) != 1) {
        logError("file entry attributes", &error);
        g_lastErrno = EIO;
        return nullptr;
    }
    /* Times are optional in the inode; a missing one reads as 0, not an error. */
    libfsapfs_file_entry_get_creation_time(entry, &ctime, nullptr);
    libfsapfs_file_entry_get_modification_time(entry, &mtime, nullptr);
    libfsapfs_file_entry_get_access_time(entry, &atime, nullptr);

    std::string name;
    if (nameOverride) {
        name = nameOverride;
    } else {
        size_t nameSize = 0;
        if (libfsapfs_file_entry_get_utf8_name_size(entry, &nameSize, &error) == 1 && nameSize > 0) {
            std::vector<uint8_t> buf(nameSize);
            if (libfsapfs_file_entry_get_utf8_name(entry, buf.data(), nameSize, &error) == 1)
                name.assign((const char *) buf.data());
            else logError("libfsapfs_file_entry_get_utf8_name", &error);
        } else if (error) {
            logError("libfsapfs_file_entry_get_utf8_name_size", &error);
        }
    }

    jclass cls = env->FindClass("app/fayaz/otgmaster/apfs/ApfsNode");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(JLjava/lang/String;ZJJJJ)V");
    jstring jname = env->NewStringUTF(name.c_str());
    if (!jname) { env->ExceptionClear(); jname = env->NewStringUTF("?"); }
    bool dir = S_ISDIR(mode);
    jobject node = env->NewObject(cls, ctor, (jlong) id, jname, (jboolean) dir,
                                  (jlong) (dir ? 0 : size),
                                  (jlong) (ctime / 1000000), (jlong) (mtime / 1000000), (jlong) (atime / 1000000));
    env->DeleteLocalRef(jname);
    env->DeleteLocalRef(cls);
    return node;
}

/* ------------------------------------------------------------------------ */
/* JNI                                                                       */
/* ------------------------------------------------------------------------ */

extern "C" {

JNIEXPORT jint JNICALL
Java_app_fayaz_otgmaster_apfs_ApfsNative_probe(JNIEnv *env, jclass clazz, jobject device, jlong size) {
    otg_apfs *h = openContainer(env, clazz, device, size, nullptr, 0);
    if (!h) return -g_lastErrno;
    int locked = libfsapfs_volume_is_locked(h->volume, nullptr);
    freeHandle(env, h);
    return locked == 1 ? PROBE_ENCRYPTED : PROBE_PLAIN;
}

JNIEXPORT jlong JNICALL
Java_app_fayaz_otgmaster_apfs_ApfsNative_mount(JNIEnv *env, jclass clazz, jobject device, jlong size,
                                                jbyteArray password) {
    std::vector<char> pw;
    if (password) {
        jsize n = env->GetArrayLength(password);
        pw.resize(n + 1, 0);
        env->GetByteArrayRegion(password, 0, n, (jbyte *) pw.data());
    }
    otg_apfs *h = openContainer(env, clazz, device, size, password ? pw.data() : nullptr,
                                password ? pw.size() - 1 : 0);
    if (!pw.empty()) memset(pw.data(), 0, pw.size());
    if (!h) return 0;
    if (libfsapfs_volume_is_locked(h->volume, nullptr) == 1) {
        freeHandle(env, h);
        g_lastErrno = EACCES;   /* encrypted, and no password was given */
        return 0;
    }
    return (jlong) (intptr_t) h;
}

JNIEXPORT jint JNICALL
Java_app_fayaz_otgmaster_apfs_ApfsNative_unmount(JNIEnv *env, jclass, jlong handle) {
    if (handle) freeHandle(env, H(handle));
    return 0;
}

JNIEXPORT jint JNICALL
Java_app_fayaz_otgmaster_apfs_ApfsNative_lastErrno(JNIEnv *, jclass) {
    return g_lastErrno;
}

JNIEXPORT jstring JNICALL
Java_app_fayaz_otgmaster_apfs_ApfsNative_volumeLabel(JNIEnv *env, jclass, jlong handle) {
    size_t n = 0;
    std::string name;
    if (libfsapfs_volume_get_utf8_name_size(H(handle)->volume, &n, nullptr) == 1 && n > 0) {
        std::vector<uint8_t> buf(n);
        if (libfsapfs_volume_get_utf8_name(H(handle)->volume, buf.data(), n, nullptr) == 1)
            name.assign((const char *) buf.data());
    }
    return env->NewStringUTF(name.c_str());
}

JNIEXPORT jlong JNICALL
Java_app_fayaz_otgmaster_apfs_ApfsNative_capacity(JNIEnv *, jclass, jlong handle) {
    size64_t size = 0;
    libfsapfs_container_get_size(H(handle)->container, &size, nullptr);
    return (jlong) size;
}

JNIEXPORT jlong JNICALL
Java_app_fayaz_otgmaster_apfs_ApfsNative_usedSpace(JNIEnv *, jclass, jlong handle) {
    /* The volume's allocated bytes (apfs_fs_alloc_count x block size). */
    size64_t size = 0;
    libfsapfs_volume_get_size(H(handle)->volume, &size, nullptr);
    return (jlong) size;
}

JNIEXPORT jboolean JNICALL
Java_app_fayaz_otgmaster_apfs_ApfsNative_isCaseSensitive(JNIEnv *, jclass, jlong handle) {
    /* APFS_INCOMPAT_CASE_INSENSITIVE (0x1) is set on Disk Utility's default "APFS". */
    uint64_t compat = 0, incompat = 0, rocompat = 0;
    if (libfsapfs_volume_get_features_flags(H(handle)->volume, &compat, &incompat, &rocompat, nullptr) != 1)
        return JNI_FALSE;
    return (incompat & 0x1) ? JNI_FALSE : JNI_TRUE;
}

JNIEXPORT jobject JNICALL
Java_app_fayaz_otgmaster_apfs_ApfsNative_root(JNIEnv *env, jclass, jlong handle) {
    libfsapfs_file_entry_t *entry = entryById(H(handle), (jlong) ROOT_ID);
    if (!entry) return nullptr;
    jobject node = toNode(env, entry, "");
    libfsapfs_file_entry_free(&entry, nullptr);
    return node;
}

JNIEXPORT jobjectArray JNICALL
Java_app_fayaz_otgmaster_apfs_ApfsNative_readDir(JNIEnv *env, jclass, jlong handle, jlong id) {
    libcerror_error_t *error = nullptr;
    otg_apfs *h = H(handle);
    libfsapfs_file_entry_t *dir = entryById(h, id);
    if (!dir) return nullptr;
    int count = 0;
    if (libfsapfs_file_entry_get_number_of_sub_file_entries(dir, &count, &error) != 1) {
        logError("libfsapfs_file_entry_get_number_of_sub_file_entries", &error);
        libfsapfs_file_entry_free(&dir, nullptr);
        g_lastErrno = ENOTDIR;
        return nullptr;
    }
    std::vector<jobject> nodes;
    nodes.reserve(count);
    for (int i = 0; i < count; i++) {
        libfsapfs_file_entry_t *child = nullptr;
        if (libfsapfs_file_entry_get_sub_file_entry_by_index(dir, i, &child, &error) != 1) {
            logError("libfsapfs_file_entry_get_sub_file_entry_by_index", &error);
            continue;   /* one unreadable entry should not hide the rest of the directory */
        }
        jobject node = toNode(env, child, nullptr);
        libfsapfs_file_entry_free(&child, nullptr);
        if (node) nodes.push_back(node);
    }
    libfsapfs_file_entry_free(&dir, nullptr);
    jclass cls = env->FindClass("app/fayaz/otgmaster/apfs/ApfsNode");
    jobjectArray out = env->NewObjectArray((jsize) nodes.size(), cls, nullptr);
    for (size_t i = 0; i < nodes.size(); i++) {
        env->SetObjectArrayElement(out, (jsize) i, nodes[i]);
        env->DeleteLocalRef(nodes[i]);
    }
    env->DeleteLocalRef(cls);
    return out;
}

JNIEXPORT jint JNICALL
Java_app_fayaz_otgmaster_apfs_ApfsNative_readFile(JNIEnv *env, jclass, jlong handle, jlong id, jlong offset,
                                                  jint size, jbyteArray buffer, jint bufferOffset) {
    libcerror_error_t *error = nullptr;
    otg_apfs *h = H(handle);
    if (size <= 0) return 0;
    if (!h->cached || h->cachedId != (uint64_t) id) {
        if (h->cached) libfsapfs_file_entry_free(&h->cached, nullptr);
        h->cached = entryById(h, id);
        if (!h->cached) return -g_lastErrno;
        h->cachedId = (uint64_t) id;
    }
    std::vector<uint8_t> buf(size);
    ssize_t n = libfsapfs_file_entry_read_buffer_at_offset(h->cached, buf.data(), (size_t) size,
                                                           (off64_t) offset, &error);
    if (n < 0) {
        logError("libfsapfs_file_entry_read_buffer_at_offset", &error);
        /* Drop the cached entry: a failed read may have left its state behind. */
        libfsapfs_file_entry_free(&h->cached, nullptr);
        return -EIO;
    }
    if (n > 0) env->SetByteArrayRegion(buffer, bufferOffset, (jsize) n, (const jbyte *) buf.data());
    return (jint) n;
}

} /* extern "C" */
