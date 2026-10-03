/*
 * File functions
 *
 * Copyright (C) 2008-2026, Joachim Metz <joachim.metz@gmail.com>
 *
 * Refer to AUTHORS for acknowledgements.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#include <common.h>
#include <memory.h>
#include <narrow_string.h>
#include <system_string.h>
#include <types.h>
#include <wide_string.h>

#if defined( HAVE_SYS_STAT_H )
#include <sys/stat.h>
#endif

#if defined( HAVE_ERRNO_H )
#include <errno.h>
#endif

#if defined( HAVE_FCNTL_H )
#include <fcntl.h>
#endif

#if defined( WINAPI )
#include <io.h>
#endif

#if defined( WINAPI ) && !defined( __CYGWIN__ )
#include <share.h>
#endif

#if defined( HAVE_SYS_IOCTL_H )
#include <sys/ioctl.h>
#endif

#if defined( WINAPI )
#include <winioctl.h>

#elif defined( HAVE_CYGWIN_FS_H )
#include <cygwin/fs.h>

#elif defined( HAVE_LINUX_FS_H )
/* Required for Linux platforms that use a sizeof( u64 )
 * in linux/fs.h but have no typedef of it
 */
#if !defined( HAVE_U64 )
typedef size_t u64;
#endif

#include <linux/fs.h>

#else

#if defined( HAVE_SYS_DISK_H )
#include <sys/disk.h>
#endif

#if defined( HAVE_SYS_DISKLABEL_H )
#include <sys/disklabel.h>
#endif

#endif

#if defined( HAVE_UNISTD_H )
#include <unistd.h>
#endif

#include "libcfile_definitions.h"
#include "libcfile_file.h"
#include "libcfile_libcerror.h"
#include "libcfile_libcnotify.h"
#include "libcfile_system_string.h"
#include "libcfile_types.h"
#include "libcfile_winapi.h"

#if defined( HAVE_IOCTL ) || defined( WINAPI )
#define LIBCFILE_HAVE_IO_CONTROL	1
#endif

/* Creates a file
 * Make sure the value file is referencing, is set to NULL
 * Returns 1 if successful or -1 on error
 */
int libcfile_file_initialize(
     libcfile_file_t **file,
     libcerror_error_t **error )
{
	libcfile_internal_file_t *internal_file = NULL;
	static char *function                   = "libcfile_file_initialize";

	if( file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
	if( *file != NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_ALREADY_SET,
		 "%s: invalid file value already set.",
		 function );

		return( -1 );
	}
	internal_file = memory_allocate_structure(
		         libcfile_internal_file_t );

	if( internal_file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_INSUFFICIENT,
		 "%s: unable to create file.",
		 function );

		goto on_error;
	}
	if( memory_set(
	     internal_file,
	     0,
	     sizeof( libcfile_internal_file_t ) ) == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_SET_FAILED,
		 "%s: unable to clear file.",
		 function );

		goto on_error;
	}
#if defined( WINAPI )
	internal_file->handle = INVALID_HANDLE_VALUE;
#else
	internal_file->descriptor = -1;
#endif
	*file = (libcfile_file_t *) internal_file;

	return( 1 );

on_error:
	if( internal_file != NULL )
	{
		memory_free(
		 internal_file );
	}
	return( -1 );
}

/* Frees a file
 * Returns 1 if successful or -1 on error
 */
int libcfile_file_free(
     libcfile_file_t **file,
     libcerror_error_t **error )
{
	libcfile_internal_file_t *internal_file = NULL;
	static char *function                   = "libcfile_file_free";
	int result                              = 1;

	if( file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
	if( *file != NULL )
	{
		internal_file = (libcfile_internal_file_t *) *file;

#if defined( WINAPI )
		if( internal_file->handle != INVALID_HANDLE_VALUE )
#else
		if( internal_file->descriptor != -1 )
#endif
		{
			if( libcfile_file_close(
			     *file,
			     error ) != 0 )
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_IO,
				 LIBCERROR_IO_ERROR_CLOSE_FAILED,
				 "%s: unable to close file.",
				 function );

				result = -1;
			}
		}
		*file = NULL;

		memory_free(
		 internal_file );
	}
	return( result );
}

/* Opens a file
 * Returns 1 if successful or -1 on error
 */
int libcfile_file_open(
     libcfile_file_t *file,
     const char *filename,
     int access_flags,
     libcerror_error_t **error )
{
	static char *function = "libcfile_file_open";
	uint32_t error_code   = 0;

	if( libcfile_file_open_with_error_code(
	     file,
	     filename,
	     access_flags,
	     &error_code,
	     error ) != 1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_IO,
		 LIBCERROR_IO_ERROR_OPEN_FAILED,
		 "%s: unable to open file.",
		 function );

		return( -1 );
	}
	return( 1 );
}

#if defined( WINAPI )

#if ( WINVER >= 0x0600 ) && ( WINVER < 0x0602 )

#define FileAlignmentInfo 0x11

typedef struct _FILE_ALIGNMENT_INFO FILE_ALIGNMENT_INFO;

struct _FILE_ALIGNMENT_INFO {
	ULONG AlignmentRequirement;
};

#endif /* ( WINVER >= 0x0600 ) && ( WINVER < 0x0602 ) */

/* Opens a file
 * This function uses the WINAPI function for Windows XP (0x0501) or later,
 * or tries to dynamically call the function for Windows 2000 (0x0500) or earlier
 * Returns 1 if successful or -1 on error
 */
int libcfile_file_open_with_error_code(
     libcfile_file_t *file,
     const char *filename,
     int access_flags,
     uint32_t *error_code,
     libcerror_error_t **error )
{
	libcfile_internal_file_t *internal_file = NULL;
	static char *function                   = "libcfile_file_open_with_error_code";
	DWORD file_io_access_flags              = 0;
	DWORD file_io_creation_flags            = 0;
	DWORD file_io_shared_flags              = 0;
	DWORD flags_and_attributes              = 0;
	size_t filename_length                  = 0;
	ssize_t read_count                      = 0;

	if( file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
	internal_file = (libcfile_internal_file_t *) file;

	if( internal_file->handle != INVALID_HANDLE_VALUE )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_ALREADY_SET,
		 "%s: invalid file - handle value already set.",
		 function );

		return( -1 );
	}
	if( filename == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid filename.",
		 function );

		return( -1 );
	}
	if( ( ( access_flags & LIBCFILE_ACCESS_FLAG_READ ) != 0 )
	 && ( ( access_flags & LIBCFILE_ACCESS_FLAG_WRITE ) != 0 ) )
	{
		file_io_access_flags   = GENERIC_WRITE | GENERIC_READ;
		file_io_creation_flags = OPEN_ALWAYS;
		file_io_shared_flags   = FILE_SHARE_READ;
	}
	else if( ( access_flags & LIBCFILE_ACCESS_FLAG_READ ) != 0 )
	{
		file_io_access_flags   = GENERIC_READ;
		file_io_creation_flags = OPEN_EXISTING;

		/* FILE_SHARE_WRITE is set to allow reading files that are
		 * currently being written FILE_SHARE_READ alone does not suffice
		 */
		file_io_shared_flags = FILE_SHARE_READ | FILE_SHARE_WRITE;
	}
	else if( ( access_flags & LIBCFILE_ACCESS_FLAG_WRITE ) != 0 )
	{
		file_io_access_flags   = GENERIC_WRITE;
		file_io_creation_flags = OPEN_ALWAYS;
		file_io_shared_flags   = FILE_SHARE_READ;
	}
	else
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_UNSUPPORTED_VALUE,
		 "%s: unsupported access flags: 0x%02x.",
		 function,
		 access_flags );

		return( -1 );
	}
	if( ( ( access_flags & LIBCFILE_ACCESS_FLAG_WRITE ) != 0 )
	 && ( ( access_flags & LIBCFILE_ACCESS_FLAG_TRUNCATE ) != 0 ) )
	{
		file_io_creation_flags = CREATE_ALWAYS;
	}
	if( error_code == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid error code.",
		 function );

		return( -1 );
	}
	filename_length = narrow_string_length(
	                   filename );

	if( filename_length > 4 )
	{
		if( ( filename[ 0 ] == '\\' )
		 && ( filename[ 1 ] == '\\' )
		 && ( filename[ 2 ] == '.' )
		 && ( filename[ 3 ] == '\\' ) )
		{
			/* Ignore \\.\F:\ which is an alternative notation for F:
			 */
			if( ( filename_length < 7 )
			 || ( filename[ 5 ] != ':' )
			 || ( filename[ 6 ] != '\\' ) )
			{
				internal_file->is_device_filename  = 1;
				internal_file->use_asynchronous_io = 1;
			}
		}
	}
	flags_and_attributes = FILE_ATTRIBUTE_NORMAL;

	if( internal_file->use_asynchronous_io != 0 )
	{
		flags_and_attributes |= FILE_FLAG_OVERLAPPED;
	}
#if ( WINVER <= 0x0500 )
	internal_file->handle = libcfile_CreateFileA(
	                         (LPCSTR) filename,
	                         file_io_access_flags,
	                         file_io_shared_flags,
	                         NULL,
	                         file_io_creation_flags,
	                         flags_and_attributes,
	                         NULL );
#else
	internal_file->handle = CreateFileA(
	                         (LPCSTR) filename,
	                         file_io_access_flags,
	                         file_io_shared_flags,
	                         NULL,
	                         file_io_creation_flags,
	                         flags_and_attributes,
	                         NULL );
#endif
	if( internal_file->handle == INVALID_HANDLE_VALUE )
	{
		*error_code = (uint32_t) GetLastError();

		switch( *error_code )
		{
			case ERROR_ACCESS_DENIED:
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_IO,
				 LIBCERROR_IO_ERROR_ACCESS_DENIED,
				 "%s: access denied to file: %" PRIs_SYSTEM ".",
				 function,
				 filename );

				break;

			case ERROR_FILE_NOT_FOUND:
			case ERROR_PATH_NOT_FOUND:
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_IO,
				 LIBCERROR_IO_ERROR_INVALID_RESOURCE,
				 "%s: no such file: %" PRIs_SYSTEM ".",
				 function,
				 filename );

				break;

			default:
				libcerror_system_set_error(
				 error,
				 LIBCERROR_ERROR_DOMAIN_IO,
				 LIBCERROR_IO_ERROR_OPEN_FAILED,
				 *error_code,
				 "%s: unable to open file: %" PRIs_SYSTEM ".",
				 function,
				 filename );

				break;
		}
		return( -1 );
	}
#if defined( LIBCFILE_HAVE_IO_CONTROL )
	if( internal_file->is_device_filename != 0 )
	{
		read_count = libcfile_internal_file_io_control_read_with_error_code(
		              internal_file,
		              FSCTL_ALLOW_EXTENDED_DASD_IO,
		              NULL,
		              0,
		              NULL,
		              0,
		              error_code,
		              error );

		if( read_count == -1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_IO,
			 LIBCERROR_IO_ERROR_IOCTL_FAILED,
			 "%s: unable to query device for: FSCTL_ALLOW_EXTENDED_DASD_IO.",
			 function );

#if defined( HAVE_DEBUG_OUTPUT )
			if( libcnotify_verbose != 0 )
			{
				if( ( error != NULL )
				 && ( *error != NULL ) )
				{
					libcnotify_print_error_backtrace(
					 *error );
				}
			}
#endif
			libcerror_error_free(
			 error );
		}
		if( libcfile_internal_file_determine_block_size(
		     internal_file,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
			 "%s: unable to determine block size.",
			 function );

			return( -1 );
		}
	}
#endif /* defined( LIBCFILE_HAVE_IO_CONTROL ) */

	if( libcfile_internal_file_get_size(
	     internal_file,
	     &( internal_file->size ),
	     error ) != 1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
		 "%s: unable to retrieve size.",
		 function );

		return( -1 );
	}
	internal_file->access_flags   = access_flags;
	internal_file->current_offset = 0;

	return( 1 );
}

#elif defined( HAVE_OPEN )

/* Opens a file
 * This function uses the POSIX open function or equivalent
 * Returns 1 if successful or -1 on error
 */
int libcfile_file_open_with_error_code(
     libcfile_file_t *file,
     const char *filename,
     int access_flags,
     uint32_t *error_code,
     libcerror_error_t **error )
{
#if defined( __FreeBSD__ ) || defined( __NetBSD__ ) || defined( __OpenBSD__ )
	struct stat file_statistics;
#endif
	libcfile_internal_file_t *internal_file = NULL;
	static char *function                   = "libcfile_file_open_with_error_code";
	int file_io_flags                       = 0;

	if( file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
	internal_file = (libcfile_internal_file_t *) file;

	if( internal_file->descriptor != -1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_ALREADY_SET,
		 "%s: invalid file - descriptor value already set.",
		 function );

		return( -1 );
	}
	if( filename == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid filename.",
		 function );

		return( -1 );
	}
	if( ( ( access_flags & LIBCFILE_ACCESS_FLAG_READ ) != 0 )
	 && ( ( access_flags & LIBCFILE_ACCESS_FLAG_WRITE ) != 0 ) )
	{
		file_io_flags = O_RDWR | O_CREAT;
	}
	else if( ( access_flags & LIBCFILE_ACCESS_FLAG_READ ) != 0 )
	{
		file_io_flags = O_RDONLY;
	}
	else if( ( access_flags & LIBCFILE_ACCESS_FLAG_WRITE ) != 0 )
	{
		file_io_flags = O_WRONLY | O_CREAT;
	}
	else
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_UNSUPPORTED_VALUE,
		 "%s: unsupported access flags: 0x%02x.",
		 function,
		 access_flags );

		return( -1 );
	}
	if( ( ( access_flags & LIBCFILE_ACCESS_FLAG_WRITE ) != 0 )
	 && ( ( access_flags & LIBCFILE_ACCESS_FLAG_TRUNCATE ) != 0 ) )
	{
		file_io_flags |= O_TRUNC;
	}
	if( error_code == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid error code.",
		 function );

		return( -1 );
	}
