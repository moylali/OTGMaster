/*
 * JNI bridge between OTG Master and libntfs-3g.
 *
 * The volume is reached through ntfs-3g's own pluggable device layer
 * (struct ntfs_device_operations), whose pread/pwrite call back into Kotlin's
 * NtfsNative.pread/pwrite on the RawBlockDevice the volume sits on — the same
 * shape as the libexfat bridge, but without patching the library: ntfs-3g was
 * designed to have its I/O supplied by the caller.
 *
 * Every entry point below names an inode by its MFT reference (record number in
 * the low 48 bits, sequence number in the high 16), opens it, does one thing and
 * closes it again. Nothing native outlives a call, so the Kotlin side holds no
 * pointers except the volume handle and has no node lifetimes to get wrong —
 * the class of crash the exFAT bridge had to work around with a release queue.
 * The sequence number is what makes a held reference safe: ntfs-3g rejects an
 * open whose sequence no longer matches the record, so a reference to a file
 * deleted since fails with ENOENT instead of reaching whatever reused the slot.
 *
 * The write paths copy the call sequences of ntfs-3g's own FUSE driver
 * (src/ntfs-3g.c: ntfs_fuse_create, _rm, _link, _write, _trunc), including the
 * order inodes are closed in, because that order is what keeps the parent's
 * index entry in step with the file's own record.
 *
 * Not thread-safe, like libntfs-3g itself: NtfsFileSystem serialises every call.
 */

#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <vector>
#include <string>

extern "C" {
#include "config.h"
#include "types.h"
#include "device.h"
#include "volume.h"
#include "inode.h"
#include "attrib.h"
#include "dir.h"
#include "unistr.h"
#include "ntfstime.h"
#include "logging.h"
}

#define LOG_TAG "ntfs-jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

/* Why a volume asked for read-write came up read-only. Mirrored in NtfsFileSystem. */
enum ro_reason {
    RO_NONE = 0,
    RO_REQUESTED = 1,     /* the user chose read-only */
    RO_HIBERNATED = 2,    /* Windows hibernated, or Fast Startup cached metadata (EPERM) */
    RO_UNCLEAN = 3,       /* $LogFile not clean: not safely removed from Windows */
};

struct otg_ntfs {
    JavaVM *vm;
    jobject blockDevice;     /* global ref to the RawBlockDevice */
    jclass nativeClass;      /* global ref to NtfsNative */
    jmethodID preadId;
    jmethodID pwriteId;
    ntfs_volume *vol;
    s64 size;                /* device size in bytes */
    s64 pos;                 /* for the seek/read/write ops ntfs-3g uses at startup */
    int roReason;
};

static thread_local int g_lastErrno = 0;

static JNIEnv *envFor(struct otg_ntfs *h) {
    JNIEnv *env = nullptr;
    if (h->vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
#ifdef __ANDROID__
        h->vm->AttachCurrentThread(&env, nullptr);
#else   /* the desktop JDK's jni.h takes void** (host unit tests) */
        h->vm->AttachCurrentThread((void **) &env, nullptr);
#endif
    }
    return env;
}

/* ------------------------------------------------------------------------ */
/* Device operations                                                         */
/* ------------------------------------------------------------------------ */

static struct otg_ntfs *handleOf(struct ntfs_device *dev) {
    return (struct otg_ntfs *) dev->d_private;
}

static int dev_open(struct ntfs_device *dev, int flags) {
    if (NDevOpen(dev)) { errno = EBUSY; return -1; }
    if ((flags & O_ACCMODE) == O_RDONLY) NDevSetReadOnly(dev);
    else NDevClearReadOnly(dev);
    NDevSetBlock(dev);
    NDevSetOpen(dev);
    handleOf(dev)->pos = 0;
    return 0;
}

static int dev_close(struct ntfs_device *dev) {
    if (!NDevOpen(dev)) { errno = EBADF; return -1; }
    /* The block device's global ref belongs to struct otg_ntfs, not to the
     * ntfs_device: a failed mount closes the device and is retried on a fresh
     * one over the same block device. */
    NDevClearOpen(dev);
    return 0;
}

static s64 dev_pread(struct ntfs_device *dev, void *buf, s64 count, s64 offset) {
    struct otg_ntfs *h = handleOf(dev);
    if (count <= 0) return 0;
    if (offset >= h->size) return 0;
    if (offset + count > h->size) count = h->size - offset;
    JNIEnv *env = envFor(h);
    jbyteArray jbuf = env->NewByteArray((jsize) count);
    if (!jbuf) { env->ExceptionClear(); errno = ENOMEM; return -1; }
    jint n = env->CallStaticIntMethod(h->nativeClass, h->preadId, h->blockDevice,
                                      (jlong) offset, (jint) count, jbuf);
    if (env->ExceptionCheck()) { env->ExceptionClear(); n = -1; }
    if (n > 0) env->GetByteArrayRegion(jbuf, 0, n, (jbyte *) buf);
    env->DeleteLocalRef(jbuf);
    if (n < 0) { errno = EIO; return -1; }
    return n;
}

