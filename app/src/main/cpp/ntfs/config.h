/*
 * Hand-written config.h for building libntfs-3g against Android's bionic libc.
 *
 * Upstream generates this with autoconf, which cannot run against the NDK. Only
 * what libntfs-3g itself tests is defined; FUSE, ntfsprogs and the default unix
 * device I/O are not built (the device is OTG Master's block layer, reached
 * through the ntfs_device_operations in NtfsNative.cpp).
 */
#ifndef OTG_NTFS_CONFIG_H
#define OTG_NTFS_CONFIG_H

#define PACKAGE_NAME "ntfs-3g"
#define PACKAGE_VERSION "2026.9.28"
#define VERSION "2026.9.28"

#define STDC_HEADERS 1
#define HAVE_BYTESWAP_H 1
#define HAVE_CLOCK_GETTIME 1
#define HAVE_CTYPE_H 1
#define HAVE_ENDIAN_H 1
#define HAVE_ERRNO_H 1
#define HAVE_FCNTL_H 1
#define HAVE_FFS 1
#define HAVE_GETTIMEOFDAY 1
#define HAVE_INTTYPES_H 1
#define HAVE_LINUX_HDREG_H 1
#define HAVE_LIMITS_H 1
#define HAVE_LOCALE_H 1
#define HAVE_MBSINIT 1
#define HAVE_REALPATH 1
#define HAVE_STDARG_H 1
#define HAVE_STDDEF_H 1
#define HAVE_STDINT_H 1
#define HAVE_STDIO_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_STRSEP 1
#define HAVE_SYS_PARAM_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_SYSMACROS_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_TIME_H 1
#define HAVE_UNISTD_H 1
#define HAVE_WCHAR_H 1
#define MAJOR_IN_SYSMACROS 1

/* No device paths, so no /etc/mtab or block-device ioctls. */
#define NO_NTFS_DEVICE_DEFAULT_IO_OPS 1

/*
 * Bionic declares ffs() in <strings.h> only, and drops the pre-POSIX S_IEXEC /
 * S_IWRITE aliases that security.c uses. Supplied here rather than by patching
 * vendored files, so an upstream pull does not have to carry them.
 */
#include <strings.h>
#include <sys/stat.h>
#ifndef S_IEXEC
#define S_IEXEC S_IXUSR
#endif
#ifndef S_IWRITE
#define S_IWRITE S_IWUSR
#endif
#ifndef S_IREAD
#define S_IREAD S_IRUSR
#endif

#endif