#if defined( O_CLOEXEC )
	/* Prevent the file descriptor to remain open across an execve
	 */
	file_io_flags |= O_CLOEXEC;
#endif
#if defined( __MINGW32__ )
	/* Ensure the file descriptor is opened in binary mode
	 */
	file_io_flags |= O_BINARY;
#endif
	internal_file->descriptor = open(
	                             filename,
	                             file_io_flags,
	                             0644 );

	if( internal_file->descriptor == -1 )
	{
		*error_code = (uint32_t) errno;

		switch( *error_code )
		{
			case EACCES:
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_IO,
				 LIBCERROR_IO_ERROR_ACCESS_DENIED,
				 "%s: access denied to file: %" PRIs_SYSTEM ".",
				 function,
				 filename );

				break;

			case ENOENT:
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_IO,
				 LIBCERROR_IO_ERROR_INVALID_RESOURCE,
				 "%s: no such file: %" PRIs_SYSTEM ".",
				 function,
				 filename );

				break;

			default:
				libcerror_system_set_error(
				 error,
				 LIBCERROR_ERROR_DOMAIN_IO,
				 LIBCERROR_IO_ERROR_OPEN_FAILED,
				 *error_code,
				 "%s: unable to open file: %" PRIs_SYSTEM ".",
				 function,
				 filename );

				break;
		}
		return( -1 );
	}
#if defined( __FreeBSD__ ) || defined( __NetBSD__ ) || defined( __OpenBSD__ )
	if( fstat(
	     internal_file->descriptor,
	     &file_statistics ) != 0 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
		 "%s: unable to retrieve file statistics.",
		 function );

		return( -1 );
	}
	if( S_ISBLK( file_statistics.st_mode ) )
	{
		if( libcfile_internal_file_determine_block_size(
		     internal_file,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
			 "%s: unable to determine block size.",
			 function );

			return( -1 );
		}
	}
#endif /* defined( __FreeBSD__ ) || defined( __NetBSD__ ) || defined( __OpenBSD__ ) */

	if( libcfile_internal_file_get_size(
	     internal_file,
	     &( internal_file->size ),
	     error ) != 1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
		 "%s: unable to retrieve size.",
		 function );

		return( -1 );
	}
	internal_file->access_flags   = access_flags;
	internal_file->current_offset = 0;

	return( 1 );
}

#else
#error Missing file open function
#endif

#if defined( HAVE_WIDE_CHARACTER_TYPE )

/* Opens a file
 * Returns 1 if successful or -1 on error
 */
int libcfile_file_open_wide(
     libcfile_file_t *file,
     const wchar_t *filename,
     int access_flags,
     libcerror_error_t **error )
{
	static char *function = "libcfile_file_open_wide";
	uint32_t error_code   = 0;

	if( libcfile_file_open_wide_with_error_code(
	     file,
	     filename,
	     access_flags,
	     &error_code,
	     error ) != 1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_IO,
		 LIBCERROR_IO_ERROR_OPEN_FAILED,
		 "%s: unable to open file.",
		 function );

		return( -1 );
	}
	return( 1 );
}

#if defined( WINAPI )

/* Opens a file
 * This function uses the WINAPI function for Windows XP (0x0501) or later
 * or tries to dynamically call the function for Windows 2000 (0x0500) or earlier
 * Returns 1 if successful or -1 on error
 */
int libcfile_file_open_wide_with_error_code(
     libcfile_file_t *file,
     const wchar_t *filename,
     int access_flags,
     uint32_t *error_code,
     libcerror_error_t **error )
{
	libcfile_internal_file_t *internal_file = NULL;
	static char *function                   = "libcfile_file_open_wide_with_error_code";
	DWORD file_io_access_flags              = 0;
	DWORD file_io_creation_flags            = 0;
	DWORD file_io_shared_flags              = 0;
	DWORD flags_and_attributes              = 0;
	size_t filename_length                  = 0;
	ssize_t read_count                      = 0;

	if( file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
	internal_file = (libcfile_internal_file_t *) file;

	if( internal_file->handle != INVALID_HANDLE_VALUE )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_ALREADY_SET,
		 "%s: invalid file - handle value already set.",
		 function );

		return( -1 );
	}
	if( filename == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid filename.",
		 function );

		return( -1 );
	}
	if( ( ( access_flags & LIBCFILE_ACCESS_FLAG_READ ) != 0 )
	 && ( ( access_flags & LIBCFILE_ACCESS_FLAG_WRITE ) != 0 ) )
	{
		file_io_access_flags   = GENERIC_WRITE | GENERIC_READ;
		file_io_creation_flags = OPEN_ALWAYS;
		file_io_shared_flags   = FILE_SHARE_READ;
	}
	else if( ( access_flags & LIBCFILE_ACCESS_FLAG_READ ) != 0 )
	{
		file_io_access_flags   = GENERIC_READ;
		file_io_creation_flags = OPEN_EXISTING;

		/* FILE_SHARE_WRITE is set to allow reading files that are
		 * currently being written FILE_SHARE_READ alone does not suffice
		 */
		file_io_shared_flags = FILE_SHARE_READ | FILE_SHARE_WRITE;
	}
	else if( ( access_flags & LIBCFILE_ACCESS_FLAG_WRITE ) != 0 )
	{
		file_io_access_flags   = GENERIC_WRITE;
		file_io_creation_flags = OPEN_ALWAYS;
		file_io_shared_flags   = FILE_SHARE_READ;
	}
	else
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_UNSUPPORTED_VALUE,
		 "%s: unsupported access flags: 0x%02x.",
		 function,
		 access_flags );

		return( -1 );
	}
	if( ( ( access_flags & LIBCFILE_ACCESS_FLAG_WRITE ) != 0 )
	 && ( ( access_flags & LIBCFILE_ACCESS_FLAG_TRUNCATE ) != 0 ) )
	{
		file_io_creation_flags = CREATE_ALWAYS;
	}
	if( error_code == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid error code.",
		 function );

		return( -1 );
	}
	filename_length = wide_string_length(
	                   filename );

	if( filename_length > 4 )
	{
		if( ( filename[ 0 ] == (wchar_t) '\\' )
		 && ( filename[ 1 ] == (wchar_t) '\\' )
		 && ( filename[ 2 ] == (wchar_t) '.' )
		 && ( filename[ 3 ] == (wchar_t) '\\' ) )
		{
			/* Ignore \\.\F:\ which is an alternative notation for F:
			 */
			if( ( filename_length < 7 )
			 || ( filename[ 5 ] != (wchar_t) ':' )
			 || ( filename[ 6 ] != (wchar_t) '\\' ) )
			{
				internal_file->is_device_filename  = 1;
				internal_file->use_asynchronous_io = 1;
			}
		}
	}
	flags_and_attributes = FILE_ATTRIBUTE_NORMAL;

	if( internal_file->use_asynchronous_io != 0 )
	{
		flags_and_attributes |= FILE_FLAG_OVERLAPPED;
	}
#if ( WINVER <= 0x0500 )
	internal_file->handle = libcfile_CreateFileW(
	                         (LPCWSTR) filename,
	                         file_io_access_flags,
	                         file_io_shared_flags,
	                         NULL,
	                         file_io_creation_flags,
	                         flags_and_attributes,
	                         NULL );
#else
	internal_file->handle = CreateFileW(
	                         (LPCWSTR) filename,
	                         file_io_access_flags,
	                         file_io_shared_flags,
	                         NULL,
	                         file_io_creation_flags,
	                         flags_and_attributes,
	                         NULL );
#endif
	if( internal_file->handle == INVALID_HANDLE_VALUE )
	{
		*error_code = (uint32_t) GetLastError();

		switch( *error_code )
		{
			case ERROR_ACCESS_DENIED:
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_IO,
				 LIBCERROR_IO_ERROR_ACCESS_DENIED,
				 "%s: access denied to file: %" PRIs_SYSTEM ".",
				 function,
				 filename );

				break;

			case ERROR_FILE_NOT_FOUND:
			case ERROR_PATH_NOT_FOUND:
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_IO,
				 LIBCERROR_IO_ERROR_INVALID_RESOURCE,
				 "%s: no such file: %" PRIs_SYSTEM ".",
				 function,
				 filename );

				break;

			default:
				libcerror_system_set_error(
				 error,
				 LIBCERROR_ERROR_DOMAIN_IO,
				 LIBCERROR_IO_ERROR_OPEN_FAILED,
				 *error_code,
				 "%s: unable to open file: %" PRIs_SYSTEM ".",
				 function,
				 filename );

				break;
		}
		return( -1 );
	}
#if defined( LIBCFILE_HAVE_IO_CONTROL )
	if( internal_file->is_device_filename != 0 )
	{
		read_count = libcfile_internal_file_io_control_read_with_error_code(
		              internal_file,
		              FSCTL_ALLOW_EXTENDED_DASD_IO,
		              NULL,
		              0,
		              NULL,
		              0,
		              error_code,
		              error );

		if( read_count == -1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_IO,
			 LIBCERROR_IO_ERROR_IOCTL_FAILED,
			 "%s: unable to query device for: FSCTL_ALLOW_EXTENDED_DASD_IO.",
			 function );

#if defined( HAVE_DEBUG_OUTPUT )
			if( libcnotify_verbose != 0 )
			{
				if( ( error != NULL )
				 && ( *error != NULL ) )
				{
					libcnotify_print_error_backtrace(
					 *error );
				}
			}
#endif
			libcerror_error_free(
			 error );
		}
		if( libcfile_internal_file_determine_block_size(
		     internal_file,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
			 "%s: unable to determine block size.",
			 function );

			return( -1 );
		}
	}
#endif /* defined( LIBCFILE_HAVE_IO_CONTROL ) */

	if( libcfile_internal_file_get_size(
	     internal_file,
	     &( internal_file->size ),
	     error ) != 1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
		 "%s: unable to retrieve size.",
		 function );

		return( -1 );
	}
	internal_file->access_flags   = access_flags;
	internal_file->current_offset = 0;

	return( 1 );
}

#elif defined( HAVE_OPEN )

/* Opens a file
 * This function uses the POSIX open function or equivalent
 * Returns 1 if successful or -1 on error
 */
int libcfile_file_open_wide_with_error_code(
     libcfile_file_t *file,
     const wchar_t *filename,
     int access_flags,
     uint32_t *error_code,
     libcerror_error_t **error )
{
#if defined( __FreeBSD__ ) || defined( __NetBSD__ ) || defined( __OpenBSD__ )
	struct stat file_statistics;
#endif
	libcfile_internal_file_t *internal_file = NULL;
	static char *function                   = "libcfile_file_open_wide_with_error_code";
	char *narrow_filename                   = NULL;
	size_t filename_size                    = 0;
	size_t narrow_filename_size             = 0;
	int file_io_flags                       = 0;

	if( file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
	internal_file = (libcfile_internal_file_t *) file;

	if( internal_file->descriptor != -1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_ALREADY_SET,
		 "%s: invalid file - descriptor value already set.",
		 function );

		return( -1 );
	}
	if( filename == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid filename.",
		 function );

		return( -1 );
	}
	if( ( ( access_flags & LIBCFILE_ACCESS_FLAG_READ ) != 0 )
	 && ( ( access_flags & LIBCFILE_ACCESS_FLAG_WRITE ) != 0 ) )
	{
		file_io_flags = O_RDWR | O_CREAT;
	}
	else if( ( access_flags & LIBCFILE_ACCESS_FLAG_READ ) != 0 )
	{
		file_io_flags = O_RDONLY;
	}
	else if( ( access_flags & LIBCFILE_ACCESS_FLAG_WRITE ) != 0 )
	{
		file_io_flags = O_WRONLY | O_CREAT;
	}
	else
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_UNSUPPORTED_VALUE,
		 "%s: unsupported access flags: 0x%02x.",
		 function,
		 access_flags );

		return( -1 );
	}
	if( ( ( access_flags & LIBCFILE_ACCESS_FLAG_WRITE ) != 0 )
	 && ( ( access_flags & LIBCFILE_ACCESS_FLAG_TRUNCATE ) != 0 ) )
	{
		file_io_flags |= O_TRUNC;
	}
	if( error_code == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid error code.",
		 function );

		return( -1 );
	}
	filename_size = 1 + wide_string_length(
	                     filename );

	if( libcfile_system_string_size_from_wide_string(
	     filename,
	     filename_size,
	     &narrow_filename_size,
	     error ) != 1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_CONVERSION,
		 LIBCERROR_CONVERSION_ERROR_GENERIC,
		 "%s: unable to determine narrow character filename size.",
		 function );

		goto on_error;
	}
	narrow_filename = narrow_string_allocate(
	                   narrow_filename_size );

	if( narrow_filename == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_INSUFFICIENT,
		 "%s: unable to create narrow character filename.",
		 function );

		goto on_error;
	}
	if( libcfile_system_string_copy_from_wide_string(
	     narrow_filename,
	     narrow_filename_size,
	     filename,
	     filename_size,
	     error ) != 1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_CONVERSION,
		 LIBCERROR_CONVERSION_ERROR_GENERIC,
		 "%s: unable to set narrow character filename.",
		 function );

		goto on_error;
	}
#if defined( O_CLOEXEC )
	/* Prevent the file descriptor to remain open across an execve
	 */
	file_io_flags |= O_CLOEXEC;
