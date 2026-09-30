#include <jni.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <errno.h>
#include <string.h>
#include <android/log.h>
#define LOG_E(TAG, ...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define TAG "native_usb_ioctl"

/*
 * LOCAL PATCH (docs/VENDOR_FIXES.md V13): upstream named these for the package
 * me.jahnen.libaums.usb, but AndroidUsbCommunication lives in
 * me.jahnen.libaums.core.usb, so neither symbol ever resolved: clearFeatureHalt
 * and resetDevice threw UnsatisfiedLinkError, which crashed the app once V12 made
 * Reset Recovery reachable from an ordinary failed transfer.
 */

JNIEXPORT jboolean JNICALL
Java_me_jahnen_libaums_core_usb_AndroidUsbCommunication_resetUsbDeviceNative(JNIEnv *env, jobject thiz, jint fd) {
    int ret = ioctl(fd, USBDEVFS_RESET);
    if(ret < 0) {
        LOG_E(TAG, "ioctl USBDEVFS_RESET error %d, %s", errno, strerror(errno));
    }

    return (ret == 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_me_jahnen_libaums_core_usb_AndroidUsbCommunication_clearHaltNative(JNIEnv *env, jobject thiz, jint fd, jint endpoint) {
    int ret = ioctl(fd, USBDEVFS_CLEAR_HALT, &endpoint);
    if(ret < 0) {
        LOG_E(TAG, "ioctl USBDEVFS_CLEAR_HALT error %d, %s", errno, strerror(errno));
    }

    return (ret == 0) ? JNI_TRUE : JNI_FALSE;
}