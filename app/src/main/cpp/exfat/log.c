/*
	log.c (02.09.09)
	exFAT file system implementation library.

	Free exFAT implementation.
	Copyright (C) 2010-2023  Andrew Nayenko

	This program is free software; you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 2 of the License, or
	(at your option) any later version.

	This program is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
	GNU General Public License for more details.

	You should have received a copy of the GNU General Public License along
	with this program; if not, write to the Free Software Foundation, Inc.,
	51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
*/

#include "exfat.h"
#include <stdarg.h>
#ifdef __ANDROID__
#include <android/log.h>
#else
#include <syslog.h>
#endif
#include <unistd.h>

int exfat_errors;

#include <pthread.h>
#include <string.h>
/*
 * LOCAL PATCH (docs/VENDOR_FIXES.md E3): keep errors and warnings for the app's log.
 * They went to logcat only, which a user's report never carries: issue #98's read-write
 * mount failed as "Failed to mount exFAT: null", with libexfat's reason left behind on
 * the phone. The first messages are kept (the first error is the cause); later ones are
 * counted. exfat_take_messages() hands them over and empties the buffer.
 */
static pthread_mutex_t otg_msgs_lock = PTHREAD_MUTEX_INITIALIZER;
static char otg_msgs[2048];
static size_t otg_msgs_len;
static unsigned otg_msgs_dropped;

static void otg_capture(const char* prefix, const char* format, va_list ap)
{
	char line[300];
	int n = snprintf(line, sizeof(line), "%s", prefix);
	if (n < 0)
		return;
	vsnprintf(line + n, sizeof(line) - n, format, ap);
	pthread_mutex_lock(&otg_msgs_lock);
	size_t len = strlen(line);
	if (otg_msgs_len + len + 2 <= sizeof(otg_msgs))
	{
		memcpy(otg_msgs + otg_msgs_len, line, len);
		otg_msgs_len += len;
		otg_msgs[otg_msgs_len++] = '\n';
		otg_msgs[otg_msgs_len] = '\0';
	}
	else
		otg_msgs_dropped++;
	pthread_mutex_unlock(&otg_msgs_lock);
}

size_t exfat_take_messages(char* out, size_t size)
{
	pthread_mutex_lock(&otg_msgs_lock);
	size_t n = 0;
	if (size > 0)
	{
		n = snprintf(out, size, "%s", otg_msgs_len ? otg_msgs : "");
		if (otg_msgs_dropped && n < size)
			n += snprintf(out + n, size - n, "(%u more not kept)\n", otg_msgs_dropped);
		if (n >= size)
			n = size - 1;
	}
	otg_msgs_len = 0;
	otg_msgs[0] = '\0';
	otg_msgs_dropped = 0;
	pthread_mutex_unlock(&otg_msgs_lock);
	return n;
}

/*
 * This message means an internal bug in exFAT implementation.
 */
void exfat_bug(const char* format, ...)
{
	va_list ap, aq;

	va_start(ap, format);
	va_copy(aq, ap);

	fflush(stdout);
	fputs("BUG: ", stderr);
	vfprintf(stderr, format, ap);
	va_end(ap);
	fputs(".\n", stderr);

#ifdef __ANDROID__
	__android_log_vprint(ANDROID_LOG_FATAL, PACKAGE, format, aq);
#else
	if (!isatty(STDERR_FILENO))
		vsyslog(LOG_CRIT, format, aq);
#endif
	va_end(aq);

	abort();
}

/*
 * This message means an error in exFAT file system.
 */
void exfat_error(const char* format, ...)
{
	va_list ap, aq;

	exfat_errors++;
	va_start(ap, format);
	va_copy(aq, ap);
	{
		va_list ac;
		va_copy(ac, ap);
		otg_capture("ERROR: ", format, ac); /* LOCAL PATCH E3 */
		va_end(ac);
	}

	fflush(stdout);
	fputs("ERROR: ", stderr);
	vfprintf(stderr, format, ap);
	va_end(ap);
	fputs(".\n", stderr);

#ifdef __ANDROID__
	__android_log_vprint(ANDROID_LOG_ERROR, "ExFatNative", format, aq);
#else
	if (!isatty(STDERR_FILENO))
		vsyslog(LOG_ERR, format, aq);
#endif
	va_end(aq);
}

/*
 * This message means that there is something unexpected in exFAT file system
 * that can be a potential problem.
 */
void exfat_warn(const char* format, ...)
{
	va_list ap, aq;

	va_start(ap, format);
	va_copy(aq, ap);
	{
		va_list ac;
		va_copy(ac, ap);
		otg_capture("WARN: ", format, ac); /* LOCAL PATCH E3 */
		va_end(ac);
	}

	fflush(stdout);
	fputs("WARN: ", stderr);
	vfprintf(stderr, format, ap);
	va_end(ap);
	fputs(".\n", stderr);

#ifdef __ANDROID__
	__android_log_vprint(ANDROID_LOG_WARN, PACKAGE, format, aq);
#else
	if (!isatty(STDERR_FILENO))
		vsyslog(LOG_WARNING, format, aq);
#endif
	va_end(aq);
}

/*
 * Just debug message. Disabled by default.
 */
void exfat_debug(const char* format, ...)
{
	va_list ap, aq;

	va_start(ap, format);
	va_copy(aq, ap);

	fflush(stdout);
	fputs("DEBUG: ", stderr);
	vfprintf(stderr, format, ap);
	va_end(ap);
	fputs(".\n", stderr);

#ifdef __ANDROID__
	__android_log_vprint(ANDROID_LOG_DEBUG, PACKAGE, format, aq);
#else
	if (!isatty(STDERR_FILENO))
		vsyslog(LOG_DEBUG, format, aq);
#endif
	va_end(aq);
}