#endif
#if defined( __MINGW32__ )
	/* Ensure the file descriptor is opened in binary mode
	 */
	file_io_flags |= O_BINARY;
#endif
	internal_file->descriptor = open(
	                             narrow_filename,
	                             file_io_flags,
	                             0644 );

	memory_free(
	 narrow_filename );

	narrow_filename = NULL;

	if( internal_file->descriptor == -1 )
	{
		*error_code = (uint32_t) errno;

		switch( *error_code )
		{
			case EACCES:
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_IO,
				 LIBCERROR_IO_ERROR_ACCESS_DENIED,
				 "%s: access denied to file: %" PRIs_SYSTEM ".",
				 function,
				 filename );

				break;

			case ENOENT:
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_IO,
				 LIBCERROR_IO_ERROR_INVALID_RESOURCE,
				 "%s: no such file: %" PRIs_SYSTEM ".",
				 function,
				 filename );

				break;

			default:
				libcerror_system_set_error(
				 error,
				 LIBCERROR_ERROR_DOMAIN_IO,
				 LIBCERROR_IO_ERROR_OPEN_FAILED,
				 *error_code,
				 "%s: unable to open file: %" PRIs_SYSTEM ".",
				 function,
				 filename );

				break;
		}
		goto on_error;
	}
#if defined( __FreeBSD__ ) || defined( __NetBSD__ ) || defined( __OpenBSD__ )
	if( fstat(
	     internal_file->descriptor,
	     &file_statistics ) != 0 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
		 "%s: unable to retrieve file statistics.",
		 function );

		return( -1 );
	}
	if( S_ISBLK( file_statistics.st_mode ) )
	{
		if( libcfile_internal_file_determine_block_size(
		     internal_file,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
			 "%s: unable to determine block size.",
			 function );

			return( -1 );
		}
	}
#endif /* defined( __FreeBSD__ ) || defined( __NetBSD__ ) || defined( __OpenBSD__ ) */

	if( libcfile_internal_file_get_size(
	     internal_file,
	     &( internal_file->size ),
	     error ) != 1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
		 "%s: unable to retrieve size.",
		 function );

		goto on_error;
	}
	return( 1 );

on_error:
	if( narrow_filename != NULL )
	{
		memory_free(
		 narrow_filename );
	}
	return( -1 );
}

#else
#error Missing file open wide function
#endif

#endif /* defined( HAVE_WIDE_CHARACTER_TYPE ) */

#if defined( WINAPI )

/* Closes the file
 * This function uses the WINAPI function for Windows 2000 (0x0500) or later
 * or tries to dynamically call the function for Windows 2000 (0x0500) or earlier
 * Returns 0 if successful or -1 on error
 */
int libcfile_file_close(
     libcfile_file_t *file,
     libcerror_error_t **error )
{
	libcfile_internal_file_t *internal_file = NULL;
	static char *function                   = "libcfile_file_close";
	DWORD error_code                        = 0;
	BOOL result                             = FALSE;

	if( file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
	internal_file = (libcfile_internal_file_t *) file;

	if( internal_file->handle != INVALID_HANDLE_VALUE )
	{
#if ( WINVER <= 0x0500 )
		result = libcfile_CloseHandle(
		          internal_file->handle );
#else
		result = CloseHandle(
		          internal_file->handle );
#endif
		if( result == 0 )
		{
			error_code = GetLastError();

			libcerror_system_set_error(
			 error,
			 LIBCERROR_ERROR_DOMAIN_IO,
			 LIBCERROR_IO_ERROR_CLOSE_FAILED,
			 error_code,
			 "%s: unable to close file.",
			 function );

			return( -1 );
		}
		internal_file->handle              = INVALID_HANDLE_VALUE;
		internal_file->is_device_filename  = 0;
		internal_file->use_asynchronous_io = 0;
		internal_file->access_flags        = 0;
		internal_file->size                = 0;
		internal_file->current_offset      = 0;
	}
	if( internal_file->block_data != NULL )
	{
		if( memory_set(
		     internal_file->block_data,
		     0,
		     internal_file->block_size ) == NULL )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_MEMORY,
			 LIBCERROR_MEMORY_ERROR_SET_FAILED,
			 "%s: unable to clear block data.",
			 function );

			return( -1 );
		}
	}
	return( 0 );
}

#elif defined( HAVE_CLOSE )

/* Closes the file
 * This function uses the POSIX close function or equivalent
 * Returns 0 if successful or -1 on error
 */
int libcfile_file_close(
     libcfile_file_t *file,
     libcerror_error_t **error )
{
	libcfile_internal_file_t *internal_file = NULL;
	static char *function                   = "libcfile_file_close";

	if( file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
	internal_file = (libcfile_internal_file_t *) file;

	if( internal_file->descriptor != -1 )
	{
		if( close(
		     internal_file->descriptor ) != 0 )
		{
			libcerror_system_set_error(
			 error,
			 LIBCERROR_ERROR_DOMAIN_IO,
			 LIBCERROR_IO_ERROR_CLOSE_FAILED,
			 errno,
			 "%s: unable to close file.",
			 function );

			return( -1 );
		}
		internal_file->descriptor     = -1;
		internal_file->access_flags   = 0;
		internal_file->size           = 0;
		internal_file->current_offset = 0;
	}
	if( internal_file->block_data != NULL )
	{
		if( memory_set(
		     internal_file->block_data,
		     0,
		     internal_file->block_size ) == NULL )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_MEMORY,
			 LIBCERROR_MEMORY_ERROR_SET_FAILED,
			 "%s: unable to clear block data.",
			 function );

			return( -1 );
		}
	}
	return( 0 );
}

#else
#error Missing file close function
#endif

/* Reads a buffer from the file
 * Returns the number of bytes read if successful, or -1 on error
 */
ssize_t libcfile_file_read_buffer(
         libcfile_file_t *file,
         uint8_t *buffer,
         size_t size,
         libcerror_error_t **error )
{
	static char *function = "libcfile_file_read_buffer";
	ssize_t read_count    = 0;
	uint32_t error_code   = 0;

	read_count = libcfile_file_read_buffer_with_error_code(
	              file,
	              buffer,
	              size,
	              &error_code,
	              error );

	if( read_count == -1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_IO,
		 LIBCERROR_IO_ERROR_READ_FAILED,
		 "%s: unable to read from file.",
		 function );

		return( -1 );
	}
	return( read_count );
}

#if defined( WINAPI )

/* Reads a buffer from the file
 * This is an internal function to wrap ReadFile in synchronous and asynchronous mode
 * the current_offset is only used in asynchronous mode.
 * Returns the number of bytes read if successful, or -1 on error
 */
ssize_t libcfile_internal_file_read_buffer_at_offset_with_error_code(
         libcfile_internal_file_t *internal_file,
         off64_t current_offset,
         uint8_t *buffer,
         size_t size,
         uint32_t *error_code,
         libcerror_error_t **error )
{
	OVERLAPPED overlapped_data;

	static char *function  = "libcfile_internal_file_read_buffer_at_offset_with_error_code";
	OVERLAPPED *overlapped = NULL;
	DWORD read_count       = 0;
	BOOL io_pending        = FALSE;
	BOOL result            = FALSE;

	if( internal_file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
	if( current_offset < 0 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid current offset value out of bounds.",
		 function );

		return( -1 );
	}
	if( buffer == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid buffer.",
		 function );

		return( -1 );
	}
#if ( UINT32_MAX < SSIZE_MAX )
	if( size > (size_t) UINT32_MAX )
#else
	if( size > (size_t) SSIZE_MAX )
#endif
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_EXCEEDS_MAXIMUM,
		 "%s: invalid size value exceeds maximum.",
		 function );

		return( -1 );
	}
	if( error_code == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid error code.",
		 function );

		return( -1 );
	}
	/* For Windows devices we need to use asynchronous IO here
	 * otherwise the ReadFile function can return ERROR_INVALID_PARAMETER
	 * if the device is read concurrently and the the block is too large
	 * to fill. Using smaller block sizes decreases the likelyhood but
	 * also impacts the performance.
	 */
	if( internal_file->use_asynchronous_io != 0 )
	{
		if( memory_set(
		     &overlapped_data,
		     0,
		     sizeof( OVERLAPPED ) ) == NULL )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_MEMORY,
			 LIBCERROR_MEMORY_ERROR_SET_FAILED,
			 "%s: unable to clear overlapped data.",
			 function );

			return( -1 );
		}
		overlapped = &overlapped_data;

		overlapped->Offset     = (DWORD) ( 0x0ffffffffUL & current_offset );
		overlapped->OffsetHigh = (DWORD) ( current_offset >> 32 );
	}
#if ( WINVER <= 0x0500 )
	result = libcfile_ReadFile(
		  internal_file->handle,
		  buffer,
		  (DWORD) size,
		  &read_count,
		  overlapped );
#else
	result = ReadFile(
		  internal_file->handle,
		  (VOID *) buffer,
		  (DWORD) size,
		  &read_count,
		  overlapped );
#endif
	if( result == 0 )
	{
		*error_code = (uint32_t) GetLastError();

		switch( *error_code )
		{
			case ERROR_HANDLE_EOF:
				break;

			case ERROR_IO_PENDING:
				io_pending = TRUE;
				break;

			default:
				libcerror_system_set_error(
				 error,
				 LIBCERROR_ERROR_DOMAIN_IO,
				 LIBCERROR_IO_ERROR_READ_FAILED,
				 *error_code,
				 "%s: unable to read from file.",
				 function );

				return( -1 );
		}
	}
	if( io_pending == TRUE )
	{
#if ( WINVER <= 0x0500 )
		result = libcfile_GetOverlappedResult(
			  internal_file->handle,
			  overlapped,
			  &read_count,
			  TRUE );
#else
		result = GetOverlappedResult(
			  internal_file->handle,
			  overlapped,
			  &read_count,
			  TRUE );
#endif
		if( result == 0 )
		{
			*error_code = (uint32_t) GetLastError();

			switch( *error_code )
			{
				case ERROR_HANDLE_EOF:
					break;

				default:
					libcerror_system_set_error(
					 error,
					 LIBCERROR_ERROR_DOMAIN_IO,
					 LIBCERROR_IO_ERROR_READ_FAILED,
					 *error_code,
					 "%s: unable to read from file - overlapped result.",
					 function );

					return( -1 );
			}
		}
	}
	return( (ssize_t) read_count );
}

/* Reads a buffer from the file
 * This function uses the WINAPI function for Windows XP (0x0501) or later
 * or tries to dynamically call the function for Windows 2000 (0x0500) or earlier
 * Returns the number of bytes read if successful, or -1 on error
 */
ssize_t libcfile_file_read_buffer_with_error_code(
         libcfile_file_t *file,
         uint8_t *buffer,
         size_t size,
         uint32_t *error_code,
         libcerror_error_t **error )
{
	libcfile_internal_file_t *internal_file = NULL;
	static char *function                   = "libcfile_file_read_buffer_with_error_code";
	size_t buffer_offset                    = 0;
	size_t read_size                        = 0;
	size_t read_size_remainder              = 0;
	ssize_t read_count                      = 0;
	BOOL result                             = FALSE;

	if( file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
	internal_file = (libcfile_internal_file_t *) file;

	if( internal_file->handle == INVALID_HANDLE_VALUE )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
		 "%s: invalid file - missing handle.",
		 function );

		return( -1 );
	}
	if( buffer == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid buffer.",
		 function );

		return( -1 );
	}
#if ( UINT32_MAX < SSIZE_MAX )
	if( size > (size_t) UINT32_MAX )
#else
	if( size > (size_t) SSIZE_MAX )