static s64 dev_pwrite(struct ntfs_device *dev, const void *buf, s64 count, s64 offset) {
    struct otg_ntfs *h = handleOf(dev);
    if (NDevReadOnly(dev)) { errno = EROFS; return -1; }
    if (count <= 0) return 0;
    if (offset + count > h->size) { errno = ENOSPC; return -1; }
    JNIEnv *env = envFor(h);
    jbyteArray jbuf = env->NewByteArray((jsize) count);
    if (!jbuf) { env->ExceptionClear(); errno = ENOMEM; return -1; }
    env->SetByteArrayRegion(jbuf, 0, (jsize) count, (const jbyte *) buf);
    jint n = env->CallStaticIntMethod(h->nativeClass, h->pwriteId, h->blockDevice,
                                      (jlong) offset, (jint) count, jbuf);
    if (env->ExceptionCheck()) { env->ExceptionClear(); n = -1; }
    env->DeleteLocalRef(jbuf);
    if (n < 0) { errno = EIO; return -1; }
    NDevSetDirty(dev);
    return n;
}

static s64 dev_seek(struct ntfs_device *dev, s64 offset, int whence) {
    struct otg_ntfs *h = handleOf(dev);
    s64 base;
    switch (whence) {
        case SEEK_SET: base = 0; break;
        case SEEK_CUR: base = h->pos; break;
        case SEEK_END: base = h->size; break;
        default: errno = EINVAL; return -1;
    }
    if (base + offset < 0) { errno = EINVAL; return -1; }
    h->pos = base + offset;
    return h->pos;
}

static s64 dev_read(struct ntfs_device *dev, void *buf, s64 count) {
    s64 n = dev_pread(dev, buf, count, handleOf(dev)->pos);
    if (n > 0) handleOf(dev)->pos += n;
    return n;
}

static s64 dev_write(struct ntfs_device *dev, const void *buf, s64 count) {
    s64 n = dev_pwrite(dev, buf, count, handleOf(dev)->pos);
    if (n > 0) handleOf(dev)->pos += n;
    return n;
}

static int dev_sync(struct ntfs_device *dev) {
    /* CachedBlockDevice is write-through: a returned pwrite has reached the drive. */
    NDevClearDirty(dev);
    return 0;
}

static int dev_stat(struct ntfs_device *dev, struct stat *st) {
    memset(st, 0, sizeof(*st));
    st->st_mode = S_IFBLK;
    st->st_size = handleOf(dev)->size;
    return 0;
}

static int dev_ioctl(struct ntfs_device *, unsigned long, void *) {
    errno = ENOTTY;
    return -1;
}

static struct ntfs_device_operations otg_dev_ops = {
    dev_open, dev_close, dev_seek, dev_read, dev_write,
    dev_pread, dev_pwrite, dev_sync, dev_stat, dev_ioctl,
};

/* ------------------------------------------------------------------------ */
/* Logging: ntfs-3g writes to stderr by default, which Android discards.     */
/* ------------------------------------------------------------------------ */

static int log_to_logcat(const char *function, const char *, int, u32 level,
                         void *, const char *format, va_list args) {
    int prio = ANDROID_LOG_INFO;
    if (level & (NTFS_LOG_LEVEL_ERROR | NTFS_LOG_LEVEL_PERROR | NTFS_LOG_LEVEL_CRITICAL))
        prio = ANDROID_LOG_ERROR;
    else if (level & NTFS_LOG_LEVEL_WARNING)
        prio = ANDROID_LOG_WARN;
    char msg[512];
    vsnprintf(msg, sizeof(msg), format, args);
    if (level & NTFS_LOG_LEVEL_PERROR) {
        __android_log_print(prio, "ntfs-3g", "%s: %s: %s", function, msg, strerror(errno));
    } else {
        __android_log_print(prio, "ntfs-3g", "%s: %s", function, msg);
    }
    return 0;
}

/* ------------------------------------------------------------------------ */
/* Helpers                                                                   */
/* ------------------------------------------------------------------------ */

static struct otg_ntfs *H(jlong p) { return (struct otg_ntfs *) p; }

