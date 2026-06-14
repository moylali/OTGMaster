
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
    jclass clazz = (*env)->FindClass(env, "app/fayaz/otgmaster/exfat/ExFatNative");
    jmethodID getDeviceSize = (*env)->GetStaticMethodID(env, clazz, "getDeviceSize", "(Lapp/fayaz/otgmaster/block/RawBlockDevice;)J");
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
    jclass clazz = (*env)->FindClass(env, "app/fayaz/otgmaster/exfat/ExFatNative");
    jmethodID preadMethod = (*env)->GetStaticMethodID(env, clazz, "pread", "(Lapp/fayaz/otgmaster/block/RawBlockDevice;JI[B)I");
    
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
    jclass clazz = (*env)->FindClass(env, "app/fayaz/otgmaster/exfat/ExFatNative");
    jmethodID pwriteMethod = (*env)->GetStaticMethodID(env, clazz, "pwrite", "(Lapp/fayaz/otgmaster/block/RawBlockDevice;JI[B)I");
    
    jbyteArray jBuffer = (*env)->NewByteArray(env, size);
    (*env)->SetByteArrayRegion(env, jBuffer, 0, size, (const jbyte*)buffer);
    
    jint result = (*env)->CallStaticIntMethod(env, clazz, pwriteMethod, (jobject)dev->block_device, (jlong)offset, (jint)size, jBuffer);
    
    (*env)->DeleteLocalRef(env, jBuffer);
    return result;
}

ssize_t exfat_generic_pread(const struct exfat* ef, struct exfat_node* node,
		void* buffer, size_t size, off_t offset)
{
	uint64_t uoffset = offset;
	cluster_t cluster;
	char* bufp = buffer;
	off_t lsize, loffset, remainder;

	if (offset < 0)
		return -EINVAL;
	if (uoffset >= node->size)
		return 0;
	if (size == 0)
		return 0;

	if (uoffset + size > node->valid_size)
	{
		ssize_t bytes = 0;

		if (uoffset < node->valid_size)
		{
			bytes = exfat_generic_pread(ef, node, buffer,
					node->valid_size - uoffset, offset);
			if (bytes < 0 || (size_t) bytes < node->valid_size - uoffset)
				return bytes;
		}
		memset(buffer + bytes, 0,
				MIN(size - bytes, node->size - node->valid_size));
		return MIN(size, node->size - uoffset);
	}

	cluster = exfat_advance_cluster(ef, node, uoffset / CLUSTER_SIZE(*ef->sb));
	if (CLUSTER_INVALID(*ef->sb, cluster))
	{
		exfat_error("invalid cluster 0x%x while reading", cluster);
		return -EIO;
	}

	loffset = uoffset % CLUSTER_SIZE(*ef->sb);
	remainder = MIN(size, node->size - uoffset);
	while (remainder > 0)
	{
		if (CLUSTER_INVALID(*ef->sb, cluster))
		{
			exfat_error("invalid cluster 0x%x while reading", cluster);
			return -EIO;
		}
		lsize = MIN(CLUSTER_SIZE(*ef->sb) - loffset, remainder);
		if (exfat_pread(ef->dev, bufp, lsize,
					exfat_c2o(ef, cluster) + loffset) < 0)
		{
			exfat_error("failed to read cluster %#x", cluster);
			return -EIO;
		}
		bufp += lsize;
		loffset = 0;
		remainder -= lsize;
		cluster = exfat_next_cluster(ef, node, cluster);
	}
	if (!(node->attrib & EXFAT_ATTRIB_DIR) && !ef->ro && !ef->noatime)
		exfat_update_atime(node);
	return MIN(size, node->size - uoffset) - remainder;
}

ssize_t exfat_generic_pwrite(struct exfat* ef, struct exfat_node* node,
		const void* buffer, size_t size, off_t offset)
{
	uint64_t uoffset = offset;
	int rc;
	cluster_t cluster;
	const char* bufp = buffer;
	off_t lsize, loffset, remainder;

	if (offset < 0)
		return -EINVAL;
	if (uoffset > node->size)
	{
		rc = exfat_truncate(ef, node, uoffset, true);
		if (rc != 0)
			return rc;
	}
	if (uoffset + size > node->size)
	{
		rc = exfat_truncate(ef, node, uoffset + size, false);
		if (rc != 0)
			return rc;
	}
	if (size == 0)
		return 0;

	cluster = exfat_advance_cluster(ef, node, uoffset / CLUSTER_SIZE(*ef->sb));
	if (CLUSTER_INVALID(*ef->sb, cluster))
	{
		exfat_error("invalid cluster 0x%x while writing", cluster);
		return -EIO;
	}

	loffset = uoffset % CLUSTER_SIZE(*ef->sb);
	remainder = size;
	while (remainder > 0)
	{
		if (CLUSTER_INVALID(*ef->sb, cluster))
		{
			exfat_error("invalid cluster 0x%x while writing", cluster);
			return -EIO;
		}
		lsize = MIN(CLUSTER_SIZE(*ef->sb) - loffset, remainder);
		if (exfat_pwrite(ef->dev, bufp, lsize,
				exfat_c2o(ef, cluster) + loffset) < 0)
		{
			exfat_error("failed to write cluster %#x", cluster);
			return -EIO;
		}
		bufp += lsize;
		loffset = 0;
		remainder -= lsize;
		node->valid_size = MAX(node->valid_size, uoffset + size - remainder);
		cluster = exfat_next_cluster(ef, node, cluster);
	}
	if (!(node->attrib & EXFAT_ATTRIB_DIR))
		/* directory's mtime should be updated by the caller only when it
		   creates or removes something in this directory */
		exfat_update_mtime(node);
	return size - remainder;
}