#endif
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_EXCEEDS_MAXIMUM,
		 "%s: invalid size value exceeds maximum.",
		 function );

		return( -1 );
	}
	if( error_code == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid error code.",
		 function );

		return( -1 );
	}
	if( internal_file->block_size != 0 )
	{
		if( internal_file->block_data == NULL )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
			 "%s: invalid file - missing block data.",
			 function );

			return( -1 );
		}
	}
	if( internal_file->current_offset < 0 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid file - current offset value out of bounds.",
		 function );

		return( -1 );
	}
	if( ( size == 0 )
	 || ( (size64_t) internal_file->current_offset > internal_file->size ) )
	{
		return( 0 );
	}
	if( ( (size64_t) internal_file->current_offset + size ) > internal_file->size )
	{
		size = (size_t) ( internal_file->size - internal_file->current_offset );
	}
	if( internal_file->block_size != 0 )
	{
		/* Read a block of data to align with the next block
		 */
		if( ( internal_file->block_data_offset > 0 )
		 && ( internal_file->block_data_size == 0 ) )
		{
			if( memory_set(
			     internal_file->block_data,
			     0,
			     internal_file->block_size ) == NULL )
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_MEMORY,
				 LIBCERROR_MEMORY_ERROR_SET_FAILED,
				 "%s: unable to clear block data.",
				 function );

				return( -1 );
			}
			read_count = libcfile_internal_file_read_buffer_at_offset_with_error_code(
			              internal_file,
			              internal_file->current_offset - internal_file->block_data_offset,
			              internal_file->block_data,
			              internal_file->block_size,
			              error_code,
			              error );

			if( read_count != (ssize_t) internal_file->block_size )
			{
				libcerror_system_set_error(
				 error,
				 LIBCERROR_ERROR_DOMAIN_IO,
				 LIBCERROR_IO_ERROR_READ_FAILED,
				 *error_code,
				 "%s: unable to read from file (pre-aligned, count: %" PRIzd ").",
				 function,
				 read_count );

				return( -1 );
			}
			internal_file->block_data_size = (size_t) read_count;
		}
		if( ( internal_file->block_data_offset > 0 )
		 && ( internal_file->block_data_offset < internal_file->block_data_size ) )
		{
			read_size = internal_file->block_data_size - internal_file->block_data_offset;

			if( read_size > size )
			{
				read_size = size;
			}
			if( memory_copy(
			     buffer,
			     &( internal_file->block_data[ internal_file->block_data_offset ] ),
			     read_size ) == NULL )
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_MEMORY,
				 LIBCERROR_MEMORY_ERROR_COPY_FAILED,
				 "%s: unable to copy block data.",
				 function );

				return( -1 );
			}
			buffer_offset                    += read_size;
			size                             -= read_size;
			internal_file->current_offset    += read_size;
			internal_file->block_data_offset += read_size;
		}
		if( size == 0 )
		{
			return( (ssize_t) buffer_offset );
		}
	}
	read_size = size;

	if( internal_file->block_size != 0 )
	{
		/* Read block aligned
		 */
		read_size_remainder = read_size % internal_file->block_size;
		read_size          -= read_size_remainder;
	}
	if( read_size > 0 )
	{
		read_count = libcfile_internal_file_read_buffer_at_offset_with_error_code(
		              internal_file,
		              internal_file->current_offset,
		              &( buffer[ buffer_offset ] ),
		              read_size,
		              error_code,
		              error );

		if( ( internal_file->block_size == 0 )
		 && ( read_count < 0 ) )
		{
			result = 0;
		}
		else if( ( internal_file->block_size != 0 )
		      && ( read_count != (ssize_t) read_size ) )
		{
			result = 0;
		}
		else
		{
			result = 1;
		}
		if( result == 0 )
		{
			libcerror_system_set_error(
			 error,
			 LIBCERROR_ERROR_DOMAIN_IO,
			 LIBCERROR_IO_ERROR_READ_FAILED,
			 *error_code,
			 "%s: unable to read from file.",
			 function );

			return( -1 );
		}
		buffer_offset                 += (size_t) read_count;
		internal_file->current_offset += read_count;
	}
	/* Read the non-aligned remainder
	 */
	if( read_size_remainder > 0 )
	{
		if( memory_set(
		     internal_file->block_data,
		     0,
		     internal_file->block_size ) == NULL )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_MEMORY,
			 LIBCERROR_MEMORY_ERROR_SET_FAILED,
			 "%s: unable to clear block data.",
			 function );

			return( -1 );
		}
		read_count = libcfile_internal_file_read_buffer_at_offset_with_error_code(
		              internal_file,
		              internal_file->current_offset,
		              internal_file->block_data,
		              internal_file->block_size,
		              error_code,
		              error );

		if( read_count != (ssize_t) internal_file->block_size )
		{
			libcerror_system_set_error(
			 error,
			 LIBCERROR_ERROR_DOMAIN_IO,
			 LIBCERROR_IO_ERROR_READ_FAILED,
			 *error_code,
			 "%s: unable to read from file (post-aligned, count: %" PRIzd ").",
			 function,
			 read_count );

			return( -1 );
		}
		internal_file->block_data_offset = 0;
		internal_file->block_data_size   = (size_t) read_count;

		if( memory_copy(
		     &( buffer[ buffer_offset ] ),
		     internal_file->block_data,
		     read_size_remainder ) == NULL )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_MEMORY,
			 LIBCERROR_MEMORY_ERROR_COPY_FAILED,
			 "%s: unable to copy block data.",
			 function );

			return( -1 );
		}
		buffer_offset                    += read_size_remainder;
		internal_file->current_offset    += read_size_remainder;
		internal_file->block_data_offset += read_size_remainder;
	}
	return( (ssize_t) buffer_offset );
}

#elif defined( HAVE_READ )

/* Reads a buffer from the file
 * This function uses the POSIX read function or equivalent
 * Returns the number of bytes read if successful, or -1 on error
 */
ssize_t libcfile_file_read_buffer_with_error_code(
         libcfile_file_t *file,
         uint8_t *buffer,
         size_t size,
         uint32_t *error_code,
         libcerror_error_t **error )
{
	libcfile_internal_file_t *internal_file = NULL;
	static char *function                   = "libcfile_file_read_buffer_with_error_code";
	size_t buffer_offset                    = 0;
	size_t read_size                        = 0;
	size_t read_size_remainder              = 0;
	ssize_t read_count                      = 0;
	int result                              = 0;

	if( file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
	internal_file = (libcfile_internal_file_t *) file;

	if( internal_file->descriptor == -1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
		 "%s: invalid file - missing descriptor.",
		 function );

		return( -1 );
	}
	if( buffer == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid buffer.",
		 function );

		return( -1 );
	}
	if( size > (size_t) SSIZE_MAX )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_EXCEEDS_MAXIMUM,
		 "%s: invalid size value exceeds maximum.",
		 function );

		return( -1 );
	}
	if( error_code == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid error code.",
		 function );

		return( -1 );
	}
	if( internal_file->block_size != 0 )
	{
		if( internal_file->block_data == NULL )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
			 "%s: invalid file - missing block data.",
			 function );

			return( -1 );
		}
	}
	if( internal_file->current_offset < 0 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid file - current offset value out of bounds.",
		 function );

		return( -1 );
	}
	if( ( size == 0 )
	 || ( (size64_t) internal_file->current_offset > internal_file->size ) )
	{
		return( 0 );
	}
	if( ( (size64_t) internal_file->current_offset + size ) > internal_file->size )
	{
		size = (size_t) ( internal_file->size - internal_file->current_offset );
	}
	if( internal_file->block_size != 0 )
	{
		/* Read a block of data to align with the next block
		 */
		if( ( internal_file->block_data_offset > 0 )
		 && ( internal_file->block_data_size == 0 ) )
		{
			if( memory_set(
			     internal_file->block_data,
			     0,
			     internal_file->block_size ) == NULL )
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_MEMORY,
				 LIBCERROR_MEMORY_ERROR_SET_FAILED,
				 "%s: unable to clear block data.",
				 function );

				return( -1 );
			}
			errno = 0;

			read_count = read(
			              internal_file->descriptor,
			              internal_file->block_data,
			              internal_file->block_size );

			if( read_count != (ssize_t) internal_file->block_size )
			{
				*error_code = (uint32_t) errno;

				libcerror_system_set_error(
				 error,
				 LIBCERROR_ERROR_DOMAIN_IO,
				 LIBCERROR_IO_ERROR_READ_FAILED,
				 *error_code,
				 "%s: unable to read from file (pre-aligned, count: %" PRIzd ").",
				 function,
				 read_count );

				return( -1 );
			}
			internal_file->block_data_size = (size_t) read_count;
		}
		if( ( internal_file->block_data_offset > 0 )
		 && ( internal_file->block_data_offset < internal_file->block_data_size ) )
		{
			read_size = internal_file->block_data_size - internal_file->block_data_offset;

			if( read_size > size )
			{
				read_size = size;
			}
			if( memory_copy(
			     buffer,
			     &( internal_file->block_data[ internal_file->block_data_offset ] ),
			     read_size ) == NULL )
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_MEMORY,
				 LIBCERROR_MEMORY_ERROR_COPY_FAILED,
				 "%s: unable to copy block data.",
				 function );

				return( -1 );
			}
			buffer_offset                    += read_size;
			size                             -= read_size;
			internal_file->block_data_offset += read_size;
			internal_file->current_offset    += read_size;
		}
		if( size == 0 )
		{
			return( (ssize_t) buffer_offset );
		}
	}
	read_size = size;

	if( internal_file->block_size != 0 )
	{
		/* Read block aligned
		 */
		read_size_remainder = read_size % internal_file->block_size;
		read_size          -= read_size_remainder;
	}
	if( read_size > 0 )
	{
		errno = 0;

		read_count = read(
		              internal_file->descriptor,
		              (void *) &( buffer[ buffer_offset ] ),
		              read_size );

		if( ( internal_file->block_size == 0 )
		 && ( read_count < 0 ) )
		{
			result = 0;
		}
		else if( ( internal_file->block_size != 0 )
		      && ( read_count != (ssize_t) read_size ) )
		{
			result = 0;
		}
		else
		{
			result = 1;
		}
		if( result == 0 )
		{
			*error_code = (uint32_t) errno;

			libcerror_system_set_error(
			 error,
			 LIBCERROR_ERROR_DOMAIN_IO,
			 LIBCERROR_IO_ERROR_READ_FAILED,
			 *error_code,
			 "%s: unable to read from file.",
			 function );

			return( -1 );
		}
		buffer_offset                 += (size_t) read_count;
		internal_file->current_offset += read_count;
	}
	/* Read the non-aligned remainder
	 */
	if( read_size_remainder > 0 )
	{
		if( memory_set(
		     internal_file->block_data,
		     0,
		     internal_file->block_size ) == NULL )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_MEMORY,
			 LIBCERROR_MEMORY_ERROR_SET_FAILED,
			 "%s: unable to clear block data.",
			 function );

			return( -1 );
		}
		errno = 0;

		read_count = read(
		              internal_file->descriptor,
		              internal_file->block_data,
		              internal_file->block_size );

		if( read_count != (ssize_t) internal_file->block_size )
		{
			*error_code = (uint32_t) errno;

			libcerror_system_set_error(
			 error,
			 LIBCERROR_ERROR_DOMAIN_IO,
			 LIBCERROR_IO_ERROR_READ_FAILED,
			 *error_code,
			 "%s: unable to read from file (post-aligned, count: %" PRIzd ").",
			 function,
			 read_count );

			return( -1 );
		}
		internal_file->block_data_offset = 0;
		internal_file->block_data_size   = (size_t) read_count;

		if( memory_copy(
		     &( buffer[ buffer_offset ] ),
		     internal_file->block_data,
		     read_size_remainder ) == NULL )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_MEMORY,
			 LIBCERROR_MEMORY_ERROR_COPY_FAILED,
			 "%s: unable to copy block data.",
			 function );

			return( -1 );
		}
		buffer_offset                    += read_size_remainder;
		internal_file->block_data_offset += read_size_remainder;
		internal_file->current_offset    += read_size_remainder;
	}
	return( (ssize_t) buffer_offset );
}

#else
#error Missing file read function
#endif

/* Writes a buffer to the file
 * Returns the number of bytes written if successful, or -1 on error
 */
ssize_t libcfile_file_write_buffer(
         libcfile_file_t *file,
         const uint8_t *buffer,
         size_t size,
         libcerror_error_t **error )
{
	static char *function = "libcfile_file_write_buffer";
	ssize_t write_count   = 0;
	uint32_t error_code   = 0;

	write_count = libcfile_file_write_buffer_with_error_code(
	               file,
	               buffer,
	               size,
	               &error_code,
	               error );

	if( write_count == -1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_IO,
		 LIBCERROR_IO_ERROR_WRITE_FAILED,
		 "%s: unable to write to file.",
		 function );

		return( -1 );
	}
	return( write_count );
}

#if defined( WINAPI )

/* Writes a buffer to the file
 * This function uses the WINAPI function for Windows XP (0x0501) or later
 * or tries to dynamically call the function for Windows 2000 (0x0500) or earlier
 * Returns the number of bytes written if successful, or -1 on error
 */
ssize_t libcfile_file_write_buffer_with_error_code(
         libcfile_file_t *file,
         const uint8_t *buffer,
         size_t size,
         uint32_t *error_code,
         libcerror_error_t **error )
{
	libcfile_internal_file_t *internal_file = NULL;
	static char *function                   = "libcfile_file_write_buffer_with_error_code";
	ssize_t write_count                     = 0;
	BOOL result                             = FALSE;

	if( file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
	internal_file = (libcfile_internal_file_t *) file;

	if( internal_file->handle == INVALID_HANDLE_VALUE )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
		 "%s: invalid file - missing handle.",
		 function );

		return( -1 );
	}
	if( buffer == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid buffer.",
		 function );

		return( -1 );
	}
#if ( UINT32_MAX < SSIZE_MAX )
	if( size > (size_t) UINT32_MAX )
#else
	if( size > (size_t) SSIZE_MAX )
#endif
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_EXCEEDS_MAXIMUM,
		 "%s: invalid size value exceeds maximum.",
		 function );

		return( -1 );
	}
	if( error_code == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid error code.",
		 function );

		return( -1 );
	}
#if ( WINVER <= 0x0500 )
	result = libcfile_WriteFile(
	          internal_file->handle,
	          (VOID *) buffer,
	          (DWORD) size,
	          (DWORD *) &write_count,
	          NULL );
#else
	result = WriteFile(
	          internal_file->handle,
	          (VOID *) buffer,
	          (DWORD) size,
	          (DWORD *) &write_count,
	          NULL );
#endif
	if( result == 0 )
	{
		*error_code = (uint32_t) GetLastError();

		libcerror_system_set_error(
		 error,
		 LIBCERROR_ERROR_DOMAIN_IO,
		 LIBCERROR_IO_ERROR_WRITE_FAILED,
		 *error_code,
		 "%s: unable to write to file.",
		 function );

		return( -1 );
	}
	if( write_count < 0 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_IO,
		 LIBCERROR_IO_ERROR_WRITE_FAILED,
		 "%s: invalid write count: %" PRIzd " returned.",
		 function,
		 write_count );

		return( -1 );
	}
	internal_file->current_offset += write_count;

	if( (size64_t) internal_file->current_offset > internal_file->size )
	{
		internal_file->size = (size64_t) internal_file->current_offset;
	}
	return( write_count );
}

#elif defined( HAVE_WRITE )

/* Writes a buffer to the file
 * This function uses the POSIX write function or equivalent
 * Returns the number of bytes written if successful, or -1 on error
 */
