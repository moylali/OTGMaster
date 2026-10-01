/* Host stand-in for the NDK's <android/log.h>, for building NtfsNative.cpp
 * into a desktop JNI library that the JVM unit tests load. Logs to stderr. */
#ifndef OTG_HOST_ANDROID_LOG_H
#define OTG_HOST_ANDROID_LOG_H
#include <stdarg.h>
#include <stdio.h>
enum { ANDROID_LOG_INFO = 4, ANDROID_LOG_WARN = 5, ANDROID_LOG_ERROR = 6 };
static inline int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    fprintf(stderr, "%c/%s: ", prio >= ANDROID_LOG_ERROR ? 'E' : prio == ANDROID_LOG_WARN ? 'W' : 'I', tag);
    vfprintf(stderr, fmt, ap);
    fputc('\n', stderr);
    va_end(ap);
    return 0;
}
#endif