static jlong toMillis(sle64 t) {
    struct timespec ts = ntfs2timespec(t);
    return (jlong) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static jlong mrefOf(ntfs_inode *ni) {
    return (jlong) MK_MREF(ni->mft_no, le16_to_cpu(ni->mrec->sequence_number));
}

static bool isDir(ntfs_inode *ni) {
    return (ni->mrec->flags & MFT_RECORD_IS_DIRECTORY) != 0;
}

static jobject makeNode(JNIEnv *env, ntfs_inode *ni, const char *name) {
    jclass cls = env->FindClass("app/fayaz/otgmaster/ntfs/NtfsNode");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(JLjava/lang/String;ZJJJJ)V");
    jstring jname = env->NewStringUTF(name);
    bool dir = isDir(ni);
    jobject node = env->NewObject(cls, ctor, mrefOf(ni), jname, (jboolean) dir,
                                  (jlong) (dir ? 0 : ni->data_size),
                                  toMillis(ni->creation_time),
                                  toMillis(ni->last_data_change_time),
                                  toMillis(ni->last_access_time));
    env->DeleteLocalRef(jname);
    env->DeleteLocalRef(cls);
    return node;
}

/* Converts a Java name to NTFS UTF-16, refusing what Windows would refuse. */
static int toNtfsName(JNIEnv *env, ntfs_volume *vol, jstring jname, ntfschar **out) {
    const char *name = env->GetStringUTFChars(jname, nullptr);
    *out = nullptr;
    int len = ntfs_mbstoucs(name, out);
    env->ReleaseStringUTFChars(jname, name);
    if (len < 0) return -errno;
    /* Names Windows cannot open (reserved device names, <>:"/\|?*, trailing dot
     * or space) are legal NTFS but make the file unreachable from Explorer.
     * ntfs-3g's FUSE driver refuses them under its windows_names option; this
     * app's drives are expected to go back to Windows, so refuse them always. */
    if (len == 0 || ntfs_forbidden_names(vol, *out, len, TRUE)) {
        free(*out);
        *out = nullptr;
        return -EINVAL;
    }
    return len;
}

static ntfs_inode *openRef(struct otg_ntfs *h, jlong mref) {
    return ntfs_inode_open(h->vol, (MFT_REF) mref);
}

/*
 * True if `dir` already holds a name equal to `uname` ignoring case.
 *
 * ntfs-3g files every name it creates in the POSIX namespace, which is
 * case-sensitive, so on its own it accepts "a.txt" beside "A.TXT". Both are
 * legal NTFS, but Windows resolves either name to whichever comes first and the
 * other file cannot be opened from Explorer.
 *
 * The volume stays case-sensitive: ntfs-3g's ignore-case mode (ntfs_set_ignore_case)
 * also lowercases every name ntfs_readdir reports. The flag is cleared only for
 * this one lookup, which collates through $UpCase as Windows does; nothing else
 * runs in between, since every call is serialised by NtfsFileSystem.
 */
static bool nameTaken(ntfs_inode *dir, const ntfschar *uname, int ulen) {
    ntfs_volume *vol = dir->vol;
    bool wasSensitive = NVolCaseSensitive(vol);
    NVolClearCaseSensitive(vol);
    u64 ref = ntfs_inode_lookup_by_name(dir, uname, ulen);
    if (wasSensitive) NVolSetCaseSensitive(vol);
    return ref != (u64) -1;
}

static int fail(int err) {
    g_lastErrno = err;
    return -err;
}

/* ------------------------------------------------------------------------ */
/* Mount / unmount / volume info                                             */
/* ------------------------------------------------------------------------ */

static ntfs_volume *tryMount(struct otg_ntfs *h, ntfs_mount_flags flags, int *err) {
    struct ntfs_device *dev = ntfs_device_alloc("otg-ntfs", 0, &otg_dev_ops, h);
    if (!dev) { *err = errno; return nullptr; }
    ntfs_volume *vol = ntfs_device_mount(dev, flags);
    if (!vol) {
        *err = errno;
        ntfs_device_free(dev);
    }
    return vol;
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_fayaz_otgmaster_ntfs_NtfsNative_mount(JNIEnv *env, jobject, jobject blockDevice,
                                               jlong sizeBytes, jboolean readOnly) {
    static bool logInit = false;
    if (!logInit) {
        ntfs_log_set_handler(log_to_logcat);
        ntfs_log_clear_levels(NTFS_LOG_LEVEL_INFO | NTFS_LOG_LEVEL_QUIET |
                              NTFS_LOG_LEVEL_VERBOSE | NTFS_LOG_LEVEL_PROGRESS);
        ntfs_log_set_levels(NTFS_LOG_LEVEL_WARNING | NTFS_LOG_LEVEL_ERROR |
                            NTFS_LOG_LEVEL_PERROR | NTFS_LOG_LEVEL_CRITICAL);
        logInit = true;
    }

    struct otg_ntfs *h = (struct otg_ntfs *) calloc(1, sizeof(struct otg_ntfs));
    if (!h) return 0;
    env->GetJavaVM(&h->vm);
    h->blockDevice = env->NewGlobalRef(blockDevice);
    jclass cls = env->FindClass("app/fayaz/otgmaster/ntfs/NtfsNative");
    h->nativeClass = (jclass) env->NewGlobalRef(cls);
    env->DeleteLocalRef(cls);
    h->preadId = env->GetStaticMethodID(h->nativeClass, "pread",
            "(Lapp/fayaz/otgmaster/block/RawBlockDevice;JI[B)I");
    h->pwriteId = env->GetStaticMethodID(h->nativeClass, "pwrite",
            "(Lapp/fayaz/otgmaster/block/RawBlockDevice;JI[B)I");
    h->size = sizeBytes;

    int err = 0;
    ntfs_volume *vol = nullptr;
    if (readOnly) {
        h->roReason = RO_REQUESTED;
        vol = tryMount(h, NTFS_MNT_RDONLY, &err);
    } else {
        /*
         * No NTFS_MNT_RECOVER. ntfs-3g can "fix" an unclean volume by emptying
         * $LogFile, which discards whatever Windows had journalled but not yet
         * applied — the NTFS equivalent of writing over an ext4 journal that
         * needs recovery. Both cases below fall back to read-only and say why;
         * the user can let Windows finish with the volume first.
         */
        vol = tryMount(h, NTFS_MNT_NONE, &err);
        if (!vol && (err == EPERM || err == EOPNOTSUPP)) {
            h->roReason = (err == EPERM) ? RO_HIBERNATED : RO_UNCLEAN;
            LOGI("read-write mount refused (%s), retrying read-only", strerror(err));
            vol = tryMount(h, NTFS_MNT_RDONLY, &err);
        }
    }
    if (!vol) {
        LOGE("ntfs mount failed: %s", strerror(err));
        g_lastErrno = err;
        env->DeleteGlobalRef(h->blockDevice);
        env->DeleteGlobalRef(h->nativeClass);
        free(h);
        return 0;
    }
    h->vol = vol;
    if (ntfs_volume_get_free_space(vol)) {
        LOGE("could not read the cluster bitmap: %s", strerror(errno));
    }
    LOGI("ntfs mounted: \"%s\", %lld clusters of %u bytes, %lld free%s",
         vol->vol_name ? vol->vol_name : "", (long long) vol->nr_clusters,
         vol->cluster_size, (long long) vol->free_clusters,
         NVolReadOnly(vol) ? " (read-only)" : "");
    return (jlong) h;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_fayaz_otgmaster_ntfs_NtfsNative_unmount(JNIEnv *env, jclass, jlong hp) {
    struct otg_ntfs *h = H(hp);
    if (!h) return 0;
    int rc = 0;
    if (h->vol && ntfs_umount(h->vol, FALSE)) rc = -errno;
    env->DeleteGlobalRef(h->blockDevice);
    env->DeleteGlobalRef(h->nativeClass);
    free(h);
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_fayaz_otgmaster_ntfs_NtfsNative_lastErrno(JNIEnv *, jclass) {
    return g_lastErrno;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_fayaz_otgmaster_ntfs_NtfsNative_readOnlyReason(JNIEnv *, jclass, jlong hp) {
    struct otg_ntfs *h = H(hp);
    if (!NVolReadOnly(h->vol)) return RO_NONE;
    return h->roReason ? h->roReason : RO_REQUESTED;
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_fayaz_otgmaster_ntfs_NtfsNative_volumeLabel(JNIEnv *env, jclass, jlong hp) {
    struct otg_ntfs *h = H(hp);
    return env->NewStringUTF(h->vol->vol_name ? h->vol->vol_name : "");
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_fayaz_otgmaster_ntfs_NtfsNative_capacity(JNIEnv *, jclass, jlong hp) {
    struct otg_ntfs *h = H(hp);
    return (jlong) h->vol->nr_clusters * h->vol->cluster_size;
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_fayaz_otgmaster_ntfs_NtfsNative_freeSpace(JNIEnv *, jclass, jlong hp) {
    struct otg_ntfs *h = H(hp);
    /* Kept current by the cluster allocator after the scan at mount. */
    s64 freeClusters = h->vol->free_clusters;
    return freeClusters < 0 ? 0 : (jlong) freeClusters * h->vol->cluster_size;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_fayaz_otgmaster_ntfs_NtfsNative_clusterSize(JNIEnv *, jclass, jlong hp) {
    return (jint) H(hp)->vol->cluster_size;
}

/* ------------------------------------------------------------------------ */
/* Lookup and listing                                                        */
/* ------------------------------------------------------------------------ */

extern "C" JNIEXPORT jobject JNICALL
Java_app_fayaz_otgmaster_ntfs_NtfsNative_root(JNIEnv *env, jclass, jlong hp) {
    struct otg_ntfs *h = H(hp);
    ntfs_inode *ni = ntfs_inode_open(h->vol, FILE_root);
    if (!ni) { fail(errno); return nullptr; }
    jobject node = makeNode(env, ni, "/");
    ntfs_inode_close(ni);
    return node;
}

extern "C" JNIEXPORT jobject JNICALL
Java_app_fayaz_otgmaster_ntfs_NtfsNative_stat(JNIEnv *env, jclass, jlong hp, jlong mref,
                                              jstring jname) {
    struct otg_ntfs *h = H(hp);
    ntfs_inode *ni = openRef(h, mref);
    if (!ni) { fail(errno); return nullptr; }
    const char *name = env->GetStringUTFChars(jname, nullptr);
    jobject node = makeNode(env, ni, name);
    env->ReleaseStringUTFChars(jname, name);
    ntfs_inode_close(ni);
    return node;
}

struct dir_entry { std::string name; MFT_REF mref; };

static int collect(void *dirent, const ntfschar *name, const int name_len,
                   const int name_type, const s64, const MFT_REF mref, const unsigned) {
    auto *out = (std::vector<dir_entry> *) dirent;
    /* A file with a Windows long name also has an 8.3 DOS alias in the index;
     * listing both would show every such file twice. */
    if (name_type == FILE_NAME_DOS) return 0;
    /* Metadata files ($MFT, $Bitmap, ...) and "."/"..", which ntfs_readdir
     * synthesises with the directory's and parent's own references. */
    if (MREF(mref) < FILE_first_user) return 0;
    char *utf8 = nullptr;
    if (ntfs_ucstombs(name, name_len, &utf8, 0) < 0) {
        ntfs_log_perror("skipping an entry whose name does not convert");
        return 0;
    }
    if (strcmp(utf8, ".") && strcmp(utf8, "..")) out->push_back({utf8, mref});
    free(utf8);
    return 0;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_app_fayaz_otgmaster_ntfs_NtfsNative_readDir(JNIEnv *env, jclass, jlong hp, jlong mref) {
    struct otg_ntfs *h = H(hp);
    ntfs_inode *dir = openRef(h, mref);
    if (!dir) { fail(errno); return nullptr; }
    if (!isDir(dir)) { ntfs_inode_close(dir); fail(ENOTDIR); return nullptr; }

    std::vector<dir_entry> entries;
    s64 pos = 0;
    int rc = ntfs_readdir(dir, &pos, &entries, collect);
    int err = errno;
    ntfs_inode_close(dir);
    if (rc) { fail(err); return nullptr; }

    /*
     * Each child is opened for its size and times rather than taken from the
     * index entry: ntfs-3g documents the copy in the index as unreliable, since
     * Windows does not always update it.
     */
    jclass cls = env->FindClass("app/fayaz/otgmaster/ntfs/NtfsNode");
    jobjectArray arr = env->NewObjectArray((jsize) entries.size(), cls, nullptr);
    env->DeleteLocalRef(cls);
    for (size_t i = 0; i < entries.size(); i++) {
        ntfs_inode *ni = ntfs_inode_open(h->vol, entries[i].mref);
        if (!ni) {
            /* Failing the whole listing beats a short one: a caller cannot tell a
             * missing entry from a deleted file. */
            fail(errno);
            LOGE("readDir: cannot open %s (mft %llu): %s", entries[i].name.c_str(),
                 (unsigned long long) MREF(entries[i].mref), strerror(errno));
            return nullptr;
        }
        jobject node = makeNode(env, ni, entries[i].name.c_str());
        ntfs_inode_close(ni);
        env->SetObjectArrayElement(arr, (jsize) i, node);
        env->DeleteLocalRef(node);
    }
    return arr;
}

extern "C" JNIEXPORT jobject JNICALL
Java_app_fayaz_otgmaster_ntfs_NtfsNative_lookup(JNIEnv *env, jclass, jlong hp, jlong dirRef,
                                                jstring jname) {
    struct otg_ntfs *h = H(hp);
    ntfs_inode *dir = openRef(h, dirRef);
    if (!dir) { fail(errno); return nullptr; }
    const char *name = env->GetStringUTFChars(jname, nullptr);
    u64 ref = ntfs_inode_lookup_by_mbsname(dir, name);
    int err = errno;
    ntfs_inode_close(dir);
    jobject node = nullptr;
    if (ref == (u64) -1) {
        fail(err);
    } else if (MREF(ref) < FILE_first_user) {
        fail(ENOENT);
    } else {
        ntfs_inode *ni = ntfs_inode_open(h->vol, ref);
        if (!ni) fail(errno);
        else {
            node = makeNode(env, ni, name);
            ntfs_inode_close(ni);
        }
    }
    env->ReleaseStringUTFChars(jname, name);
    return node;
}

/* ------------------------------------------------------------------------ */
/* File data                                                                 */
/* ------------------------------------------------------------------------ */

extern "C" JNIEXPORT jint JNICALL
Java_app_fayaz_otgmaster_ntfs_NtfsNative_readFile(JNIEnv *env, jclass, jlong hp, jlong mref,
                                                  jlong offset, jint size, jbyteArray buffer,
                                                  jint bufferOffset) {
    struct otg_ntfs *h = H(hp);
    if (size < 0 || bufferOffset < 0 || bufferOffset + size > env->GetArrayLength(buffer))
        return fail(EINVAL);
    ntfs_inode *ni = openRef(h, mref);
    if (!ni) return fail(errno);
    ntfs_attr *na = ntfs_attr_open(ni, AT_DATA, AT_UNNAMED, 0);
    if (!na) {
        int err = errno;
        ntfs_inode_close(ni);
        /* No unnamed $DATA: a reparse point such as a symlink. It has no content
         * this driver can follow, so it reads as empty rather than failing. */
        return err == ENOENT ? 0 : fail(err);
    }
    jbyte *elements = env->GetByteArrayElements(buffer, nullptr);
    if (!elements) { ntfs_attr_close(na); ntfs_inode_close(ni); return fail(ENOMEM); }

    /* ntfs_attr_pread may return short, e.g. at the end of a compression unit. */
    s64 total = 0;
    int err = 0;
    while (total < size) {
        s64 n = ntfs_attr_pread(na, offset + total, size - total, elements + bufferOffset + total);
        if (n < 0) { err = errno; break; }
        if (n == 0) break;
        total += n;
    }
    env->ReleaseByteArrayElements(buffer, elements, total > 0 ? 0 : JNI_ABORT);
    ntfs_attr_close(na);
    /* No atime update: a read must not write to the volume. */
    ntfs_inode_close(ni);
    if (err && total == 0) return fail(err);
    return (jint) total;
}

static bool refuseDataWrite(ntfs_attr *na) {
    /*
     * Writing a compressed attribute needs a closing step (ntfs_attr_pclose)
     * that FUSE runs on release; doing it per call would recompress the tail
     * unit on every write. Encrypted (EFS) data cannot be written without the
     * key. Both are refused rather than half-supported.
     */
    if (na->data_flags & ATTR_COMPRESSION_MASK) { errno = EOPNOTSUPP; return true; }
    if (na->data_flags & ATTR_IS_ENCRYPTED) { errno = EACCES; return true; }
    return false;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_fayaz_otgmaster_ntfs_NtfsNative_writeFile(JNIEnv *env, jclass, jlong hp, jlong mref,
                                                   jlong offset, jint size, jbyteArray buffer,
                                                   jint bufferOffset) {
    struct otg_ntfs *h = H(hp);
    if (size < 0 || bufferOffset < 0 || bufferOffset + size > env->GetArrayLength(buffer))
        return fail(EINVAL);
    ntfs_inode *ni = openRef(h, mref);
    if (!ni) return fail(errno);
    if (ni->mft_no < FILE_first_user || isDir(ni)) { ntfs_inode_close(ni); return fail(EPERM); }
    ntfs_attr *na = ntfs_attr_open(ni, AT_DATA, AT_UNNAMED, 0);
    if (!na || refuseDataWrite(na)) {
        int err = errno;
        if (na) ntfs_attr_close(na);
        ntfs_inode_close(ni);
        return fail(err);
    }
    jbyte *elements = env->GetByteArrayElements(buffer, nullptr);
    if (!elements) { ntfs_attr_close(na); ntfs_inode_close(ni); return fail(ENOMEM); }
    s64 total = 0;
    int err = 0;
    while (total < size) {
        s64 n = ntfs_attr_pwrite(na, offset + total, size - total, elements + bufferOffset + total);
        if (n <= 0) { err = n < 0 ? errno : ENOSPC; break; }
        total += n;
    }
    env->ReleaseByteArrayElements(buffer, elements, JNI_ABORT);
    if (total > 0) {
        ntfs_inode_update_times(ni, (ntfs_time_update_flags) NTFS_UPDATE_MCTIME);
        ni->flags = (FILE_ATTR_FLAGS) (ni->flags | FILE_ATTR_ARCHIVE);
    }
    ntfs_attr_close(na);
    if (ntfs_inode_close(ni) && !err) err = errno;
    if (err) return fail(err);
    return (jint) total;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_fayaz_otgmaster_ntfs_NtfsNative_setLength(JNIEnv *, jclass, jlong hp, jlong mref,
                                                   jlong length) {
    struct otg_ntfs *h = H(hp);
    if (length < 0) return fail(EINVAL);
    ntfs_inode *ni = openRef(h, mref);
    if (!ni) return fail(errno);
    if (ni->mft_no < FILE_first_user || isDir(ni)) { ntfs_inode_close(ni); return fail(EPERM); }
    ntfs_attr *na = ntfs_attr_open(ni, AT_DATA, AT_UNNAMED, 0);
    int err = 0;
    if (!na || refuseDataWrite(na)) {
        err = errno;
    } else if (na->data_size != length) {
        if (ntfs_attr_truncate(na, length)) err = errno;
        else {
            ntfs_inode_update_times(ni, (ntfs_time_update_flags) NTFS_UPDATE_MCTIME);
            ni->flags = (FILE_ATTR_FLAGS) (ni->flags | FILE_ATTR_ARCHIVE);
        }
    }
    if (na) ntfs_attr_close(na);
    if (ntfs_inode_close(ni) && !err) err = errno;
    return err ? fail(err) : 0;
}

/* ------------------------------------------------------------------------ */
/* Namespace                                                                 */
/* ------------------------------------------------------------------------ */

extern "C" JNIEXPORT jobject JNICALL
Java_app_fayaz_otgmaster_ntfs_NtfsNative_create(JNIEnv *env, jclass, jlong hp, jlong dirRef,
                                                jstring jname, jboolean directory) {
    struct otg_ntfs *h = H(hp);
    ntfschar *uname = nullptr;
    int ulen = toNtfsName(env, h->vol, jname, &uname);
    if (ulen < 0) { fail(-ulen); return nullptr; }
    ntfs_inode *dir = openRef(h, dirRef);
    if (!dir) { fail(errno); free(uname); return nullptr; }
    if (!isDir(dir) || dir->mft_no == FILE_Extend || (dir->flags & FILE_ATTR_REPARSE_POINT)) {
        int err = isDir(dir) ? EPERM : ENOTDIR;
        ntfs_inode_close(dir);
        free(uname);
        fail(err);
        return nullptr;
    }
    if (nameTaken(dir, uname, ulen)) {
        ntfs_inode_close(dir);
        free(uname);
        fail(EEXIST);
        return nullptr;
    }
    /* securid 0: no security descriptor of our own; Windows applies the
     * inherited defaults, as for a file created by ntfs-3g without user mapping. */
    ntfs_inode *ni = ntfs_create(dir, const_cpu_to_le32(0), uname, (u8) ulen,
                                 directory ? S_IFDIR : S_IFREG);
    free(uname);
    if (!ni) {
        int err = errno;
        ntfs_inode_close(dir);
        fail(err);
        return nullptr;
    }
    ni->flags = (FILE_ATTR_FLAGS) (ni->flags | FILE_ATTR_ARCHIVE);
    NInoSetDirty(ni);
    MFT_REF ref = (MFT_REF) mrefOf(ni);
    /* Closing the new inode syncs its name into dir's index, so it goes first
     * and through dir rather than reopening it. */
    int err = 0;
    if (ntfs_inode_close_in_dir(ni, dir)) err = errno;
    ntfs_inode_update_times(dir, (ntfs_time_update_flags) NTFS_UPDATE_MCTIME);
    if (ntfs_inode_close(dir) && !err) err = errno;
    if (err) { fail(err); return nullptr; }

    ni = ntfs_inode_open(h->vol, ref);
    if (!ni) { fail(errno); return nullptr; }
    const char *name = env->GetStringUTFChars(jname, nullptr);
    jobject node = makeNode(env, ni, name);
    env->ReleaseStringUTFChars(jname, name);
    ntfs_inode_close(ni);
    return node;
}

/* Removes the name `jname` in directory `dirRef`, which must refer to `mref`. */
static int unlinkName(JNIEnv *env, struct otg_ntfs *h, jlong dirRef, jlong mref, jstring jname) {
    ntfschar *uname = nullptr;
    const char *name = env->GetStringUTFChars(jname, nullptr);
    int ulen = ntfs_mbstoucs(name, &uname);
    env->ReleaseStringUTFChars(jname, name);
    if (ulen < 0) return -errno;
    ntfs_inode *ni = openRef(h, mref);
    if (!ni) { int e = errno; free(uname); return -e; }
    if (ni->mft_no < FILE_first_user) { ntfs_inode_close(ni); free(uname); return -EPERM; }
    ntfs_inode *dir = openRef(h, dirRef);
    if (!dir || dir->mft_no == FILE_Extend) {
        int e = dir ? EPERM : errno;
        if (dir) ntfs_inode_close(dir);
        ntfs_inode_close(ni);
        free(uname);
        return -e;
    }
    /* ntfs_delete closes both inodes whatever the outcome. A non-empty
     * directory fails here with ENOTEMPTY. */
    int rc = ntfs_delete(h->vol, nullptr, ni, dir, uname, (u8) ulen);
    int e = errno;
    free(uname);
    return rc ? -e : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_fayaz_otgmaster_ntfs_NtfsNative_delete(JNIEnv *env, jclass, jlong hp, jlong dirRef,
                                                jlong mref, jstring jname) {
    int rc = unlinkName(env, H(hp), dirRef, mref, jname);
    return rc ? fail(-rc) : 0;
}

/* Adds the name `jname` in directory `dirRef` for `mref`. */
static int linkName(JNIEnv *env, struct otg_ntfs *h, jlong mref, jlong dirRef, jstring jname) {
    ntfschar *uname = nullptr;
    int ulen = toNtfsName(env, h->vol, jname, &uname);
    if (ulen < 0) return ulen;
    ntfs_inode *ni = openRef(h, mref);
    if (!ni) { int e = errno; free(uname); return -e; }
    ntfs_inode *dir = openRef(h, dirRef);
    if (!dir) { int e = errno; ntfs_inode_close(ni); free(uname); return -e; }
    int err = 0;
    if (!isDir(dir) || dir->mft_no == FILE_Extend || (dir->flags & FILE_ATTR_REPARSE_POINT)) {
        err = EPERM;
    } else if (nameTaken(dir, uname, ulen)) {
        err = EEXIST;
    } else if (ntfs_link(ni, dir, uname, (u8) ulen)) {
        err = errno;
    } else {
        ni->flags = (FILE_ATTR_FLAGS) (ni->flags | FILE_ATTR_ARCHIVE);
        ntfs_inode_update_times(ni, (ntfs_time_update_flags) NTFS_UPDATE_CTIME);
        ntfs_inode_update_times(dir, (ntfs_time_update_flags) NTFS_UPDATE_MCTIME);
    }
    free(uname);
    /* dir first: closing ni syncs its FILE_NAME into an index that has to be
     * on disk already (see ntfs_fuse_link). */
    if (ntfs_inode_close(dir) && !err) err = errno;
    if (ntfs_inode_close(ni) && !err) err = errno;
    return err ? -err : 0;
}

/*
 * Rename as ntfs-3g's FUSE driver does it: link the new name, then unlink the
 * old one. Not atomic — a failure between the two leaves the file reachable
 * under both names, which is recoverable, rather than under neither.
 *
 * The destination must not exist. A change of case only (a.txt -> A.txt) goes
 * through a temporary name, because Win32 names collate case-insensitively and
 * the new name would otherwise collide with the old one.
 */
extern "C" JNIEXPORT jint JNICALL
Java_app_fayaz_otgmaster_ntfs_NtfsNative_rename(JNIEnv *env, jclass, jlong hp, jlong mref,
                                                jlong oldDirRef, jstring oldName,
                                                jlong newDirRef, jstring newName,
                                                jboolean caseOnly) {
    struct otg_ntfs *h = H(hp);
    if (caseOnly) {
        char tmp[64];
        snprintf(tmp, sizeof(tmp), ".otg-rename-%016llx", (unsigned long long) MREF(mref));
        jstring jtmp = env->NewStringUTF(tmp);
        int rc = linkName(env, h, mref, oldDirRef, jtmp);
        if (!rc) rc = unlinkName(env, h, oldDirRef, mref, oldName);
        if (!rc) rc = linkName(env, h, mref, newDirRef, newName);
        if (!rc) rc = unlinkName(env, h, oldDirRef, mref, jtmp);
        env->DeleteLocalRef(jtmp);
        return rc ? fail(-rc) : 0;
    }
    int rc = linkName(env, h, mref, newDirRef, newName);
    if (rc) return fail(-rc);
    rc = unlinkName(env, h, oldDirRef, mref, oldName);
    return rc ? fail(-rc) : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_fayaz_otgmaster_ntfs_NtfsNative_sync(JNIEnv *, jclass, jlong hp) {
    struct otg_ntfs *h = H(hp);
    /* Inodes are written back as each call closes them; this only covers
     * anything ntfs-3g holds open on its own (the MFT and bitmap attributes). */
    return ntfs_device_sync(h->vol->dev) ? fail(errno) : 0;
}

/* The volume cluster bitmap ($Bitmap), one bit per cluster, set when in use.
 * Used by the debug volume dump to copy only allocated clusters. */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_fayaz_otgmaster_ntfs_NtfsNative_clusterBitmap(JNIEnv *env, jclass, jlong hp) {
    struct otg_ntfs *h = H(hp);
    s64 len = (h->vol->nr_clusters + 7) / 8;
    jbyteArray arr = env->NewByteArray((jsize) len);
    if (!arr) return nullptr;
    jbyte *buf = env->GetByteArrayElements(arr, nullptr);
    s64 n = ntfs_attr_pread(h->vol->lcnbmp_na, 0, len, buf);
    env->ReleaseByteArrayElements(arr, buf, 0);
    if (n != len) { fail(n < 0 ? errno : EIO); return nullptr; }
    return arr;
}