ssize_t libcfile_file_write_buffer_with_error_code(
         libcfile_file_t *file,
         const uint8_t *buffer,
         size_t size,
         uint32_t *error_code,
         libcerror_error_t **error )
{
	libcfile_internal_file_t *internal_file = NULL;
	static char *function                   = "libcfile_file_write_buffer_with_error_code";
	ssize_t write_count                     = 0;

	if( file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
	internal_file = (libcfile_internal_file_t *) file;

	if( internal_file->descriptor == -1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
		 "%s: invalid file - missing descriptor.",
		 function );

		return( -1 );
	}
	if( buffer == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid buffer.",
		 function );

		return( -1 );
	}
	if( size > (size_t) SSIZE_MAX )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_EXCEEDS_MAXIMUM,
		 "%s: invalid size value exceeds maximum.",
		 function );

		return( -1 );
	}
	if( error_code == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid error code.",
		 function );

		return( -1 );
	}
	write_count = write(
	               internal_file->descriptor,
	               (void *) buffer,
	               size );

	if( write_count < 0 )
	{
		*error_code = (uint32_t) errno;

		libcerror_system_set_error(
		 error,
		 LIBCERROR_ERROR_DOMAIN_IO,
		 LIBCERROR_IO_ERROR_WRITE_FAILED,
		 *error_code,
		 "%s: unable to write to file.",
		 function );

		return( -1 );
	}
	internal_file->current_offset += write_count;

	if( (size64_t) internal_file->current_offset > internal_file->size )
	{
		internal_file->size = (size64_t) internal_file->current_offset;
	}
	return( write_count );
}

#else
#error Missing file write function
#endif

#if defined( WINAPI )

/* Seeks a certain offset within the file
 * This function uses the WINAPI function for Windows XP (0x0501) or later
 * or tries to dynamically call the function for Windows 2000 (0x0500) or earlier
 * Returns the offset if the seek is successful or -1 on error
 */
off64_t libcfile_file_seek_offset(
         libcfile_file_t *file,
         off64_t offset,
         int whence,
         libcerror_error_t **error )
{
	libcfile_internal_file_t *internal_file = NULL;
	static char *function                   = "libcfile_file_seek_offset";
	off64_t calculated_offset               = 0;
	off64_t offset_remainder                = 0;
	LARGE_INTEGER large_integer_offset      = LIBCFILE_LARGE_INTEGER_ZERO;
	DWORD error_code                        = 0;
	DWORD move_method                       = 0;

	if( file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
	internal_file = (libcfile_internal_file_t *) file;

	if( internal_file->handle == INVALID_HANDLE_VALUE )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
		 "%s: invalid file - missing handle.",
		 function );

		return( -1 );
	}
	if( offset > (off64_t) INT64_MAX )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_EXCEEDS_MAXIMUM,
		 "%s: invalid offset value exceeds maximum.",
		 function );

		return( -1 );
	}
	calculated_offset = offset;

	if( whence == SEEK_CUR )
	{
		calculated_offset += internal_file->current_offset;
	}
	else if( whence == SEEK_END )
	{
		calculated_offset += internal_file->size;
	}
	else if( whence != SEEK_SET )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_UNSUPPORTED_VALUE,
		 "%s: unsupported whence.",
		 function );

		return( -1 );
	}
	if( calculated_offset < 0 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid offset value out of bounds.",
		 function );

		return( -1 );
	}
	if( internal_file->block_size != 0 )
	{
		whence           = SEEK_SET;
		offset_remainder = calculated_offset % internal_file->block_size;
		offset           = calculated_offset - offset_remainder;
	}
	if( whence == SEEK_SET )
	{
		move_method = FILE_BEGIN;
	}
	else if( whence == SEEK_CUR )
	{
		move_method = FILE_CURRENT;
	}
	else if( whence == SEEK_END )
	{
		move_method = FILE_END;
	}
	/* SetFilePointerEx cannot be used in combination with FILE_FLAG_OVERLAPPED.
	 */
	if( internal_file->use_asynchronous_io == 0 )
	{
#if defined( __BORLANDC__ ) && __BORLANDC__ <= 0x0520
		large_integer_offset.QuadPart = (LONGLONG) offset;
#else
		large_integer_offset.LowPart  = (DWORD) ( 0x0ffffffffUL & offset );
		large_integer_offset.HighPart = (LONG) ( offset >> 32 );
#endif

#if ( WINVER <= 0x0500 )
		if( libcfile_SetFilePointerEx(
		     internal_file->handle,
		     large_integer_offset,
		     &large_integer_offset,
		     move_method ) == 0 )
#else
		if( SetFilePointerEx(
		     internal_file->handle,
		     large_integer_offset,
		     &large_integer_offset,
		     move_method ) == 0 )
#endif
		{
			error_code = GetLastError();

			libcerror_system_set_error(
			 error,
			 LIBCERROR_ERROR_DOMAIN_IO,
			 LIBCERROR_IO_ERROR_SEEK_FAILED,
			 error_code,
			 "%s: unable to seek offset in file.",
			 function );

			return( -1 );
		}
#if defined( __BORLANDC__ ) && __BORLANDC__ <= 0x0520
		offset = (off64_t) large_integer_offset.QuadPart;
#else
		offset = ( (off64_t) large_integer_offset.HighPart << 32 ) + large_integer_offset.LowPart;
#endif
		if( offset < 0 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_IO,
			 LIBCERROR_IO_ERROR_SEEK_FAILED,
			 "%s: invalid offset: %" PRIi64 " returned.",
			 function,
			 offset );

			return( -1 );
		}
	}
	internal_file->current_offset = offset;

	if( internal_file->block_size != 0 )
	{
		internal_file->current_offset   += offset_remainder;
		internal_file->block_data_offset = (size_t) offset_remainder;
		internal_file->block_data_size   = 0;
	}
	return( internal_file->current_offset );
}

#elif defined( HAVE_LSEEK )

/* Seeks a certain offset within the file
 * This function uses the POSIX lseek function or equivalent
 * Returns the offset if the seek is successful or -1 on error
 */
off64_t libcfile_file_seek_offset(
         libcfile_file_t *file,
         off64_t offset,
         int whence,
         libcerror_error_t **error )
{
	libcfile_internal_file_t *internal_file = NULL;
	static char *function                   = "libcfile_file_seek_offset";
	off64_t calculated_offset               = 0;
	off64_t offset_remainder                = 0;

	if( file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
	internal_file = (libcfile_internal_file_t *) file;

	if( internal_file->descriptor == -1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
		 "%s: invalid file - missing descriptor.",
		 function );

		return( -1 );
	}
	if( offset > (off64_t) INT64_MAX )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_EXCEEDS_MAXIMUM,
		 "%s: invalid offset value exceeds maximum.",
		 function );

		return( -1 );
	}
	calculated_offset = offset;

	if( whence == SEEK_CUR )
	{
		calculated_offset += internal_file->current_offset;
	}
	else if( whence == SEEK_END )
	{
		calculated_offset += internal_file->size;
	}
	else if( whence != SEEK_SET )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_UNSUPPORTED_VALUE,
		 "%s: unsupported whence.",
		 function );

		return( -1 );
	}
	if( calculated_offset < 0 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid offset value out of bounds.",
		 function );

		return( -1 );
	}
	if( internal_file->block_size != 0 )
	{
		whence           = SEEK_SET;
		offset_remainder = calculated_offset % internal_file->block_size;
		offset           = calculated_offset - offset_remainder;
	}
	offset = lseek(
	          internal_file->descriptor,
	          (off_t) offset,
	          whence );

	if( offset < 0 )
	{
		libcerror_system_set_error(
		 error,
		 LIBCERROR_ERROR_DOMAIN_IO,
		 LIBCERROR_IO_ERROR_SEEK_FAILED,
		 errno,
		 "%s: unable to seek offset in file.",
		 function );

		return( -1 );
	}
	internal_file->current_offset = offset;

	if( internal_file->block_size != 0 )
	{
		internal_file->current_offset   += offset_remainder;
		internal_file->block_data_offset = (size_t) offset_remainder;
		internal_file->block_data_size   = 0;
	}
	return( internal_file->current_offset );
}

#else
#error Missing file lseek function
#endif

#if defined( WINAPI )

/* Resizes the file
 * This function uses the WINAPI function for Windows XP (0x0501) or later
 * or tries to dynamically call the function for Windows 2000 (0x0500) or earlier
 * Returns 1 if successful or -1 on error
 */
int libcfile_file_resize(
     libcfile_file_t *file,
     size64_t size,
     libcerror_error_t **error )
{
	libcfile_internal_file_t *internal_file = NULL;
	static char *function                   = "libcfile_file_resize";
	off64_t offset                          = 0;
	LARGE_INTEGER large_integer_offset      = LIBCFILE_LARGE_INTEGER_ZERO;
	DWORD error_code                        = 0;

	if( file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
	internal_file = (libcfile_internal_file_t *) file;

	if( internal_file->handle == INVALID_HANDLE_VALUE )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
		 "%s: invalid file - missing handle.",
		 function );

		return( -1 );
	}
	if( size > (size64_t) INT64_MAX )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_EXCEEDS_MAXIMUM,
		 "%s: invalid size value exceeds maximum.",
		 function );

		return( -1 );
	}
#if defined( __BORLANDC__ ) && __BORLANDC__ <= 0x0520
	large_integer_offset.QuadPart = (LONGLONG) size;
#else
	large_integer_offset.LowPart  = (DWORD) ( 0x0ffffffffUL & size );
	large_integer_offset.HighPart = (LONG) ( size >> 32 );
#endif

#if ( WINVER <= 0x0500 )
	if( libcfile_SetFilePointerEx(
	     internal_file->handle,
	     large_integer_offset,
	     &large_integer_offset,
	     FILE_BEGIN ) == 0 )
#else
	if( SetFilePointerEx(
	     internal_file->handle,
	     large_integer_offset,
	     &large_integer_offset,
	     FILE_BEGIN ) == 0 )
#endif
	{
		error_code = GetLastError();

		libcerror_system_set_error(
		 error,
		 LIBCERROR_ERROR_DOMAIN_IO,
		 LIBCERROR_IO_ERROR_SEEK_FAILED,
		 error_code,
		 "%s: unable to seek offset in file.",
		 function );

		return( -1 );
	}
#if defined( __BORLANDC__ ) && __BORLANDC__ <= 0x0520
	offset = (off64_t) large_integer_offset.QuadPart;
#else
	offset = ( (off64_t) large_integer_offset.HighPart << 32 ) + large_integer_offset.LowPart;
#endif

	if( offset < 0 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_IO,
		 LIBCERROR_IO_ERROR_SEEK_FAILED,
		 "%s: invalid offset: %" PRIi64 " returned.",
		 function,
		 offset );

		return( -1 );
	}
	internal_file->current_offset = offset;

#if ( WINVER <= 0x0500 )
	if( libcfile_SetEndOfFile(
	     internal_file->handle ) == 0 )
#else
	if( SetEndOfFile(
	     internal_file->handle ) == 0 )
#endif
	{
		error_code = GetLastError();

		libcerror_system_set_error(
		 error,
		 LIBCERROR_ERROR_DOMAIN_IO,
		 LIBCERROR_IO_ERROR_SEEK_FAILED,
		 error_code,
		 "%s: unable to resize file.",
		 function );

		return( -1 );
	}
	return( 1 );
}

#elif defined( HAVE_FTRUNCATE )

/* Resizes the file
 * This function uses the POSIX truncate function or equivalent
 * Returns 1 if successful or -1 on error
 */
int libcfile_file_resize(
     libcfile_file_t *file,
     size64_t size,
     libcerror_error_t **error )
{
	libcfile_internal_file_t *internal_file = NULL;
	static char *function                   = "libcfile_file_resize";
	off_t offset                            = 0;

	if( file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
	internal_file = (libcfile_internal_file_t *) file;

	if( internal_file->descriptor == -1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
		 "%s: invalid file - missing descriptor.",
		 function );

		return( -1 );
	}
#if SIZEOF_OFF_T >= 8
	if( size > (size64_t) INT64_MAX )
#else
	if( size > (size64_t) INT32_MAX )
#endif
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_EXCEEDS_MAXIMUM,
		 "%s: invalid size value exceeds maximum.",
		 function );

		return( -1 );
	}
	if( ftruncate(
	     internal_file->descriptor,
	     (off_t) size ) != 0 )
	{
		libcerror_system_set_error(
		 error,
		 LIBCERROR_ERROR_DOMAIN_IO,
		 LIBCERROR_IO_ERROR_GENERIC,
		 errno,
		 "%s: unable to resize file.",
		 function );

		return( -1 );
	}
	offset = lseek(
	          internal_file->descriptor,
	          0,
	          SEEK_CUR );

	if( offset < 0 )
	{
		libcerror_system_set_error(
		 error,
		 LIBCERROR_ERROR_DOMAIN_IO,
		 LIBCERROR_IO_ERROR_SEEK_FAILED,
		 errno,
		 "%s: unable to seek offset in file.",
		 function );

		return( -1 );
	}
	internal_file->current_offset = (off64_t) offset;

	return( 1 );
}

#else
#error Missing file truncate function
#endif

/* Checks if the file is open
 * Returns 1 if open, 0 if not or -1 on error
 */
int libcfile_file_is_open(
     libcfile_file_t *file,
     libcerror_error_t **error )
{
	libcfile_internal_file_t *internal_file = NULL;
	static char *function                   = "libcfile_file_is_open";

	if( file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
	internal_file = (libcfile_internal_file_t *) file;

#if defined( WINAPI )
	if( internal_file->handle == INVALID_HANDLE_VALUE )
#else
	if( internal_file->descriptor == -1 )
#endif
	{
		return( 0 );
	}
	return( 1 );
}

/* Retrieves the current offset in the file
 * Returns 1 if successful or -1 on error
 */
int libcfile_file_get_offset(
     libcfile_file_t *file,
     off64_t *offset,
     libcerror_error_t **error )
{
	libcfile_internal_file_t *internal_file = NULL;
	static char *function                   = "libcfile_file_get_offset";

	if( file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
	internal_file = (libcfile_internal_file_t *) file;

#if defined( WINAPI )
	if( internal_file->handle == INVALID_HANDLE_VALUE )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
		 "%s: invalid file - missing handle.",
		 function );

		return( -1 );
	}
#else
	if( internal_file->descriptor == -1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
		 "%s: invalid file - missing descriptor.",
		 function );

		return( -1 );
	}
#endif
	if( offset == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid offset.",
		 function );

		return( -1 );
	}
	*offset = internal_file->current_offset;

	return( 1 );
}

#if defined( WINAPI )

#if !defined( IOCTL_DISK_GET_LENGTH_INFO )
#define IOCTL_DISK_GET_LENGTH_INFO \
	CTL_CODE( IOCTL_DISK_BASE, 0x0017, METHOD_BUFFERED, FILE_READ_ACCESS )

typedef struct
{
	LARGE_INTEGER Length;
}
GET_LENGTH_INFORMATION;

#endif /* !defined( IOCTL_DISK_GET_LENGTH_INFO ) */

/* Retrieves the size of the file
 * This function uses the WINAPI function for Windows XP (0x0501) or later
 * or tries to dynamically call the function for Windows 2000 (0x0500) or earlier
 * Returns 1 if successful or -1 on error
 */
int libcfile_internal_file_get_size(
     libcfile_internal_file_t *internal_file,
     size64_t *size,
     libcerror_error_t **error )
{
	DISK_GEOMETRY disk_geometry;
	GET_LENGTH_INFORMATION length_information;

	static char *function            = "libcfile_internal_file_get_size";
	LARGE_INTEGER large_integer_size = LIBCFILE_LARGE_INTEGER_ZERO;
	size_t read_count                = 0;
	uint32_t error_code              = 0;
	int result                       = 0;

	if( internal_file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
	if( internal_file->handle == INVALID_HANDLE_VALUE )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
		 "%s: invalid file - missing handle.",
		 function );

		return( -1 );
	}
	if( size == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid size.",
		 function );

		return( -1 );
	}
	result = libcfile_file_is_device(
	          (libcfile_file_t *) internal_file,
	          error );

	if( result == -1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
		 "%s: unable to determine if file is a device.",
		 function );

		return( -1 );
	}
	else if( result != 0 )
	{
#if defined( LIBCFILE_HAVE_IO_CONTROL )
		read_count = libcfile_internal_file_io_control_read_with_error_code(
		              internal_file,
		              IOCTL_DISK_GET_LENGTH_INFO,
		              NULL,
		              0,
		              (uint8_t *) &length_information,
		              sizeof( GET_LENGTH_INFORMATION ),
		              &error_code,
		              error );

		if( read_count == -1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_IO,
			 LIBCERROR_IO_ERROR_IOCTL_FAILED,
			 "%s: unable to query device for: IOCTL_DISK_GET_LENGTH_INFO.",
			 function );

#if defined( HAVE_DEBUG_OUTPUT )
			if( libcnotify_verbose != 0 )
			{
				if( ( error != NULL )
				 && ( *error != NULL ) )
				{
					libcnotify_print_error_backtrace(
					 *error );
				}
			}
#endif
			libcerror_error_free(
			 error );

			if( error_code == ERROR_NOT_SUPPORTED )
			{
				/* A floppy device does not support IOCTL_DISK_GET_LENGTH_INFO
				 */
				read_count = libcfile_internal_file_io_control_read_with_error_code(
				              internal_file,
				              IOCTL_DISK_GET_DRIVE_GEOMETRY,
				              NULL,
				              0,
				              (uint8_t *) &disk_geometry,
				              sizeof( DISK_GEOMETRY ),
				              &error_code,
				              error );

				if( read_count == -1 )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_IO,
					 LIBCERROR_IO_ERROR_IOCTL_FAILED,
					 "%s: unable to query device for: IOCTL_DISK_GET_DRIVE_GEOMETRY.",
					 function );

#if defined( HAVE_DEBUG_OUTPUT )
					if( libcnotify_verbose != 0 )
					{
						if( ( error != NULL )
						 && ( *error != NULL ) )
						{
							libcnotify_print_error_backtrace(
							 *error );
						}
					}
#endif
					libcerror_error_free(
					 error );
				}
				else
				{
					*size  = disk_geometry.Cylinders.QuadPart;
					*size *= disk_geometry.TracksPerCylinder;
					*size *= disk_geometry.SectorsPerTrack;
					*size *= disk_geometry.BytesPerSector;
				}
			}
		}
		else
		{
			*size  = (size64_t) length_information.Length.HighPart << 32;
			*size += (uint32_t) length_information.Length.LowPart;
		}
#else
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
		 "%s: unable to determine size of device.",
		 function );

		return( -1 );

#endif /* defined( LIBCFILE_HAVE_IO_CONTROL ) */
	}
	else
	{
#if ( WINVER <= 0x0500 )
		if( libcfile_GetFileSizeEx(
		     internal_file->handle,
		     &large_integer_size ) == 0 )
#else
		if( GetFileSizeEx(
		     internal_file->handle,
		     &large_integer_size ) == 0 )
#endif
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
			 "%s: unable to retrieve file size.",
			 function );

			return( -1 );
		}
#if defined( __BORLANDC__ ) && __BORLANDC__ <= 0x520
		*size = (size64_t) large_integer_size.QuadPart;
#else
		*size  = (size64_t) large_integer_size.HighPart << 32;
		*size += (uint32_t) large_integer_size.LowPart;
#endif
	}
	return( 1 );
}

#elif defined( HAVE_FSTAT )

/* Retrieves the size of the file
 * This function uses the POSIX fstat function or equivalent
 * Returns 1 if successful or -1 on error
 */
int libcfile_internal_file_get_size(
     libcfile_internal_file_t *internal_file,
     size64_t *size,
     libcerror_error_t **error )
{
	struct stat file_statistics;

	static char *function     = "libcfile_internal_file_get_size";
	size64_t safe_size        = 0;
	ssize_t read_count        = 0;
	off64_t current_offset    = 0;
	off64_t offset            = 0;

#if defined( BLKGETSIZE64 ) || defined( DIOCGMEDIASIZE ) || defined( DIOCGDINFO ) || ( defined( DKIOCGETBLOCKCOUNT ) && defined( DKIOCGETBLOCKSIZE ) )
	uint32_t error_code       = 0;
#endif

#if !defined( DIOCGMEDIASIZE ) && defined( DIOCGDINFO )
	struct disklabel disk_label;
#endif
#if defined( DKIOCGETBLOCKCOUNT ) && defined( DKIOCGETBLOCKSIZE )
	uint64_t block_count      = 0;
	uint32_t bytes_per_sector = 0;
#endif

	if( internal_file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
	if( internal_file->descriptor == -1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
		 "%s: invalid file - missing descriptor.",
		 function );

		return( -1 );
	}
	if( size == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid size.",
		 function );

		return( -1 );
	}
	if( memory_set(
	     &file_statistics,
	     0,
	     sizeof( struct stat ) ) == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_SET_FAILED,
		 "%s: unable to clear file statistics.",
		 function );

		return( -1 );
	}
	if( fstat(
	     internal_file->descriptor,
	     &file_statistics ) != 0 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
		 "%s: unable to retrieve file statistics.",
		 function );

		return( -1 );
	}
	if( S_ISBLK( file_statistics.st_mode )
	 || S_ISCHR( file_statistics.st_mode ) )
	{
#if defined( LIBCFILE_HAVE_IO_CONTROL )
#if defined( BLKGETSIZE64 )
		read_count = libcfile_internal_file_io_control_read_with_error_code(
		              internal_file,
		              (uint32_t) BLKGETSIZE64,
		              NULL,
		              0,
		              (uint8_t *) &safe_size,
		              8,
		              &error_code,
		              error );

		if( read_count == -1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_IO,
			 LIBCERROR_IO_ERROR_IOCTL_FAILED,
			 "%s: unable to query device for: BLKGETSIZE64.",
			 function );

#if defined( HAVE_DEBUG_OUTPUT )
			if( libcnotify_verbose != 0 )
			{
				if( ( error != NULL )
				 && ( *error != NULL ) )
				{
					libcnotify_print_error_backtrace(
					 *error );
				}
			}
#endif
			libcerror_error_free(
			 error );
		}
#elif defined( DIOCGMEDIASIZE )
		read_count = libcfile_internal_file_io_control_read_with_error_code(
		              internal_file,
		              (uint32_t) DIOCGMEDIASIZE,
		              NULL,
		              0,
		              (uint8_t *) &safe_size,
		              8,
		              &error_code,
		              error );

		if( read_count == -1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_IO,
			 LIBCERROR_IO_ERROR_IOCTL_FAILED,
			 "%s: unable to query device for: DIOCGMEDIASIZE.",
			 function );

#if defined( HAVE_DEBUG_OUTPUT )
			if( libcnotify_verbose != 0 )
			{
				if( ( error != NULL )
				 && ( *error != NULL ) )
				{
					libcnotify_print_error_backtrace(
					 *error );
				}
			}
#endif
			libcerror_error_free(
			 error );
		}
#elif defined( DIOCGDINFO )
		read_count = libcfile_internal_file_io_control_read_with_error_code(
		              internal_file,
		              (uint32_t) DIOCGDINFO,
		              NULL,
		              0,
		              (uint8_t *) &disk_label,
		              sizeof( struct disklabel ),
		              &error_code,
		              error );

		if( read_count == -1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_IO,
			 LIBCERROR_IO_ERROR_IOCTL_FAILED,
			 "%s: unable to query device for: DIOCGDINFO.",
			 function );

#if defined( HAVE_DEBUG_OUTPUT )
			if( libcnotify_verbose != 0 )
			{
				if( ( error != NULL )
				 && ( *error != NULL ) )
				{
					libcnotify_print_error_backtrace(
					 *error );
				}
			}
#endif
			libcerror_error_free(
			 error );
		}
		else
		{
			safe_size = disk_label.d_secperunit * disk_label.d_secsize;
		}
#elif defined( DKIOCGETBLOCKCOUNT ) && defined( DKIOCGETBLOCKSIZE )
		read_count = libcfile_internal_file_io_control_read_with_error_code(
		              internal_file,
		              (uint32_t) DKIOCGETBLOCKSIZE,
		              NULL,
		              0,
		              (uint8_t *) &bytes_per_sector,
		              4,
		              &error_code,
		              error );

		if( read_count == -1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_IO,
			 LIBCERROR_IO_ERROR_IOCTL_FAILED,
			 "%s: unable to query device for: DKIOCGETBLOCKSIZE.",
			 function );

#if defined( HAVE_DEBUG_OUTPUT )
			if( libcnotify_verbose != 0 )
			{
				if( ( error != NULL )
				 && ( *error != NULL ) )
				{
					libcnotify_print_error_backtrace(
					 *error );
				}
			}
#endif
			libcerror_error_free(
			 error );
		}
		else
		{
			read_count = libcfile_internal_file_io_control_read_with_error_code(
			              internal_file,
			              (uint32_t) DKIOCGETBLOCKCOUNT,
			              NULL,
			              0,
			              (uint8_t *) &block_count,
			              4,
			              &error_code,
			              error );

			if( read_count == -1 )
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_IO,
				 LIBCERROR_IO_ERROR_IOCTL_FAILED,
				 "%s: unable to query device for: DKIOCGETBLOCKCOUNT.",
				 function );

#if defined( HAVE_DEBUG_OUTPUT )
				if( libcnotify_verbose != 0 )
				{
					if( ( error != NULL )
					 && ( *error != NULL ) )
					{
						libcnotify_print_error_backtrace(
						 *error );
					}
				}
#endif
				libcerror_error_free(
				 error );
			}
			else
			{
#if defined( HAVE_DEBUG_OUTPUT )
				if( libcnotify_verbose != 0 )
				{
					libcnotify_printf(
					 "%s: block size: %" PRIu32 " block count: %" PRIu64 " ",
					 function,
					 bytes_per_sector,
					 block_count );
				}
#endif
				safe_size = (size64_t) ( block_count * bytes_per_sector );
			}
		}
#endif
#endif /* defined( LIBCFILE_HAVE_IO_CONTROL ) */

		if( read_count <= 0 )
		{
			/* Try to seek the end of the file and determine the size based on the offset
			 */
			if( libcfile_file_get_offset(
			     (libcfile_file_t *) internal_file,
			     &current_offset,
			     error ) != 1  )
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_RUNTIME,
				 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
				 "%s: unable to retrieve current offset.",
				 function );

				return( -1 );
			}
			offset = libcfile_file_seek_offset(
			          (libcfile_file_t *) internal_file,
				  0,
				  SEEK_END,
				  error );

			if( offset == -1 )
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_IO,
				 LIBCERROR_IO_ERROR_SEEK_FAILED,
				 "%s: unable to seek end of file.",
				 function );

				return( -1 );
			}
			safe_size = (size64_t) offset;

			offset = libcfile_file_seek_offset(
			          (libcfile_file_t *) internal_file,
				  current_offset,
				  SEEK_SET,
				  error );

			if( offset == -1 )
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_IO,
				 LIBCERROR_IO_ERROR_SEEK_FAILED,
				 "%s: unable to seek offset: %" PRIi64 ".",
				 function,
				 current_offset );

				return( -1 );
			}
		}
#if defined( HAVE_DEBUG_OUTPUT )
		if( libcnotify_verbose != 0 )
		{
			libcnotify_printf(
			 "%s: device media size: %" PRIu64 "\n",
			 function,
			 safe_size );
		}
#endif
	}
	else
	{
		safe_size = (size64_t) file_statistics.st_size;
	}
	*size = safe_size;

	return( 1 );
}

#else
#error Missing file get size function
#endif

/* Retrieves the size of the file
 * Returns 1 if successful or -1 on error
 */
int libcfile_file_get_size(
     libcfile_file_t *file,
     size64_t *size,
     libcerror_error_t **error )
{
	libcfile_internal_file_t *internal_file = NULL;
	static char *function                   = "libcfile_file_get_size";

	if( file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
	internal_file = (libcfile_internal_file_t *) file;

	if( size == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid size.",
		 function );

		return( -1 );
	}
	*size = internal_file->size;

	return( 1 );
}

#if defined( WINAPI )

/* Determines if a file is a device
 * This function uses the WINAPI function for Windows XP (0x0501) or later
 * or tries to dynamically call the function for Windows 2000 (0x0500) or earlier
 * Returns 1 if true, 0 if not or -1 on error
 */
int libcfile_file_is_device(
     libcfile_file_t *file,
     libcerror_error_t **error )
{
	libcfile_internal_file_t *internal_file = NULL;
	static char *function                   = "libcfile_file_is_device";
	DWORD file_type                         = 0;
	int result                              = 0;

	if( file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
	internal_file = (libcfile_internal_file_t *) file;

	if( internal_file->handle == INVALID_HANDLE_VALUE )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
		 "%s: invalid file - missing handle.",
		 function );

		return( -1 );
	}
/* TODO what about FILE_ATTRIBUTE_DEVICE using GetFileAttributes() */

	/* Use the GetFileType function to rule out certain file types
	 * like pipes, sockets, etc.
	 */
#if ( WINVER <= 0x0500 )
	file_type = libcfile_GetFileType(
	             internal_file->handle );
#else
	file_type = GetFileType(
	             internal_file->handle );
#endif

	if( file_type == FILE_TYPE_UNKNOWN )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
		 "%s: unable to determine file type.",
		 function );

		return( -1 );
	}
	else if( file_type == FILE_TYPE_DISK )
	{
		if( internal_file->is_device_filename )
		{
			result = 1;
		}
	}
	return( result );
}

#elif defined( HAVE_FSTAT )

/* Determines if a file is a device
 * This function uses the POSIX fstat function or equivalent
 * Returns 1 if true, 0 if not or -1 on error
 */
int libcfile_file_is_device(
     libcfile_file_t *file,
     libcerror_error_t **error )
{
	struct stat file_statistics;

	libcfile_internal_file_t *internal_file = NULL;
	static char *function                   = "libcfile_file_is_device";
	int result                              = 0;

	if( file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
	internal_file = (libcfile_internal_file_t *) file;

	if( internal_file->descriptor == -1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
		 "%s: invalid file - missing descriptor.",
		 function );

		return( -1 );
	}
	if( memory_set(
	     &file_statistics,
	     0,
	     sizeof( struct stat ) ) == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_SET_FAILED,
		 "%s: unable to clear file statistics.",
		 function );

		return( -1 );
	}
	if( fstat(
	     internal_file->descriptor,
	     &file_statistics ) != 0 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
		 "%s: unable to retrieve file statistics.",
		 function );

		return( -1 );
	}
	if( S_ISBLK( file_statistics.st_mode )
	 || S_ISCHR( file_statistics.st_mode ) )
	{
		result = 1;
	}
	return( result );
}

#else
#error Missing file is device function
#endif

/* Read data from a device file using IO control
 * This function uses the POSIX ioctl function or WINAPI DeviceIoControl
 * Returns the number of bytes read if successful or -1 on error
 */
ssize_t libcfile_internal_file_io_control_read_with_error_code(
         libcfile_internal_file_t *internal_file,
         uint32_t control_code,
         uint8_t *control_data,
         size_t control_data_size,
         uint8_t *data,
         size_t data_size,
         uint32_t *error_code,
         libcerror_error_t **error )
{
	static char *function = "libcfile_internal_file_io_control_read_with_error_code";

#if defined( WINAPI )
	BOOL result           = 0;
	DWORD response_count  = 0;
#endif

	if( internal_file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
#if defined( WINAPI )
	if( internal_file->handle == INVALID_HANDLE_VALUE )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
		 "%s: invalid file - missing handle.",
		 function );

		return( -1 );
	}
#else
	if( internal_file->descriptor == -1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
		 "%s: invalid file - missing descriptor.",
		 function );

		return( -1 );
	}
#endif
	if( control_data == NULL )
	{
		if( control_data_size != 0 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
			 LIBCERROR_ARGUMENT_ERROR_VALUE_EXCEEDS_MAXIMUM,
			 "%s: invalid control data size value exceeds maximum.",
			 function );

			return( -1 );
		}
	}
	else
	{
#if ( UINT32_MAX < SSIZE_MAX )
		if( control_data_size > (size_t) UINT32_MAX )
#else
		if( control_data_size > (size_t) SSIZE_MAX )
#endif
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
			 LIBCERROR_ARGUMENT_ERROR_VALUE_EXCEEDS_MAXIMUM,
			 "%s: invalid control data size value exceeds maximum.",
			 function );

			return( -1 );
		}
	}
	if( data == NULL )
	{
		if( data_size != 0 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
			 LIBCERROR_ARGUMENT_ERROR_VALUE_EXCEEDS_MAXIMUM,
			 "%s: invalid data size value exceeds maximum.",
			 function );

			return( -1 );
		}
	}
	else
	{
#if ( UINT32_MAX < SSIZE_MAX )
		if( data_size > (size_t) UINT32_MAX )
#else
		if( data_size > (size_t) SSIZE_MAX )
#endif
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
			 LIBCERROR_ARGUMENT_ERROR_VALUE_EXCEEDS_MAXIMUM,
			 "%s: invalid data size value exceeds maximum.",
			 function );

			return( -1 );
		}
	}
	if( error_code == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid error code.",
		 function );

		return( -1 );
	}
#if defined( WINAPI )
	result = DeviceIoControl(
	          internal_file->handle,
	          (DWORD) control_code,
	          control_data,
	          (DWORD) control_data_size,
	          data,
	          (DWORD) data_size,
	          &response_count,
	          NULL );

	if( result == FALSE )
	{
		*error_code = (uint32_t) GetLastError();

		/* TODO: handle if( *error_code == ERROR_MORE_DATA ) */

		libcerror_system_set_error(
		 error,
		 LIBCERROR_ERROR_DOMAIN_IO,
		 LIBCERROR_IO_ERROR_IOCTL_FAILED,
		 *error_code,
		 "%s: unable to IO control device.",
		 function );

		return( -1 );
	}
#if ( SSIZE_MAX < UINT32_MAX )
	if( response_count > (size_t) SSIZE_MAX )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid response count value out of bounds.",
		 function );

		return( -1 );
	}
#endif
	return( (ssize_t) response_count );

#elif defined( HAVE_IOCTL )
	if( control_data != NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_UNSUPPORTED_VALUE,
		 "%s: unsupported control data.",
		 function );

		return( -1 );
	}
	if( ioctl(
	     internal_file->descriptor,
	     (int) control_code,
	     data ) == -1 )
	{
		*error_code = (uint32_t) errno;

		libcerror_system_set_error(
		 error,
		 LIBCERROR_ERROR_DOMAIN_IO,
		 LIBCERROR_IO_ERROR_IOCTL_FAILED,
		 *error_code,
		 "%s: unable to IO control device.",
		 function );

		return( -1 );
	}
	return( (size_t) data_size );
#else
	libcerror_error_set(
	 error,
	 LIBCERROR_ERROR_DOMAIN_RUNTIME,
	 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
	 "%s: missing IO control support.",
	 function );

	return( -1 );

#endif /* defined( HAVE_IOCTL ) || defined( WINAPI ) */
}

/* Read data from a device file using IO control
 * Returns the number of bytes read if successful or -1 on error
 */
ssize_t libcfile_file_io_control_read(
         libcfile_file_t *file,
         uint32_t control_code,
         uint8_t *control_data,
         size_t control_data_size,
         uint8_t *data,
         size_t data_size,
         libcerror_error_t **error )
{
	libcfile_internal_file_t *internal_file = NULL;
	static char *function                   = "libcfile_file_io_control_read";
	ssize_t read_count                      = 0;
	uint32_t error_code                     = 0;

	if( file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
	internal_file = (libcfile_internal_file_t *) file;

	read_count = libcfile_internal_file_io_control_read_with_error_code(
	              internal_file,
	              control_code,
	              control_data,
	              control_data_size,
	              data,
	              data_size,
	              &error_code,
	              error );

	if( read_count == -1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_IO,
		 LIBCERROR_IO_ERROR_IOCTL_FAILED,
		 "%s: unable to to IO control device.",
		 function );

		return( -1 );
	}
	return( read_count );
}

/* Read data from a device file using IO control
 * Returns the number of bytes read if successful or -1 on error
 */
ssize_t libcfile_file_io_control_read_with_error_code(
         libcfile_file_t *file,
         uint32_t control_code,
         uint8_t *control_data,
         size_t control_data_size,
         uint8_t *data,
         size_t data_size,
         uint32_t *error_code,
         libcerror_error_t **error )
{
	libcfile_internal_file_t *internal_file = NULL;
	static char *function                   = "libcfile_file_io_control_read_with_error_code";
	ssize_t read_count                      = 0;

	if( file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
	internal_file = (libcfile_internal_file_t *) file;

	read_count = libcfile_internal_file_io_control_read_with_error_code(
	              internal_file,
	              control_code,
	              control_data,
	              control_data_size,
	              data,
	              data_size,
	              error_code,
	              error );

	if( read_count == -1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_IO,
		 LIBCERROR_IO_ERROR_IOCTL_FAILED,
		 "%s: unable to to IO control device.",
		 function );

		return( -1 );
	}
	return( read_count );
}

/* On some versions of Linux the FADVISE definions seem to be missing from fcntl.h
 */
#if defined( HAVE_POSIX_FADVISE ) && !defined( WINAPI )

#if !defined( POSIX_FADV_NORMAL )
#define POSIX_FADV_NORMAL		0
#endif

#if !defined( POSIX_FADV_RANDOM )
#define POSIX_FADV_RANDOM		1
#endif

#if !defined( POSIX_FADV_SEQUENTIAL )
#define POSIX_FADV_SEQUENTIAL		2
#endif

#endif /* #if defined( HAVE_POSIX_FADVISE ) && !defined( WINAPI ) */

/* Sets the expected access behavior so the system can optimize the access
 * Returns 1 if successful or -1 on error
 */
int libcfile_file_set_access_behavior(
     libcfile_file_t *file,
     int access_behavior,
     libcerror_error_t **error )
{
	libcfile_internal_file_t *internal_file = NULL;
	static char *function                   = "libcfile_file_set_access_behavior";

#if defined( HAVE_POSIX_FADVISE ) && !defined( WINAPI )
	int advice                              = POSIX_FADV_NORMAL;
	int result                              = 0;
#endif

	if( file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
	internal_file = (libcfile_internal_file_t *) file;

#if defined( WINAPI )
	if( internal_file->handle == INVALID_HANDLE_VALUE )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
		 "%s: invalid file - missing handle.",
		 function );

		return( -1 );
	}
#else
	if( internal_file->descriptor == -1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
		 "%s: invalid file - missing descriptor.",
		 function );

		return( -1 );
	}
#endif
	if( ( access_behavior != LIBCFILE_ACCESS_BEHAVIOR_NORMAL )
	 && ( access_behavior != LIBCFILE_ACCESS_BEHAVIOR_RANDOM )
	 && ( access_behavior != LIBCFILE_ACCESS_BEHAVIOR_SEQUENTIAL ) )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_UNSUPPORTED_VALUE,
		 "%s: unsupported access behavior: %d.",
		 function,
		 access_behavior );

		return( -1 );
	}
#if defined( HAVE_POSIX_FADVISE ) && !defined( WINAPI )
	if( access_behavior == LIBCFILE_ACCESS_BEHAVIOR_NORMAL )
	{
		advice = POSIX_FADV_NORMAL;
	}
	else if( access_behavior == LIBCFILE_ACCESS_BEHAVIOR_RANDOM )
	{
		advice = POSIX_FADV_RANDOM;
	}
	else if( access_behavior == LIBCFILE_ACCESS_BEHAVIOR_SEQUENTIAL )
	{
		advice = POSIX_FADV_SEQUENTIAL;
	}
	result = posix_fadvise(
	          internal_file->descriptor,
	          0,
	          0,
	          advice );

	/* Safely ignore if the device does not support fadvise.
	 * Note that FreeBSD 10.0 had a bug and was returning -1
	 * and setting errno.
	 */
	if( ( result != 0 )
	 && ( result != ENODEV ) )
	{
		libcerror_system_set_error(
		 error,
		 LIBCERROR_ERROR_DOMAIN_IO,
		 LIBCERROR_IO_ERROR_GENERIC,
		 errno,
		 "%s: unable to advice file descriptor on access behavior.",
		 function );

		return( -1 );
	}
#endif /* defined( HAVE_POSIX_FADVISE ) && !defined( WINAPI ) */

	return( 1 );
}

/* Determines the block size
 * Returns the 1 if successful, 0 if not or -1 on error
 */
int libcfile_internal_file_determine_block_size(
     libcfile_internal_file_t *internal_file,
     libcerror_error_t **error )
{
#if defined( WINAPI )
#if ( WINVER >= 0x0600 )
	FILE_ALIGNMENT_INFO file_alignment_information;
#else
	DISK_GEOMETRY disk_geometry;
	DISK_GEOMETRY_EX disk_geometry_extended;
#endif
#endif /* defined( WINAPI ) */

	static char *function     = "libcfile_internal_file_get_bytes_per_sector";

#if defined( WINAPI )
	uint32_t bytes_per_sector = 0;
	uint32_t error_code       = 0;
	BOOL result               = 0;

#if ( WINVER < 0x0600 )
	DWORD response_count      = 0;
#endif

#else
#if defined( BLKSSZGET ) || defined( DIOCGSECTORSIZE ) || defined( DKIOCGETBLOCKSIZE )
	ssize_t read_count        = 0;
	uint32_t error_code       = 0;
#endif
#if !defined( BLKSSZGET ) && defined( DIOCGSECTORSIZE )
	u_int bytes_per_sector    = 0;
#else
	uint32_t bytes_per_sector = 0;
#endif
#endif /* defined( WINAPI ) */

	if( internal_file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
#if defined( WINAPI )
#if ( WINVER >= 0x0600 )
	result = GetFileInformationByHandleEx(
		  internal_file->handle,
		  FileAlignmentInfo,
		  (void *) &file_alignment_information,
		  (DWORD) sizeof( FILE_ALIGNMENT_INFO ) );

	if( result == FALSE )
	{
		error_code = (uint32_t) GetLastError();

		libcerror_system_set_error(
		 error,
		 LIBCERROR_ERROR_DOMAIN_IO,
		 LIBCERROR_IO_ERROR_IOCTL_FAILED,
		 error_code,
		 "%s: unable to retrieve file alignment information.",
		 function );

#if defined( HAVE_DEBUG_OUTPUT )
		if( libcnotify_verbose != 0 )
		{
			if( ( error != NULL )
			 && ( *error != NULL ) )
			{
				libcnotify_print_error_backtrace(
				 *error );
			}
		}
#endif
		libcerror_error_free(
		 error );
	}
	else
	{
		if( (size64_t) file_alignment_information.AlignmentRequirement >= (size64_t) UINT32_MAX )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
			 LIBCERROR_ARGUMENT_ERROR_VALUE_OUT_OF_BOUNDS,
			 "%s: invalid file alignment requirement value out of bounds.",
			 function );

			return( -1 );
		}
		bytes_per_sector = (size_t) file_alignment_information.AlignmentRequirement;
	}
#else
	result = DeviceIoControl(
		  internal_file->handle,
		  IOCTL_DISK_GET_DRIVE_GEOMETRY_EX,
		  NULL,
		  0,
		  &disk_geometry_extended,
		  (DWORD) sizeof( DISK_GEOMETRY_EX ),
		  &response_count,
		  NULL );

	if( result == FALSE )
	{
		error_code = (uint32_t) GetLastError();

		if( error_code == ERROR_NOT_SUPPORTED )
		{
		}
		else if( error_code != ERROR_MORE_DATA )
		{
			libcerror_system_set_error(
			 error,
			 LIBCERROR_ERROR_DOMAIN_IO,
			 LIBCERROR_IO_ERROR_IOCTL_FAILED,
			 error_code,
			 "%s: unable to query device for: IOCTL_DISK_GET_DRIVE_GEOMETRY_EX.",
			 function );

#if defined( HAVE_DEBUG_OUTPUT )
			if( libcnotify_verbose != 0 )
			{
				if( ( error != NULL )
				 && ( *error != NULL ) )
				{
					libcnotify_print_error_backtrace(
					 *error );
				}
			}
#endif
			libcerror_error_free(
			 error );
		}
		else if( response_count < sizeof( DISK_GEOMETRY_EX ) )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_VALUE_OUT_OF_BOUNDS,
			 "%s: invalid response count to query: IOCTL_DISK_GET_DRIVE_GEOMETRY_EX value out of bounds.",
			 function );

#if defined( HAVE_DEBUG_OUTPUT )
			if( libcnotify_verbose != 0 )
			{
				if( ( error != NULL )
				 && ( *error != NULL ) )
				{
					libcnotify_print_error_backtrace(
					 *error );
				}
			}
#endif
			libcerror_error_free(
			 error );
		}
		else
		{
			result = TRUE;
		}
	}
	if( result == TRUE )
	{
		bytes_per_sector = (uint32_t) disk_geometry_extended.Geometry.BytesPerSector;
	}
	else if( error_code == ERROR_NOT_SUPPORTED )
	{
		/* A floppy device does not support IOCTL_DISK_GET_DRIVE_GEOMETRY_EX
		 */
		result = DeviceIoControl(
			  internal_file->handle,
			  IOCTL_DISK_GET_DRIVE_GEOMETRY,
			  NULL,
			  0,
			  &disk_geometry,
			  sizeof( DISK_GEOMETRY ),
			  &response_count,
			  NULL );

		if( result == FALSE )
		{
			error_code = (uint32_t) GetLastError();

			if( error_code != ERROR_MORE_DATA )
			{
				libcerror_system_set_error(
				 error,
				 LIBCERROR_ERROR_DOMAIN_IO,
				 LIBCERROR_IO_ERROR_IOCTL_FAILED,
				 error_code,
				 "%s: unable to query device for: IOCTL_DISK_GET_DRIVE_GEOMETRY_EX.",
				 function );

#if defined( HAVE_DEBUG_OUTPUT )
				if( libcnotify_verbose != 0 )
				{
					if( ( error != NULL )
					 && ( *error != NULL ) )
					{
						libcnotify_print_error_backtrace(
						 *error );
					}
				}
#endif
				libcerror_error_free(
				 error );
			}
			else if( response_count < sizeof( DISK_GEOMETRY ) )
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_RUNTIME,
				 LIBCERROR_RUNTIME_ERROR_VALUE_OUT_OF_BOUNDS,
				 "%s: invalid response count to query: IOCTL_DISK_GET_DRIVE_GEOMETRY value out of bounds.",
				 function );

#if defined( HAVE_DEBUG_OUTPUT )
				if( libcnotify_verbose != 0 )
				{
					if( ( error != NULL )
					 && ( *error != NULL ) )
					{
						libcnotify_print_error_backtrace(
						 *error );
					}
				}
#endif
				libcerror_error_free(
				 error );
			}
			else
			{
				result = TRUE;
			}
		}
		if( result == TRUE )
		{
			bytes_per_sector = (uint32_t) disk_geometry.BytesPerSector;
		}
	}
#endif /* ( WINVER >= 0x0600 ) */

#elif defined( BLKSSZGET )
	read_count = libcfile_internal_file_io_control_read_with_error_code(
		      internal_file,
		      BLKSSZGET,
		      NULL,
		      0,
		      (uint8_t *) &bytes_per_sector,
		      4,
		      &error_code,
		      error );

	if( read_count == -1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_IO,
		 LIBCERROR_IO_ERROR_IOCTL_FAILED,
		 "%s: unable to query device for: BLKSSZGET.",
		 function );

#if defined( HAVE_DEBUG_OUTPUT )
		if( libcnotify_verbose != 0 )
		{
			if( ( error != NULL )
			 && ( *error != NULL ) )
			{
				libcnotify_print_error_backtrace(
				 *error );
			}
		}
#endif
		libcerror_error_free(
		 error );
	}
#elif defined( DIOCGSECTORSIZE )
	read_count = libcfile_internal_file_io_control_read_with_error_code(
		      internal_file,
		      DIOCGSECTORSIZE,
		      NULL,
		      0,
		      (uint8_t *) &bytes_per_sector,
		      sizeof( u_int ),
		      &error_code,
		      error );

	if( read_count == -1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_IO,
		 LIBCERROR_IO_ERROR_IOCTL_FAILED,
		 "%s: unable to query device for: DIOCGSECTORSIZE.",
		 function );

#if defined( HAVE_DEBUG_OUTPUT )
		if( libcnotify_verbose != 0 )
		{
			if( ( error != NULL )
			 && ( *error != NULL ) )
			{
				libcnotify_print_error_backtrace(
				 *error );
			}
		}
#endif
		libcerror_error_free(
		 error );
	}
	else if( bytes_per_sector > (u_int) UINT32_MAX )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid bytes per sector value out of bounds.",
		 function );

#if defined( HAVE_DEBUG_OUTPUT )
		if( libcnotify_verbose != 0 )
		{
			if( ( error != NULL )
			 && ( *error != NULL ) )
			{
				libcnotify_print_error_backtrace(
				 *error );
			}
		}
#endif
		libcerror_error_free(
		 error );
	}
#elif defined( DKIOCGETBLOCKSIZE )
	read_count = libcfile_internal_file_io_control_read_with_error_code(
		      internal_file,
		      DKIOCGETBLOCKSIZE,
		      NULL,
		      0,
		      (uint8_t *) &bytes_per_sector,
		      4,
		      &error_code,
		      error );

	if( read_count == -1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_IO,
		 LIBCERROR_IO_ERROR_IOCTL_FAILED,
		 "%s: unable to query device for: DKIOCGETBLOCKSIZE.",
		 function );

#if defined( HAVE_DEBUG_OUTPUT )
		if( libcnotify_verbose != 0 )
		{
			if( ( error != NULL )
			 && ( *error != NULL ) )
			{
				libcnotify_print_error_backtrace(
				 *error );
			}
		}
#endif
		libcerror_error_free(
		 error );
	}
#endif
#if defined( HAVE_DEBUG_OUTPUT )
	if( libcnotify_verbose != 0 )
	{
		libcnotify_printf(
		 "%s: bytes per sector: %" PRIu32 "\n",
		 function,
		 bytes_per_sector );
	}
#endif
	if( bytes_per_sector != 0 )
	{
		if( libcfile_internal_file_set_block_size(
		     internal_file,
		     (size_t) bytes_per_sector,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
			 "%s: unable to set block size.",
			 function );

			return( -1 );
		}
	}
	return( 1 );
}

/* Sets the block size for the read and seek operations
 * A block size of 0 represents no block-based operations
 * The total size must be a multitude of block size
 * Returns 1 if successful or -1 on error
 */
int libcfile_internal_file_set_block_size(
     libcfile_internal_file_t *internal_file,
     size_t block_size,
     libcerror_error_t **error )
{
	static char *function = "libcfile_internal_file_set_block_size";

	if( internal_file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
#if defined( WINAPI ) && ( UINT32_MAX < SSIZE_MAX )
	if( block_size > (size_t) UINT32_MAX )
#else
	if( block_size > (size_t) SSIZE_MAX )
#endif
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_EXCEEDS_MAXIMUM,
		 "%s: invalid block size value exceeds maximum.",
		 function );

		return( -1 );
	}
	if( internal_file->block_data != NULL )
	{
		if( block_size != internal_file->block_size )
		{
			memory_free(
			 internal_file->block_data );

			internal_file->block_data      = NULL;
			internal_file->block_data_size = 0;
		}
	}
	if( internal_file->block_data == NULL )
	{
		if( block_size > 0 )
		{
#if defined( __FreeBSD__ ) || defined( __NetBSD__ ) || defined( __OpenBSD__ )
			if( posix_memalign(
			     (void **) &( internal_file->block_data ),
			     block_size,
			     block_size ) != 0 )
#else
			internal_file->block_data = (uint8_t *) memory_allocate(
			                                         block_size );

			if( internal_file->block_data == NULL )
#endif
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_MEMORY,
				 LIBCERROR_MEMORY_ERROR_INSUFFICIENT,
				 "%s: unable to create block data.",
				 function );

				return( -1 );
			}
			if( memory_set(
			     internal_file->block_data,
			     0,
			     block_size ) == NULL )
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_MEMORY,
				 LIBCERROR_MEMORY_ERROR_SET_FAILED,
				 "%s: unable to clear block data.",
				 function );

				return( -1 );
			}
		}
		internal_file->block_size = block_size;
	}
	return( 1 );
}

/* Sets the block size for the read and seek operations
 * A block size of 0 represents no block-based operations
 * The total size must be a multitude of block size
 * Returns 1 if successful or -1 on error
 */
int libcfile_file_set_block_size(
     libcfile_file_t *file,
     size_t block_size,
     libcerror_error_t **error )
{
	libcfile_internal_file_t *internal_file = NULL;
	static char *function                   = "libcfile_file_set_block_size";

	if( file == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid file.",
		 function );

		return( -1 );
	}
	internal_file = (libcfile_internal_file_t *) file;

	if( ( internal_file->access_flags & LIBCFILE_ACCESS_FLAG_WRITE ) != 0 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_UNSUPPORTED_VALUE,
		 "%s: setting block size not supported with write access.",
		 function );

		return( -1 );
	}
#if defined( WINAPI )
	if( internal_file->handle == INVALID_HANDLE_VALUE )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
		 "%s: invalid file - missing handle.",
		 function );

		return( -1 );
	}
#else
	if( internal_file->descriptor == -1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
		 "%s: invalid file - missing descriptor.",
		 function );

		return( -1 );
	}
#endif
#if defined( WINAPI ) && ( UINT32_MAX < SSIZE_MAX )
	if( block_size > (size_t) UINT32_MAX )
#else
	if( block_size > (size_t) SSIZE_MAX )
#endif
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_EXCEEDS_MAXIMUM,
		 "%s: invalid block size value exceeds maximum.",
		 function );

		return( -1 );
	}
	if( ( block_size != 0 )
	 && ( ( internal_file->size % block_size ) != 0 ) )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid block size value out of bounds.",
		 function );

		return( -1 );
	}
	if( libcfile_internal_file_set_block_size(
	     internal_file,
	     block_size,
	     error ) != 1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
		 "%s: unable to set block size.",
		 function );

		return( -1 );
	}
	return( 1 );
}

