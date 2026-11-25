/*
 * Path functions
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
#include <types.h>
#include <wide_string.h>

#if defined( WINAPI ) && ( WINVER <= 0x0500 )
#include <system_string.h>
#endif

#if defined( HAVE_ERRNO_H )
#include <errno.h>
#endif

#if defined( HAVE_SYS_STAT_H )
#include <sys/stat.h>
#endif

#if defined( HAVE_LIMITS_H ) || defined( WINAPI )
/* Include for PATH_MAX */
#include <limits.h>
#endif

#if defined( HAVE_UNISTD_H )
#include <unistd.h>
#endif

#include "libcpath_definitions.h"
#include "libcpath_libcerror.h"
#include "libcpath_libcsplit.h"
#include "libcpath_path.h"
#include "libcpath_system_string.h"

#if defined( WINAPI ) && ( WINVER <= 0x0500 )

/* Cross Windows safe version of CloseHandle
 * Returns TRUE if successful or FALSE on error
 */
BOOL libcpath_CloseHandle(
      HANDLE file_handle )
{
	FARPROC function       = NULL;
	HMODULE library_handle = NULL;
	BOOL result            = FALSE;

	if( file_handle == NULL )
	{
		return( FALSE );
	}
	library_handle = LoadLibrary(
	                  _SYSTEM_STRING( "kernel32.dll" ) );

	if( library_handle == NULL )
	{
		return( FALSE );
	}
	function = GetProcAddress(
		    library_handle,
		    (LPCSTR) "CloseHandle" );

	if( function != NULL )
	{
		result = function(
			  file_handle );
	}
	/* This call should be after using the function
	 * in most cases kernel32.dll will still be available after free
	 */
	if( FreeLibrary(
	     library_handle ) != TRUE )
	{
		result = FALSE;
	}
	return( result );
}

#endif /* defined( WINAPI ) && ( WINVER <= 0x0500 ) */

#if defined( WINAPI ) && ( WINVER <= 0x0500 )

/* Cross Windows safe version of SetCurrentDirectoryA
 * Returns TRUE if successful or FALSE on error
 */
BOOL libcpath_SetCurrentDirectoryA(
      LPCSTR path )
{
	FARPROC function       = NULL;
	HMODULE library_handle = NULL;
	BOOL result            = FALSE;

	if( path == NULL )
	{
		return( FALSE );
	}
	library_handle = LoadLibrary(
	                  _SYSTEM_STRING( "kernel32.dll" ) );

	if( library_handle == NULL )
	{
		return( FALSE );
	}
	function = GetProcAddress(
		    library_handle,
		    (LPCSTR) "SetCurrentDirectoryA" );

	if( function != NULL )
	{
		result = function(
			  path );
	}
	/* This call should be after using the function
	 * in most cases kernel32.dll will still be available after free
	 */
	if( FreeLibrary(
	     library_handle ) != TRUE )
	{
		libcpath_CloseHandle(
		 library_handle );

		return( FALSE );
	}
	return( result );
}

#endif /* defined( WINAPI ) && ( WINVER <= 0x0500 ) */

#if defined( WINAPI )

/* Changes the directory
 * This function uses the WINAPI function for Windows XP (0x0501) or later
 * or tries to dynamically call the function for Windows 2000 (0x0500) or earlier
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_change_directory(
     const char *directory_name,
     libcerror_error_t **error )
{
	static char *function = "libcpath_path_change_directory";
	DWORD error_code      = 0;

	if( directory_name == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid directory name.",
		 function );

		return( -1 );
	}
#if WINVER <= 0x0500
	if( libcpath_SetCurrentDirectoryA(
	     directory_name ) == 0 )
#else
	if( SetCurrentDirectoryA(
	     directory_name ) == 0 )
#endif
	{
		error_code = GetLastError();

		libcerror_system_set_error(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
		 error_code,
		 "%s: unable to change directory.",
		 function );

		return( -1 );
	}
	return( 1 );
}

#elif defined( HAVE_CHDIR )

/* Changes the directory
 * This function uses the POSIX chdir function or equivalent
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_change_directory(
     const char *directory_name,
     libcerror_error_t **error )
{
	static char *function = "libcpath_path_change_directory";

	if( directory_name == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid directory name.",
		 function );

		return( -1 );
	}
	if( chdir(
	     directory_name ) != 0 )
	{
		libcerror_system_set_error(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
		 errno,
		 "%s: unable to change directory.",
		 function );

		return( -1 );
	}
	return( 1 );
}

#else
#error Missing change directory function
#endif

#if defined( WINAPI ) && ( WINVER <= 0x0500 )

/* Cross Windows safe version of GetCurrentDirectoryA
 * Returns the number of characters in the current directory string or 0 on error
 */
DWORD libcpath_GetCurrentDirectoryA(
       DWORD buffer_size,
       LPCSTR buffer )
{
	FARPROC function       = NULL;
	HMODULE library_handle = NULL;
	DWORD result           = 0;

	library_handle = LoadLibrary(
	                  _SYSTEM_STRING( "kernel32.dll" ) );

	if( library_handle == NULL )
	{
		return( 0 );
	}
	function = GetProcAddress(
		    library_handle,
		    (LPCSTR) "GetCurrentDirectoryA" );

	if( function != NULL )
	{
		result = function(
			  buffer_size,
			  buffer );
	}
	/* This call should be after using the function
	 * in most cases kernel32.dll will still be available after free
	 */
	if( FreeLibrary(
	     library_handle ) != TRUE )
	{
		libcpath_CloseHandle(
		 library_handle );

		return( 0 );
	}
	return( result );
}

#endif /* defined( WINAPI ) && ( WINVER <= 0x0500 ) */

#if defined( WINAPI )

/* Retrieves the current working directory
 * This function uses the WINAPI function for Windows XP (0x0501) or later
 * or tries to dynamically call the function for Windows 2000 (0x0500) or earlier
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_get_current_working_directory(
     char **current_working_directory,
     size_t *current_working_directory_size,
     libcerror_error_t **error )
{
	static char *function                     = "libcpath_path_get_current_working_directory";
	char *safe_current_working_directory      = NULL;
	DWORD error_code                          = 0;
	DWORD result                              = 0;
	DWORD safe_current_working_directory_size = 0;

	if( current_working_directory == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid current working directory.",
		 function );

		return( -1 );
	}
	if( *current_working_directory != NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_ALREADY_SET,
		 "%s: invalid current working directory value already set.",
		 function );

		return( -1 );
	}
	if( current_working_directory_size == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid current working directory size.",
		 function );

		return( -1 );
	}
#if WINVER <= 0x0500
	safe_current_working_directory_size = libcpath_GetCurrentDirectoryA(
	                                       0,
	                                       NULL );
#else
	safe_current_working_directory_size = GetCurrentDirectoryA(
	                                       0,
	                                       NULL );
#endif
	if( safe_current_working_directory_size == 0 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
		 "%s: unable to retrieve current working directory size.",
		 function );

		goto on_error;
	}
	if( (size_t) safe_current_working_directory_size > (size_t) SSIZE_MAX )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid current working directory size value out of bounds.",
		 function );

		goto on_error;
	}
	safe_current_working_directory = narrow_string_allocate(
	                                  safe_current_working_directory_size );

	if( safe_current_working_directory == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_INSUFFICIENT,
		 "%s: unable to create current working directory.",
		 function );

		goto on_error;
	}
	if( memory_set(
	     safe_current_working_directory,
	     0,
	     sizeof( char ) * safe_current_working_directory_size ) == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_SET_FAILED,
		 "%s: unable to clear current working directory.",
		 function );

		goto on_error;
	}
#if WINVER <= 0x0500
	result = libcpath_GetCurrentDirectoryA(
	          safe_current_working_directory_size,
	          safe_current_working_directory );
#else
	result = GetCurrentDirectoryA(
	          safe_current_working_directory_size,
	          safe_current_working_directory );
#endif
	/* Note that safe_current_working_directory_size can be larger than result
	 */
	if( ( result == 0 )
	 || ( result > safe_current_working_directory_size ) )
	{
		error_code = GetLastError();

		libcerror_system_set_error(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
		 error_code,
		 "%s: unable to retrieve current working directory.",
		 function );

		goto on_error;
	}
	*current_working_directory      = safe_current_working_directory;
	*current_working_directory_size = (size_t) safe_current_working_directory_size;

	return( 1 );

on_error:
	if( safe_current_working_directory != NULL )
	{
		memory_free(
		 safe_current_working_directory );
	}
	return( -1 );
}

#elif defined( HAVE_GETCWD )

/* Retrieves the current working directory
 * This function uses the POSIX getcwd function or equivalent
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_get_current_working_directory(
     char **current_working_directory,
     size_t *current_working_directory_size,
     libcerror_error_t **error )
{
	static char *function                      = "libcpath_path_get_current_working_directory";
	char *safe_current_working_directory       = NULL;
	size_t safe_current_working_directory_size = 0;

	if( current_working_directory == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid current working directory.",
		 function );

		return( -1 );
	}
	if( *current_working_directory != NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_ALREADY_SET,
		 "%s: invalid current working directory value already set.",
		 function );

		return( -1 );
	}
	if( current_working_directory_size == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid current working directory size.",
		 function );

		return( -1 );
	}
	safe_current_working_directory_size = PATH_MAX;

	safe_current_working_directory = narrow_string_allocate(
	                                  safe_current_working_directory_size );

	if( safe_current_working_directory == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_INSUFFICIENT,
		 "%s: unable to create current working directory.",
		 function );

		goto on_error;
	}
	if( memory_set(
	     safe_current_working_directory,
	     0,
	     sizeof( char ) * safe_current_working_directory_size ) == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_SET_FAILED,
		 "%s: unable to clear current working directory.",
		 function );

		goto on_error;
	}
	if( getcwd(
	     safe_current_working_directory,
	     safe_current_working_directory_size ) == NULL )
	{
		libcerror_system_set_error(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
		 errno,
		 "%s: unable to retrieve current working directory.",
		 function );

		goto on_error;
	}
	*current_working_directory      = safe_current_working_directory;
	*current_working_directory_size = safe_current_working_directory_size;

	return( 1 );

on_error:
	if( safe_current_working_directory != NULL )
	{
		memory_free(
		 safe_current_working_directory );
	}
	return( -1 );
}

#else
#error Missing get current working directory function
#endif

#if defined( WINAPI ) && ( WINVER <= 0x0500 )

/* Cross Windows safe version of GetFullPathNameA
 * Returns the number of characters in the current directory string or 0 on error
 */
DWORD libcpath_GetFullPathNameA(
       LPCSTR filename,
       DWORD  buffer_size,
       LPSTR  buffer,
       LPSTR  *file_part )
{
	FARPROC function       = NULL;
	HMODULE library_handle = NULL;
	DWORD result           = 0;

	library_handle = LoadLibrary(
	                  _SYSTEM_STRING( "kernel32.dll" ) );

	if( library_handle == NULL )
	{
		return( 0 );
	}
	function = GetProcAddress(
		    library_handle,
		    (LPCSTR) "GetFullPathNameA" );

	if( function != NULL )
	{
		result = function(
			  filename,
			  buffer_size,
			  buffer,
			  file_part );
	}
	/* This call should be after using the function
	 * in most cases kernel32.dll will still be available after free
	 */
	if( FreeLibrary(
	     library_handle ) != TRUE )
	{
		libcpath_CloseHandle(
		 library_handle );

		return( 0 );
	}
	return( result );
}

#endif /* defined( WINAPI ) && ( WINVER <= 0x0500 ) */

#if defined( WINAPI )

/* Determines the path type
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_get_path_type(
     const char *path,
     size_t path_length,
     uint8_t *path_type,
     libcerror_error_t **error )
{
	static char *function = "libcpath_path_get_path_type";

	if( path == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid path.",
		 function );

		return( -1 );
	}
	if( ( path_length == 0 )
	 || ( path_length > (size_t) ( SSIZE_MAX - 1 ) ) )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid path length value out of bounds.",
		 function );

		return( -1 );
	}
	if( path_type == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid path type.",
		 function );

		return( -1 );
	}
	*path_type = LIBCPATH_TYPE_RELATIVE;

	/* Determine if the path is a special path
	 * device path prefix:          \\.\
	 * extended-length path prefix: \\?\
	 */
	if( ( path_length >= 4 )
	 && ( path[ 0 ] == '\\' )
	 && ( path[ 1 ] == '\\' )
	 && ( ( path[ 2 ] == '.' )
	  ||  ( path[ 2 ] == '?' ) )
	 && ( path[ 3 ] == '\\' ) )
	{
		if( path[ 2 ] == '.' )
		{
			*path_type = LIBCPATH_TYPE_DEVICE;
		}
		/* Determine if the path in an extended-length UNC path
		 * \\?\UNC\server\share
		 */
		else if( ( path_length >= 8 )
		      && ( path[ 4 ] == 'U' )
		      && ( path[ 5 ] == 'N' )
		      && ( path[ 6 ] == 'C' )
		      && ( path[ 7 ] == '\\' ) )
		{
			*path_type = LIBCPATH_TYPE_EXTENDED_LENGTH_UNC;
		}
		else
		{
			*path_type = LIBCPATH_TYPE_EXTENDED_LENGTH;
		}
	}
	/* Determine if the path is an UNC path
	 * \\server\share
	 */
	else if( ( path_length >= 2 )
	      && ( path[ 0 ] == '\\' )
	      && ( path[ 1 ] == '\\' ) )
	{
		*path_type = LIBCPATH_TYPE_UNC;
	}
	else if( path[ 0 ] == '\\' )
	{
		*path_type = LIBCPATH_TYPE_ABSOLUTE;
	}
	else if( ( path_length >= 3 )
	      && ( path[ 1 ] == ':' )
	      && ( path[ 2 ] == '\\' )
	      && ( ( ( path[ 0 ] >= 'A' )
	        &&   ( path[ 0 ] <= 'Z' ) )
	       ||  ( ( path[ 0 ] >= 'a' )
	        &&   ( path[ 0 ] <= 'z' ) ) ) )
	{
		*path_type = LIBCPATH_TYPE_ABSOLUTE;
	}
	return( 1 );
}

/* Determines the volume name
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_get_volume_name(
     const char *path,
     size_t path_length,
     char **volume_name,
     size_t *volume_name_length,
     size_t *directory_name_index,
     libcerror_error_t **error )
{
	static char *function    = "libcpath_path_get_volume_name";
	size_t path_index        = 0;
	size_t share_name_index  = 0;
	size_t volume_name_index = 0;

	if( path == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid path.",
		 function );

		return( -1 );
	}
	if( ( path_length == 0 )
	 || ( path_length > (size_t) ( SSIZE_MAX - 1 ) ) )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid path length value out of bounds.",
		 function );

		return( -1 );
	}
	if( volume_name == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid volume name.",
		 function );

		return( -1 );
	}
	if( volume_name_length == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid volume name length.",
		 function );

		return( -1 );
	}
	if( directory_name_index == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid directory name index.",
		 function );

		return( -1 );
	}
	*volume_name          = NULL;
	*volume_name_length   = 0;
	*directory_name_index = 0;

	/* Determine if the path is a special path
	 * device path prefix:          \\.\
	 * extended-length path prefix: \\?\
	 */
	if( ( path_length >= 4 )
	 && ( path[ 0 ] == '\\' )
	 && ( path[ 1 ] == '\\' )
	 && ( ( path[ 2 ] == '.' )
	  ||  ( path[ 2 ] == '?' ) )
	 && ( path[ 3 ] == '\\' ) )
	{
		if( path[ 2 ] == '.' )
		{
			volume_name_index = 4;
		}
		/* Determine if the path in an extended-length UNC path
		 * \\?\UNC\server\share
		 */
		else if( ( path_length >= 8 )
		      && ( path[ 4 ] == 'U' )
		      && ( path[ 5 ] == 'N' )
		      && ( path[ 6 ] == 'C' )
		      && ( path[ 7 ] == '\\' ) )
		{
			volume_name_index = 8;
		}
		else
		{
			volume_name_index = 4;
		}
	}
	/* Determine if the path is an UNC path
	 * \\server\share
	 */
	else if( ( path_length >= 2 )
	      && ( path[ 0 ] == '\\' )
	      && ( path[ 1 ] == '\\' ) )
	{
		volume_name_index = 2;
	}
	/* Check if the path contains a volume letter
	 */
	if( ( path_length >= 2 )
	 && ( volume_name_index <= ( path_length - 2 ) )
	 && ( path[ volume_name_index + 1 ] == ':' )
	 && ( ( ( path[ volume_name_index ] >= 'A' )
	   &&   ( path[ volume_name_index ] <= 'Z' ) )
	  ||  ( ( path[ volume_name_index ] >= 'a' )
	   &&   ( path[ volume_name_index ] <= 'z' ) ) ) )
	{
		path_index = volume_name_index + 2;

		if( ( path_index < path_length )
		 && ( path[ path_index ] == '\\' ) )
		{
			path_index++;
		}
		*volume_name        = (char *) &( path[ volume_name_index ] );
		*volume_name_length = path_index - volume_name_index;

		if( path[ path_index - 1 ] == '\\' )
		{
			*volume_name_length -= 1;
		}
		*directory_name_index = path_index;
	}
	else if( volume_name_index == 4 )
	{
		for( path_index = volume_name_index;
		     path_index < path_length;
		     path_index++ )
		{
			if( path[ path_index ] == '\\' )
			{
				path_index++;

				break;
			}
		}
		*volume_name        = (char *) &( path[ 4 ] );
		*volume_name_length = path_index - 4;

		if( path[ path_index - 1 ] == '\\' )
		{
			*volume_name_length -= 1;
		}
		*directory_name_index = path_index;
	}
	else if( ( volume_name_index == 2 )
	      || ( volume_name_index == 8 ) )
	{
		for( share_name_index = volume_name_index;
		     share_name_index < path_length;
		     share_name_index++ )
		{
			if( path[ share_name_index ] == '\\' )
			{
				share_name_index++;

				break;
			}
		}
		if( share_name_index > path_length )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
			 "%s: invalid path - missing share name.",
			 function );

			return( -1 );
		}
		for( path_index = share_name_index;
		     path_index < path_length;
		     path_index++ )
		{
			if( path[ path_index ] == '\\' )
			{
				path_index++;

				break;
			}
		}
		*volume_name        = (char *) &( path[ volume_name_index ] );
		*volume_name_length = path_index - volume_name_index;

		if( path[ path_index - 1 ] == '\\' )
		{
			*volume_name_length -= 1;
		}
		*directory_name_index = path_index;
	}
	return( 1 );
}

/* Retrieves the current working directory of a specific volume
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_get_current_working_directory_by_volume(
     char *volume_name,
     size_t volume_name_length,
     char **current_working_directory,
     size_t *current_working_directory_size,
     libcerror_error_t **error )
{
	static char *function                     = "libcpath_path_get_current_working_directory_by_volume";
	char *safe_current_working_directory      = NULL;
	DWORD error_code                          = 0;
	DWORD result                              = 0;
	DWORD safe_current_working_directory_size = 0;

	if( volume_name == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid volume name.",
		 function );

		return( -1 );
	}
	if( ( volume_name_length == 0 )
	 || ( volume_name_length > (size_t) ( SSIZE_MAX - 1 ) ) )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid volume name length value out of bounds.",
		 function );

		return( -1 );
	}
	if( current_working_directory == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid current working directory.",
		 function );

		return( -1 );
	}
	if( *current_working_directory != NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_ALREADY_SET,
		 "%s: invalid current working directory value already set.",
		 function );

		return( -1 );
	}
	if( current_working_directory_size == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid current working directory size.",
		 function );

		return( -1 );
	}
#if WINVER <= 0x0500
	safe_current_working_directory_size = libcpath_GetFullPathNameA(
	                                       volume_name,
	                                       0,
	                                       NULL,
	                                       NULL );
#else
	safe_current_working_directory_size = GetFullPathNameA(
	                                       volume_name,
	                                       0,
	                                       NULL,
	                                       NULL );
#endif
	if( safe_current_working_directory_size == 0 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
		 "%s: unable to retrieve current working directory size.",
		 function );

		goto on_error;
	}
	if( (size_t) safe_current_working_directory_size > (size_t) SSIZE_MAX )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid current working directory size value out of bounds.",
		 function );

		goto on_error;
	}
	safe_current_working_directory = narrow_string_allocate(
	                                  safe_current_working_directory_size );

	if( safe_current_working_directory == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_INSUFFICIENT,
		 "%s: unable to create current working directory.",
		 function );

		goto on_error;
	}
	if( memory_set(
	     safe_current_working_directory,
	     0,
	     sizeof( char ) * safe_current_working_directory_size ) == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_SET_FAILED,
		 "%s: unable to clear current working directory.",
		 function );

		goto on_error;
	}
#if WINVER <= 0x0500
	result = libcpath_GetFullPathNameA(
	          volume_name,
	          safe_current_working_directory_size,
	          safe_current_working_directory,
	          NULL );
#else
	result = GetFullPathNameA(
	          volume_name,
	          safe_current_working_directory_size,
	          safe_current_working_directory,
	          NULL );
#endif
	/* Note that safe_current_working_directory_size can be larger than result
	 */
	if( ( result == 0 )
	 || ( result > safe_current_working_directory_size ) )
	{
		error_code = GetLastError();

		libcerror_system_set_error(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
		 error_code,
		 "%s: unable to retrieve current working directory.",
		 function );

		goto on_error;
	}
	*current_working_directory      = safe_current_working_directory;
	*current_working_directory_size = (size_t) safe_current_working_directory_size;

	return( 1 );

on_error:
	if( safe_current_working_directory != NULL )
	{
		memory_free(
		 safe_current_working_directory );
	}
	return( -1 );
}

/* Determines the full path of the Windows path specified
 * The function uses the extended-length path format
 * (path with \\?\ prefix)
 *
 * Multiple successive \ not at the start of the path are combined into one
 *
 * Scenario's that are considered full paths:
 * Device path:			\\.\PhysicalDrive0
 * Extended-length path:	\\?\C:\directory\file.txt
 * Extended-length UNC path:	\\?\UNC\server\share\directory\file.txt
 *
 * Scenario's that are not considered full paths:
 * Local 'absolute' path:	\directory\file.txt
 *                       	\directory\\file.txt
 * Local 'relative' path:	..\directory\file.txt
 * Local 'relative' path:	.\directory\file.txt
 * Volume 'absolute' path:	C:\directory\file.txt
 *                              C:\..\directory\file.txt
 * Volume 'relative' path:	C:directory\file.txt
 * UNC path:			\\server\share\directory\file.txt
 *
 * TODO handle:
 * Volume device path:		\\.\C:
 * Volume file system path:	\\.\C:\
 *
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_get_full_path(
     const char *path,
     size_t path_length,
     char **full_path,
     size_t *full_path_size,
     libcerror_error_t **error )
{
	libcsplit_narrow_split_string_t *current_directory_split_string = NULL;
	libcsplit_narrow_split_string_t *path_split_string              = NULL;
	char *current_directory                                         = NULL;
	char *current_directory_string_segment                          = NULL;
	char *full_path_prefix                                          = NULL;
	char *last_used_path_string_segment                             = NULL;
	char *path_string_segment                                       = NULL;
	char *safe_full_path                                            = NULL;
	char *volume_name                                               = NULL;
	static char *function                                           = "libcpath_path_get_full_path";
	size_t current_directory_length                                 = 0;
	size_t current_directory_name_index                             = 0;
	size_t current_directory_size                                   = 0;
	size_t current_directory_string_segment_size                    = 0;
	size_t full_path_index                                          = 0;
	size_t full_path_prefix_length                                  = 0;
	size_t last_used_path_string_segment_size                       = 0;
	size_t path_directory_name_index                                = 0;
	size_t path_string_segment_length                               = 0;
	size_t path_string_segment_size                                 = 0;
	size_t safe_full_path_size                                      = 0;
	size_t volume_name_length                                       = 0;
	uint8_t path_type                                               = LIBCPATH_TYPE_RELATIVE;
	int current_directory_number_of_segments                        = 0;
	int current_directory_segment_index                             = 0;
	int last_used_path_segment_index                                = -1;
	int path_number_of_segments                                     = 0;
	int path_segment_index                                          = 0;
	int result                                                      = 0;

	if( path == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid path.",
		 function );

		return( -1 );
	}
	if( ( path_length == 0 )
	 || ( path_length > (size_t) ( SSIZE_MAX - 1 ) ) )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid path length value out of bounds.",
		 function );

		return( -1 );
	}
	if( full_path == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid full path.",
		 function );

		return( -1 );
	}
	if( *full_path != NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_ALREADY_SET,
		 "%s: invalid full path value already set.",
		 function );

		return( -1 );
	}
	if( full_path_size == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid full path size.",
		 function );

		return( -1 );
	}
	if( libcpath_path_get_path_type(
	     path,
	     path_length,
	     &path_type,
	     error ) != 1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
		 "%s: unable to determine path type.",
		 function );

		goto on_error;
	}
	if( libcpath_path_get_volume_name(
	     path,
	     path_length,
	     &volume_name,
	     &volume_name_length,
	     &path_directory_name_index,
	     error ) != 1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
		 "%s: unable to determine volume name.",
		 function );

		goto on_error;
	}
	/* If the path is a device path, an extended-length path or an UNC
	 * do not bother to lookup the current working directory
	 */
	if( ( path_type != LIBCPATH_TYPE_DEVICE )
	 && ( path_type != LIBCPATH_TYPE_EXTENDED_LENGTH )
	 && ( path_type != LIBCPATH_TYPE_EXTENDED_LENGTH_UNC )
	 && ( path_type != LIBCPATH_TYPE_UNC ) )
	{
		if( volume_name == NULL )
		{
			result = libcpath_path_get_current_working_directory(
			          &current_directory,
			          &current_directory_size,
			          error );
		}
		else
		{
			result = libcpath_path_get_current_working_directory_by_volume(
			          volume_name,
			          volume_name_length,
			          &current_directory,
			          &current_directory_size,
			          error );
		}
		if( result != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
			 "%s: unable to retrieve current working directory.",
			 function );

			goto on_error;
		}
		if( volume_name == NULL )
		{
			/* Determine the volume name using the current working directory if necessary
			 */
			if( libcpath_path_get_volume_name(
			     current_directory,
			     current_directory_size - 1,
			     &volume_name,
			     &volume_name_length,
			     &current_directory_name_index,
			     error ) != 1 )
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_RUNTIME,
				 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
				 "%s: unable to determine volume name from current working directory.",
				 function );

				goto on_error;
			}
		}
	}
	/* The full path prefix consists of "\\?\" or "\\.\"
	 */
	full_path_prefix_length = 4;

	if( volume_name != NULL )
	{
		/* Add the volume or device name
		 */
		full_path_prefix_length += volume_name_length;
	}
	if( ( path_type == LIBCPATH_TYPE_EXTENDED_LENGTH_UNC )
	 || ( path_type == LIBCPATH_TYPE_UNC ) )
	{
		/* Add the "UNC\" prefix
		 */
		full_path_prefix_length += 4;
	}
	if( ( current_directory != NULL )
	 && ( current_directory_name_index < current_directory_size ) )
	{
		current_directory_length = narrow_string_length(
		                            &( current_directory[ current_directory_name_index ] ) );

		if( libcsplit_narrow_string_split(
		     &( current_directory[ current_directory_name_index ] ),
		     current_directory_length + 1,
		     '\\',
		     &current_directory_split_string,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_INITIALIZE_FAILED,
			 "%s: unable to split current working directory.",
			 function );

			goto on_error;
		}
		if( libcsplit_narrow_split_string_get_number_of_segments(
		     current_directory_split_string,
		     &current_directory_number_of_segments,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
			 "%s: unable to retrieve number of current working directory string segments.",
			 function );

			goto on_error;
		}
		current_directory_segment_index = current_directory_number_of_segments - 1;

		if( path_type == LIBCPATH_TYPE_RELATIVE )
		{
			/* If the path is relative
			 * add the size of the current working directory
			 * a directory separator, if necessary
			 */
			safe_full_path_size += ( current_directory_size - ( current_directory_name_index + 1 ) );

			if( ( current_directory_size >= 2 )
			 && ( current_directory[ current_directory_size - 2 ] != '\\' ) )
			{
				safe_full_path_size += 1;
			}
		}
	}
	if( libcsplit_narrow_string_split(
	     &( path[ path_directory_name_index ] ),
	     path_length - path_directory_name_index + 1,
	     '\\',
	     &path_split_string,
	     error ) != 1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_INITIALIZE_FAILED,
		 "%s: unable to split path.",
		 function );

		goto on_error;
	}
	if( path_split_string != NULL )
	{
		if( libcsplit_narrow_split_string_get_number_of_segments(
		     path_split_string,
		     &path_number_of_segments,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
			 "%s: unable to retrieve number of path string segments.",
			 function );

			goto on_error;
		}
		for( path_segment_index = 0;
		     path_segment_index < path_number_of_segments;
		     path_segment_index++ )
		{
			if( libcsplit_narrow_split_string_get_segment_by_index(
			     path_split_string,
			     path_segment_index,
			     &path_string_segment,
			     &path_string_segment_size,
			     error ) != 1 )
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_RUNTIME,
				 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
				 "%s: unable to retrieve path string segment: %d.",
				 function,
				 path_segment_index );

				goto on_error;
			}
			if( path_string_segment == NULL )
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_RUNTIME,
				 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
				 "%s: missing path string segment: %d.",
				 function,
				 path_segment_index );

				goto on_error;
			}
			/* If the path is "" (empty) or "." (current) ignore the entry
			 */
			if( ( path_string_segment_size <= 1 )
			 || ( ( path_string_segment_size == 2 )
			  &&  ( path_string_segment[ 0 ] == '.' ) ) )
			{
				if( libcsplit_narrow_split_string_set_segment_by_index(
				     path_split_string,
				     path_segment_index,
				     NULL,
				     0,
				     error ) != 1 )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
					 "%s: unable to set path string segment: %d.",
					 function,
					 path_segment_index );

					goto on_error;
				}
			}
			/* If the path is ".." (parent) reverse the current path by one directory
			 */
			else if( ( path_string_segment_size == 3 )
			      && ( path_string_segment[ 0 ] == '.' )
			      && ( path_string_segment[ 1 ] == '.' ) )
			{
				if( ( current_directory_split_string != NULL )
				 && ( last_used_path_segment_index == -1 ) )
				{
					if( libcsplit_narrow_split_string_get_segment_by_index(
					     current_directory_split_string,
					     current_directory_segment_index,
					     &current_directory_string_segment,
					     &current_directory_string_segment_size,
					     error ) != 1 )
					{
						libcerror_error_set(
						 error,
						 LIBCERROR_ERROR_DOMAIN_RUNTIME,
						 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
						 "%s: unable to retrieve current working directory string segment: %d.",
						 function,
						 current_directory_segment_index );

						goto on_error;
					}
					if( current_directory_string_segment == NULL )
					{
						libcerror_error_set(
						 error,
						 LIBCERROR_ERROR_DOMAIN_RUNTIME,
						 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
						 "%s: missing current working directory string segment: %d.",
						 function,
						 current_directory_segment_index );

						goto on_error;
					}
					/* Remove the size of the parent directory name and a directory separator
					 * Note that the size includes the end of string character
					 */
					if( current_directory_string_segment_size < safe_full_path_size )
					{
						safe_full_path_size -= current_directory_string_segment_size;
					}
					else
					{
						safe_full_path_size = 0;
					}
					if( libcsplit_narrow_split_string_set_segment_by_index(
					     current_directory_split_string,
					     current_directory_segment_index,
					     NULL,
					     0,
					     error ) != 1 )
					{
						libcerror_error_set(
						 error,
						 LIBCERROR_ERROR_DOMAIN_RUNTIME,
						 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
						 "%s: unable to set current working directory string segment: %d.",
						 function,
						 current_directory_segment_index );

						goto on_error;
					}
					current_directory_segment_index--;
				}
				else if( last_used_path_segment_index >= 0 )
				{
					if( libcsplit_narrow_split_string_get_segment_by_index(
					     path_split_string,
					     last_used_path_segment_index,
					     &last_used_path_string_segment,
					     &last_used_path_string_segment_size,
					     error ) != 1 )
					{
						libcerror_error_set(
						 error,
						 LIBCERROR_ERROR_DOMAIN_RUNTIME,
						 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
						 "%s: unable to retrieve last used path string segment: %d.",
						 function,
						 last_used_path_segment_index );

						goto on_error;
					}
					if( last_used_path_string_segment == NULL )
					{
						libcerror_error_set(
						 error,
						 LIBCERROR_ERROR_DOMAIN_RUNTIME,
						 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
						 "%s: missing last used path string string segment: %d.",
						 function,
						 last_used_path_segment_index );

						goto on_error;
					}
					/* Remove the size of the parent directory name and a directory separator
					 * Note that the size includes the end of string character
					 */
					if( current_directory_string_segment_size < safe_full_path_size )
					{
						safe_full_path_size -= last_used_path_string_segment_size;
					}
					else
					{
						safe_full_path_size = 0;
					}
					if( libcsplit_narrow_split_string_set_segment_by_index(
					     path_split_string,
					     last_used_path_segment_index,
					     NULL,
					     0,
					     error ) != 1 )
					{
						libcerror_error_set(
						 error,
						 LIBCERROR_ERROR_DOMAIN_RUNTIME,
						 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
						 "%s: unable to set path string segment: %d.",
						 function,
						 last_used_path_segment_index );

						goto on_error;
					}
					/* Find the previous path split value that contains a name
					 */
					while( last_used_path_segment_index > 0 )
					{
						last_used_path_segment_index--;

						if( libcsplit_narrow_split_string_get_segment_by_index(
						     path_split_string,
						     last_used_path_segment_index,
						     &last_used_path_string_segment,
						     &last_used_path_string_segment_size,
						     error ) != 1 )
						{
							libcerror_error_set(
							 error,
							 LIBCERROR_ERROR_DOMAIN_RUNTIME,
							 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
							 "%s: unable to retrieve last used path string segment: %d.",
							 function,
							 last_used_path_segment_index );

							goto on_error;
						}
						if( last_used_path_string_segment_size != 0 )
						{
							break;
						}
					}
				}
				if( libcsplit_narrow_split_string_set_segment_by_index(
				     path_split_string,
				     path_segment_index,
				     NULL,
				     0,
				     error ) != 1 )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
					 "%s: unable to set path string segment: %d.",
					 function,
					 path_segment_index );

					goto on_error;
				}
			}
			else
			{
				/* Add the size of the directory or file name and a directory separator
				 * Note that the size includes the end of string character
				 */
				safe_full_path_size += path_string_segment_size;

				last_used_path_segment_index = path_segment_index;
			}
		}
		/* Use the size reserved of the last directory separator for
		 * the directory separator after the path prefix.
		 */
	}
	safe_full_path_size += full_path_prefix_length + 1;

	full_path_index = 0;

	safe_full_path = narrow_string_allocate(
	                  safe_full_path_size );

	if( safe_full_path == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_INSUFFICIENT,
		 "%s: unable to create full path.",
		 function );

		goto on_error;
	}
	if( memory_set(
	     safe_full_path,
	     0,
	     sizeof( char ) * safe_full_path_size ) == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_SET_FAILED,
		 "%s: unable to clear full path.",
		 function );

		goto on_error;
	}
	if( path_type == LIBCPATH_TYPE_DEVICE )
	{
		full_path_prefix        = "\\\\.\\";
		full_path_prefix_length = 4;
	}
	else
	{
		full_path_prefix        = "\\\\?\\";
		full_path_prefix_length = 4;
	}
	if( full_path_prefix_length > ( safe_full_path_size - full_path_index ) )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid full path size value out of bounds.",
		 function );

		goto on_error;
	}
	if( narrow_string_copy(
	     &( safe_full_path[ full_path_index ] ),
	     full_path_prefix,
	     full_path_prefix_length ) == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
		 "%s: unable to set prefix in full path.",
		 function );

		goto on_error;
	}
	full_path_index += full_path_prefix_length;

	/* If there is a share name the path is an UNC path
	 */
	if( ( path_type == LIBCPATH_TYPE_EXTENDED_LENGTH_UNC )
	 || ( path_type == LIBCPATH_TYPE_UNC ) )
	{
		if( ( safe_full_path_size - full_path_index ) < 4 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
			 LIBCERROR_ARGUMENT_ERROR_VALUE_OUT_OF_BOUNDS,
			 "%s: invalid full path size value out of bounds.",
			 function );

			goto on_error;
		}
		if( narrow_string_copy(
		     &( safe_full_path[ full_path_index ] ),
		     "UNC\\",
		     4 ) == NULL )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
			 "%s: unable to set UNC\\ prefix in full path.",
			 function );

			goto on_error;
		}
		full_path_index += 4;
	}
	if( volume_name != NULL )
	{
		if( volume_name_length > ( safe_full_path_size - full_path_index ) )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
			 LIBCERROR_ARGUMENT_ERROR_VALUE_OUT_OF_BOUNDS,
			 "%s: invalid full path size value out of bounds.",
			 function );

			goto on_error;
		}
		if( narrow_string_copy(
		     &( safe_full_path[ full_path_index ] ),
		     volume_name,
		     volume_name_length ) == NULL )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
			 "%s: unable to set volume name in full path.",
			 function );

			goto on_error;
		}
		full_path_index += volume_name_length;

		safe_full_path[ full_path_index++ ] = '\\';
	}
	/* If the path is relative
	 * add the current working directory elements
	 */
	if( ( path_type == LIBCPATH_TYPE_RELATIVE )
	 && ( current_directory_split_string != NULL ) )
	{
		for( current_directory_segment_index = 0;
		     current_directory_segment_index < current_directory_number_of_segments;
		     current_directory_segment_index++ )
		{
			if( libcsplit_narrow_split_string_get_segment_by_index(
			     current_directory_split_string,
			     current_directory_segment_index,
			     &current_directory_string_segment,
			     &current_directory_string_segment_size,
			     error ) != 1 )
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_RUNTIME,
				 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
				 "%s: unable to retrieve current working directory string segment: %d.",
				 function,
				 current_directory_segment_index );

				goto on_error;
			}
			if( current_directory_string_segment_size != 0 )
			{
				if( current_directory_string_segment == NULL )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
					 "%s: missing current working directory string segment: %d.",
					 function,
					 current_directory_segment_index );

					goto on_error;
				}
				/* Note that here we should have room for the segment string and
				 * one additional character
				 */
				if( current_directory_string_segment_size > ( safe_full_path_size - full_path_index ) )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_VALUE_OUT_OF_BOUNDS,
					 "%s: invalid current working directory string segment: %d size value out of bounds.",
					 function,
					 current_directory_segment_index );

					goto on_error;
				}
				path_string_segment_length = current_directory_string_segment_size - 1;

				if( narrow_string_copy(
				     &( safe_full_path[ full_path_index ] ),
				     current_directory_string_segment,
				     path_string_segment_length ) == NULL )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
					 "%s: unable to set current working directory split value: %d in full path.",
					 function,
					 current_directory_segment_index );

					goto on_error;
				}
				full_path_index += path_string_segment_length;

				safe_full_path[ full_path_index++ ] = '\\';
			}
		}
	}
	if( path_split_string != NULL )
	{
		for( path_segment_index = 0;
		     path_segment_index < path_number_of_segments;
		     path_segment_index++ )
		{
			if( libcsplit_narrow_split_string_get_segment_by_index(
			     path_split_string,
			     path_segment_index,
			     &path_string_segment,
			     &path_string_segment_size,
			     error ) != 1 )
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_RUNTIME,
				 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
				 "%s: unable to retrieve path string segment: %d.",
				 function,
				 path_segment_index );

				goto on_error;
			}
			if( path_string_segment_size != 0 )
			{
				if( path_string_segment == NULL )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
					 "%s: missing path string segment: %d.",
					 function,
					 path_segment_index );

					goto on_error;
				}
				/* Note that here we should have room for the segment string and
				 * one additional character
				 */
				if( path_string_segment_size > ( safe_full_path_size - full_path_index ) )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_VALUE_OUT_OF_BOUNDS,
					 "%s: invalid current path string segment: %d size value out of bounds.",
					 function,
					 path_segment_index );

					goto on_error;
				}
				path_string_segment_length = path_string_segment_size - 1;

				if( narrow_string_copy(
				     &( safe_full_path[ full_path_index ] ),
				     path_string_segment,
				     path_string_segment_length ) == NULL )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
					 "%s: unable to set path split value: %d in full path.",
					 function,
					 path_segment_index );

					goto on_error;
				}
				full_path_index += path_string_segment_length;

				safe_full_path[ full_path_index++ ] = '\\';
			}
		}
	}
	safe_full_path[ full_path_index - 1 ] = 0;

	if( path_split_string != NULL )
	{
		if( libcsplit_narrow_split_string_free(
		     &path_split_string,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_FINALIZE_FAILED,
			 "%s: unable to free path split string.",
			 function );

			goto on_error;
		}
	}
	if( current_directory_split_string != NULL )
	{
		if( libcsplit_narrow_split_string_free(
		     &current_directory_split_string,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_FINALIZE_FAILED,
			 "%s: unable to free current working directory split string.",
			 function );

			goto on_error;
		}
	}
	if( current_directory != NULL )
	{
		memory_free(
		 current_directory );
	}
	*full_path      = safe_full_path;
	*full_path_size = safe_full_path_size;

	return( 1 );

on_error:
	if( safe_full_path != NULL )
	{
		memory_free(
		 safe_full_path );
	}
	if( path_split_string != NULL )
	{
		libcsplit_narrow_split_string_free(
		 &path_split_string,
		 NULL );
	}
	if( current_directory_split_string != NULL )
	{
		libcsplit_narrow_split_string_free(
		 &current_directory_split_string,
		 NULL );
	}
	if( current_directory != NULL )
	{
		memory_free(
		 current_directory );
	}
	return( -1 );
}

#else

/* Determines the full path of the POSIX path specified
 * Multiple successive / are combined into one
 *
 * Scenarios:
 * /home/user/file.txt
 * /home/user//file.txt
 * /home/user/../user/file.txt
 * /../home/user/file.txt
 * user/../user/file.txt
 *
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_get_full_path(
     const char *path,
     size_t path_length,
     char **full_path,
     size_t *full_path_size,
     libcerror_error_t **error )
{
	libcsplit_narrow_split_string_t *current_directory_split_string = NULL;
	libcsplit_narrow_split_string_t *path_split_string              = NULL;
	char *current_directory                                         = NULL;
	char *current_directory_string_segment                          = NULL;
	char *last_used_path_string_segment                             = NULL;
	char *path_string_segment                                       = NULL;
	char *safe_full_path                                            = NULL;
	static char *function                                           = "libcpath_path_get_full_path";
	size_t current_directory_index                                  = 0;
	size_t current_directory_length                                 = 0;
	size_t current_directory_size                                   = 0;
	size_t current_directory_string_segment_size                    = 0;
	size_t full_path_index                                          = 0;
	size_t full_path_prefix_length                                  = 0;
	size_t last_used_path_string_segment_size                       = 0;
	size_t path_index                                               = 0;
	size_t path_string_segment_length                               = 0;
	size_t path_string_segment_size                                 = 0;
	size_t safe_full_path_size                                      = 0;
	uint8_t path_type                                               = LIBCPATH_TYPE_RELATIVE;
	int current_directory_number_of_segments                        = 0;
	int current_directory_segment_index                             = 0;
	int last_used_path_segment_index                                = -1;
	int path_number_of_segments                                     = 0;
	int path_segment_index                                          = 0;

	if( path == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid path.",
		 function );

		return( -1 );
	}
#if defined( __MINGW32__ )
	if( ( path_length < 2 )
	 || ( path_length > (size_t) ( SSIZE_MAX - 1 ) ) )
#else
	if( ( path_length < 1 )
	 || ( path_length > (size_t) ( SSIZE_MAX - 1 ) ) )
#endif
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid path length value out of bounds.",
		 function );

		return( -1 );
	}
	if( full_path == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid full path.",
		 function );

		return( -1 );
	}
	if( *full_path != NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_ALREADY_SET,
		 "%s: invalid full path value already set.",
		 function );

		return( -1 );
	}
	if( full_path_size == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid full path size.",
		 function );

		return( -1 );
	}
	/* Determine the full path size
	 */
#if defined( __MINGW32__ )
	if( path[ 1 ] == ':' )
	{
		path_index = 2;
	}
	current_directory_index = 3;
#endif
	full_path_prefix_length = current_directory_index;

	if( path[ path_index ] == LIBCPATH_SEPARATOR )
	{
		path_type = LIBCPATH_TYPE_ABSOLUTE;

#if !defined( __MINGW32__ )
		/* If the path is absolute
		 * a directory separator
		 */
		full_path_prefix_length += 1;
#endif
	}
	if( ( path_type == LIBCPATH_TYPE_RELATIVE )
	 || ( path_index != current_directory_index ) )
	{
		if( libcpath_path_get_current_working_directory(
		     &current_directory,
		     &current_directory_size,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
			 "%s: unable to retrieve current working directory.",
			 function );

			goto on_error;
		}
		if( current_directory == NULL )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
			 "%s: missing current working directory.",
			 function );

			goto on_error;
		}
		/* We need to use the length here since current_directory_size will be PATH_MAX
		 */
		current_directory_length = narrow_string_length(
		                            current_directory );

		if( current_directory_index > current_directory_length )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_VALUE_OUT_OF_BOUNDS,
			 "%s: invalid current working directory length value out of bounds.",
			 function );

			goto on_error;
		}
		current_directory_length -= current_directory_index;
	}
	if( path_type == LIBCPATH_TYPE_RELATIVE )
	{
		if( libcsplit_narrow_string_split(
		     &( current_directory[ current_directory_index ] ),
		     current_directory_length + 1,
		     LIBCPATH_SEPARATOR,
		     &current_directory_split_string,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_INITIALIZE_FAILED,
			 "%s: unable to split current working directory.",
			 function );

			goto on_error;
		}
		if( libcsplit_narrow_split_string_get_number_of_segments(
		     current_directory_split_string,
		     &current_directory_number_of_segments,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
			 "%s: unable to retrieve number of current working directory string segments.",
			 function );

			goto on_error;
		}
		current_directory_segment_index = current_directory_number_of_segments - 1;

		/* If the path is relative
		 * add the size of the current working directory
		 * a directory separator, if necessary
		 */
		safe_full_path_size = current_directory_length;

		if( ( current_directory_length >= 1 )
		 && ( current_directory[ current_directory_length - 1 ] != LIBCPATH_SEPARATOR ) )
		{
			safe_full_path_size++;
		}
	}
	if( path_index > path_length )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid path length value out of bounds.",
		 function );

		goto on_error;
	}
	if( libcsplit_narrow_string_split(
	     &( path[ path_index ] ),
	     ( path_length - path_index ) + 1,
	     LIBCPATH_SEPARATOR,
	     &path_split_string,
	     error ) != 1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_INITIALIZE_FAILED,
		 "%s: unable to split path.",
		 function );

		goto on_error;
	}
	if( path_split_string != NULL )
	{
		if( libcsplit_narrow_split_string_get_number_of_segments(
		     path_split_string,
		     &path_number_of_segments,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
			 "%s: unable to retrieve number of path string segments.",
			 function );

			goto on_error;
		}
		for( path_segment_index = 0;
		     path_segment_index < path_number_of_segments;
		     path_segment_index++ )
		{
			if( libcsplit_narrow_split_string_get_segment_by_index(
			     path_split_string,
			     path_segment_index,
			     &path_string_segment,
			     &path_string_segment_size,
			     error ) != 1 )
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_RUNTIME,
				 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
				 "%s: unable to retrieve path string segment: %d.",
				 function,
				 path_segment_index );

				goto on_error;
			}
			if( path_string_segment == NULL )
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_RUNTIME,
				 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
				 "%s: missing path string segment: %d.",
				 function,
				 path_segment_index );

				goto on_error;
			}
			/* If the path is "" (empty) or "." (current) ignore the entry
			 */
			if( ( path_string_segment_size <= 1 )
			 || ( ( path_string_segment_size == 2 )
			  &&  ( path_string_segment[ 0 ] == '.' ) ) )
			{
				if( libcsplit_narrow_split_string_set_segment_by_index(
				     path_split_string,
				     path_segment_index,
				     NULL,
				     0,
				     error ) != 1 )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
					 "%s: unable to set path string segment: %d.",
					 function,
					 path_segment_index );

					goto on_error;
				}
			}
			/* If the path is ".." (parent) reverse the current path by one directory
			 */
			else if( ( path_string_segment_size == 3 )
			      && ( path_string_segment[ 0 ] == '.' )
			      && ( path_string_segment[ 1 ] == '.' ) )
			{
				if( ( current_directory_split_string != NULL )
				 && ( last_used_path_segment_index == -1 ) )
				{
					if( libcsplit_narrow_split_string_get_segment_by_index(
					     current_directory_split_string,
					     current_directory_segment_index,
					     &current_directory_string_segment,
					     &current_directory_string_segment_size,
					     error ) != 1 )
					{
						libcerror_error_set(
						 error,
						 LIBCERROR_ERROR_DOMAIN_RUNTIME,
						 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
						 "%s: unable to retrieve current working directory string segment: %d.",
						 function,
						 current_directory_segment_index );

						goto on_error;
					}
					if( current_directory_string_segment == NULL )
					{
						libcerror_error_set(
						 error,
						 LIBCERROR_ERROR_DOMAIN_RUNTIME,
						 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
						 "%s: missing current working directory string segment: %d.",
						 function,
						 current_directory_segment_index );

						goto on_error;
					}
					/* Remove the size of the parent directory name and a directory separator
					 * Note that the size includes the end of string character
					 */
					if( current_directory_string_segment_size < safe_full_path_size )
					{
						safe_full_path_size -= current_directory_string_segment_size;
					}
					else
					{
						safe_full_path_size = 0;
					}
					if( libcsplit_narrow_split_string_set_segment_by_index(
					     current_directory_split_string,
					     current_directory_segment_index,
					     NULL,
					     0,
					     error ) != 1 )
					{
						libcerror_error_set(
						 error,
						 LIBCERROR_ERROR_DOMAIN_RUNTIME,
						 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
						 "%s: unable to set current working directory string segment: %d.",
						 function,
						 current_directory_segment_index );

						goto on_error;
					}
					current_directory_segment_index--;
				}
				else if( last_used_path_segment_index >= 0 )
				{
					if( libcsplit_narrow_split_string_get_segment_by_index(
					     path_split_string,
					     last_used_path_segment_index,
					     &last_used_path_string_segment,
					     &last_used_path_string_segment_size,
					     error ) != 1 )
					{
						libcerror_error_set(
						 error,
						 LIBCERROR_ERROR_DOMAIN_RUNTIME,
						 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
						 "%s: unable to retrieve last used path string segment: %d.",
						 function,
						 last_used_path_segment_index );

						goto on_error;
					}
					if( last_used_path_string_segment == NULL )
					{
						libcerror_error_set(
						 error,
						 LIBCERROR_ERROR_DOMAIN_RUNTIME,
						 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
						 "%s: missing last used path string string segment: %d.",
						 function,
						 last_used_path_segment_index );

						goto on_error;
					}
					/* Remove the size of the parent directory name and a directory separator
					 * Note that the size includes the end of string character
					 */
					if( last_used_path_string_segment_size < safe_full_path_size )
					{
						safe_full_path_size -= last_used_path_string_segment_size;
					}
					else
					{
						safe_full_path_size = 0;
					}
					if( libcsplit_narrow_split_string_set_segment_by_index(
					     path_split_string,
					     last_used_path_segment_index,
					     NULL,
					     0,
					     error ) != 1 )
					{
						libcerror_error_set(
						 error,
						 LIBCERROR_ERROR_DOMAIN_RUNTIME,
						 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
						 "%s: unable to set path string segment: %d.",
						 function,
						 last_used_path_segment_index );

						goto on_error;
					}
					/* Find the previous path split value that contains a name
					 */
					while( last_used_path_segment_index > 0 )
					{
						last_used_path_segment_index--;

						if( libcsplit_narrow_split_string_get_segment_by_index(
						     path_split_string,
						     last_used_path_segment_index,
						     &last_used_path_string_segment,
						     &last_used_path_string_segment_size,
						     error ) != 1 )
						{
							libcerror_error_set(
							 error,
							 LIBCERROR_ERROR_DOMAIN_RUNTIME,
							 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
							 "%s: unable to retrieve last used path string segment: %d.",
							 function,
							 last_used_path_segment_index );

							goto on_error;
						}
						if( last_used_path_string_segment_size != 0 )
						{
							break;
						}
					}
				}
				if( libcsplit_narrow_split_string_set_segment_by_index(
				     path_split_string,
				     path_segment_index,
				     NULL,
				     0,
				     error ) != 1 )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
					 "%s: unable to set path string segment: %d.",
					 function,
					 path_segment_index );

					goto on_error;
				}
			}
			else
			{
				/* Add the size of the directory or file name and a directory separator
				 * Note that the size includes the end of string character
				 */
				safe_full_path_size += path_string_segment_size;

				last_used_path_segment_index = path_segment_index;
			}
		}
#if defined( __MINGW32__ )
		if( ( safe_full_path_size > 0 )
		 && ( path_number_of_segments > 1 ) )
#else
		if( safe_full_path_size > 0 )
#endif
		{
			/* Remove the size reserved of the last directory separator
			 */
			safe_full_path_size -= 1;
		}
	}
	safe_full_path_size += full_path_prefix_length + 1;

	safe_full_path = narrow_string_allocate(
	                  safe_full_path_size );

	if( safe_full_path == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_INSUFFICIENT,
		 "%s: unable to create full path.",
		 function );

		goto on_error;
	}
	if( memory_set(
	     safe_full_path,
	     0,
	     sizeof( char ) * safe_full_path_size ) == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_SET_FAILED,
		 "%s: unable to clear full path.",
		 function );

		goto on_error;
	}
	full_path_index = 0;

#if defined( __MINGW32__ )
	if( path_index == 2 )
	{
		safe_full_path[ full_path_index++ ] = path[ 0 ];
	}
	else
	{
		safe_full_path[ full_path_index++ ] = current_directory[ 0 ];
	}
	safe_full_path[ full_path_index++ ] = ':';
	safe_full_path[ full_path_index++ ] = LIBCPATH_SEPARATOR;
#else
	if( path_type == LIBCPATH_TYPE_ABSOLUTE )
	{
		safe_full_path[ full_path_index++ ] = LIBCPATH_SEPARATOR;
	}
#endif
	/* If the path is relative
	 * add the current working directory elements
	 */
	if( current_directory_split_string != NULL )
	{
		for( current_directory_segment_index = 0;
		     current_directory_segment_index < current_directory_number_of_segments;
		     current_directory_segment_index++ )
		{
			if( libcsplit_narrow_split_string_get_segment_by_index(
			     current_directory_split_string,
			     current_directory_segment_index,
			     &current_directory_string_segment,
			     &current_directory_string_segment_size,
			     error ) != 1 )
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_RUNTIME,
				 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
				 "%s: unable to retrieve current working directory string segment: %d.",
				 function,
				 current_directory_segment_index );

				goto on_error;
			}
			if( current_directory_string_segment_size != 0 )
			{
				if( current_directory_string_segment == NULL )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
					 "%s: missing current working directory string segment: %d.",
					 function,
					 current_directory_segment_index );

					goto on_error;
				}
				/* Note that here we should have room for the segment string and
				 * one additional character
				 */
				if( current_directory_string_segment_size > ( safe_full_path_size - full_path_index ) )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_VALUE_OUT_OF_BOUNDS,
					 "%s: invalid current working directory string segment: %d size value out of bounds.",
					 function,
					 current_directory_segment_index );

					goto on_error;
				}
				path_string_segment_length = current_directory_string_segment_size - 1;

				if( narrow_string_copy(
				     &( safe_full_path[ full_path_index ] ),
				     current_directory_string_segment,
				     path_string_segment_length ) == NULL )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
					 "%s: unable to set current working directory split value: %d in full path.",
					 function,
					 current_directory_segment_index );

					goto on_error;
				}
				full_path_index += path_string_segment_length;

				safe_full_path[ full_path_index++ ] = LIBCPATH_SEPARATOR;
			}
		}
	}
	if( path_split_string != NULL )
	{
		for( path_segment_index = 0;
		     path_segment_index < path_number_of_segments;
		     path_segment_index++ )
		{
			if( libcsplit_narrow_split_string_get_segment_by_index(
			     path_split_string,
			     path_segment_index,
			     &path_string_segment,
			     &path_string_segment_size,
			     error ) != 1 )
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_RUNTIME,
				 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
				 "%s: unable to retrieve path string segment: %d.",
				 function,
				 path_segment_index );

				goto on_error;
			}
			if( path_string_segment_size != 0 )
			{
				if( path_string_segment == NULL )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
					 "%s: missing path string segment: %d.",
					 function,
					 path_segment_index );

					goto on_error;
				}
				/* Note that here we should have room for the segment string and
				 * one additional character
				 */
				if( path_string_segment_size > ( safe_full_path_size - full_path_index ) )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_VALUE_OUT_OF_BOUNDS,
					 "%s: invalid current path string segment: %d size value out of bounds.",
					 function,
					 path_segment_index );

					goto on_error;
				}
				path_string_segment_length = path_string_segment_size - 1;

				if( narrow_string_copy(
				     &( safe_full_path[ full_path_index ] ),
				     path_string_segment,
				     path_string_segment_length ) == NULL )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
					 "%s: unable to set path split value: %d in full path.",
					 function,
					 path_segment_index );

					goto on_error;
				}
				full_path_index += path_string_segment_length;

				safe_full_path[ full_path_index++ ] = LIBCPATH_SEPARATOR;
			}
		}
	}
	safe_full_path[ full_path_index - 1 ] = 0;

	if( path_split_string != NULL )
	{
		if( libcsplit_narrow_split_string_free(
		     &path_split_string,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_FINALIZE_FAILED,
			 "%s: unable to free path split string.",
			 function );

			goto on_error;
		}
	}
	if( current_directory_split_string != NULL )
	{
		if( libcsplit_narrow_split_string_free(
		     &current_directory_split_string,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_FINALIZE_FAILED,
			 "%s: unable to free current working directory split string.",
			 function );

			goto on_error;
		}
	}
	if( current_directory != NULL )
	{
		memory_free(
		 current_directory );
	}
	*full_path      = safe_full_path;
	*full_path_size = safe_full_path_size;

	return( 1 );

on_error:
	if( safe_full_path != NULL )
	{
		memory_free(
		 safe_full_path );
	}
	if( path_split_string != NULL )
	{
		libcsplit_narrow_split_string_free(
		 &path_split_string,
		 NULL );
	}
	if( current_directory_split_string != NULL )
	{
		libcsplit_narrow_split_string_free(
		 &current_directory_split_string,
		 NULL );
	}
	if( current_directory != NULL )
	{
		memory_free(
		 current_directory );
	}
	return( -1 );
}

#endif /* defined( WINAPI ) */

/* Retrieves the size of a sanitized version of the path character
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_get_sanitized_character_size(
     char character,
     size_t *sanitized_character_size,
     libcerror_error_t **error )
{
	static char *function = "libcpath_path_get_sanitized_character_size";

	if( sanitized_character_size == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid sanitized character size.",
		 function );

		return( -1 );
	}
	if( ( character >= 0x00 )
	 && ( character <= 0x1f ) )
	{
		*sanitized_character_size = 4;
	}
	else if( character == LIBCPATH_ESCAPE_CHARACTER )
	{
		*sanitized_character_size = 2;
	}
#if defined( WINAPI )
	else if( character == '/' )
	{
		*sanitized_character_size = 4;
	}
#endif
	else if( ( character == '!' )
	      || ( character == '$' )
	      || ( character == '%' )
	      || ( character == '&' )
	      || ( character == '*' )
	      || ( character == '+' )
	      || ( character == ':' )
	      || ( character == ';' )
	      || ( character == '<' )
	      || ( character == '>' )
	      || ( character == '?' )
	      || ( character == '|' )
	      || ( character == 0x7f ) )
	{
		*sanitized_character_size = 4;
	}
	else
	{
		*sanitized_character_size = 1;
	}
	return( 1 );
}

/* Retrieves a sanitized version of the path character
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_get_sanitized_character(
     char character,
     size_t sanitized_character_size,
     char *sanitized_path,
     size_t sanitized_path_size,
     size_t *sanitized_path_index,
     libcerror_error_t **error )
{
	static char *function            = "libcpath_path_get_sanitized_character";
	size_t safe_sanitized_path_index = 0;
	char lower_nibble                = 0;
	char upper_nibble                = 0;

	if( ( sanitized_character_size != 1 )
	 && ( sanitized_character_size != 2 )
	 && ( sanitized_character_size != 4 ) )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid sanitized character size value out of bounds.",
		 function );

		return( -1 );
	}
	if( sanitized_path == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid sanitized path.",
		 function );

		return( -1 );
	}
	if( sanitized_path_size > (size_t) SSIZE_MAX )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_EXCEEDS_MAXIMUM,
		 "%s: invalid sanitized path size value exceeds maximum.",
		 function );

		return( -1 );
	}
	if( sanitized_path_index == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid sanitized path index.",
		 function );

		return( -1 );
	}
	safe_sanitized_path_index = *sanitized_path_index;

	if( safe_sanitized_path_index > sanitized_path_size )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid sanitized path index value out of bounds.",
		 function );

		return( -1 );
	}
	if( ( sanitized_character_size > sanitized_path_size )
	 || ( safe_sanitized_path_index > ( sanitized_path_size - sanitized_character_size ) ) )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_TOO_SMALL,
		 "%s: invalid sanitized path size value too small.",
		 function );

		return( -1 );
	}
	if( sanitized_character_size == 1 )
	{
		sanitized_path[ safe_sanitized_path_index++ ] = character;
	}
	else if( sanitized_character_size == 2 )
	{
		sanitized_path[ safe_sanitized_path_index++ ] = LIBCPATH_ESCAPE_CHARACTER;
		sanitized_path[ safe_sanitized_path_index++ ] = LIBCPATH_ESCAPE_CHARACTER;
	}
	else if( sanitized_character_size == 4 )
	{
		lower_nibble = character & 0x0f;
		upper_nibble = ( character >> 4 ) & 0x0f;

		if( lower_nibble > 10 )
		{
			lower_nibble += 'a' - 10;
		}
		else
		{
			lower_nibble += '0';
		}
		if( upper_nibble > 10 )
		{
			upper_nibble += 'a' - 10;
		}
		else
		{
			upper_nibble += '0';
		}
		sanitized_path[ safe_sanitized_path_index++ ] = LIBCPATH_ESCAPE_CHARACTER;
		sanitized_path[ safe_sanitized_path_index++ ] = 'x';
		sanitized_path[ safe_sanitized_path_index++ ] = upper_nibble;
		sanitized_path[ safe_sanitized_path_index++ ] = lower_nibble;
	}
	*sanitized_path_index = safe_sanitized_path_index;

	return( 1 );
}

/* Retrieves a sanitized version of the filename
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_get_sanitized_filename(
     const char *filename,
     size_t filename_length,
     char **sanitized_filename,
     size_t *sanitized_filename_size,
     libcerror_error_t **error )
{
	static char *function               = "libcpath_path_get_sanitized_filename";
	char *safe_sanitized_filename       = NULL;
	size_t filename_index               = 0;
	size_t sanitized_character_size     = 0;
	size_t safe_sanitized_filename_size = 0;
	size_t sanitized_filename_index     = 0;

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
	if( ( filename_length == 0 )
	 || ( filename_length > (size_t) ( SSIZE_MAX - 1 ) ) )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid filename length value out of bounds.",
		 function );

		return( -1 );
	}
	if( sanitized_filename == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid sanitized filename.",
		 function );

		return( -1 );
	}
	if( *sanitized_filename != NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_ALREADY_SET,
		 "%s: invalid sanitized filename value already set.",
		 function );

		return( -1 );
	}
	if( sanitized_filename_size == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid sanitized filename size.",
		 function );

		return( -1 );
	}
	safe_sanitized_filename_size = 1;

	for( filename_index = 0;
	     filename_index < filename_length;
	     filename_index++ )
	{
		if( filename[ filename_index ] == LIBCPATH_SEPARATOR )
		{
			sanitized_character_size = 4;
		}
		else if( libcpath_path_get_sanitized_character_size(
		          filename[ filename_index ],
		          &sanitized_character_size,
		          error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
			 "%s: unable to determine sanitize character size.",
			 function );

			goto on_error;
		}
		safe_sanitized_filename_size += sanitized_character_size;
	}
	if( safe_sanitized_filename_size > (size_t) SSIZE_MAX )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_EXCEEDS_MAXIMUM,
		 "%s: invalid sanitized filename size value exceeds maximum.",
		 function );

		goto on_error;
	}
	safe_sanitized_filename = narrow_string_allocate(
	                           safe_sanitized_filename_size );

	if( safe_sanitized_filename == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_INSUFFICIENT,
		 "%s: unable to create sanitized filename.",
		 function );

		goto on_error;
	}
	for( filename_index = 0;
	     filename_index < filename_length;
	     filename_index++ )
	{
		if( filename[ filename_index ] == LIBCPATH_SEPARATOR )
		{
			sanitized_character_size = 4;
		}
		else if( libcpath_path_get_sanitized_character_size(
		          filename[ filename_index ],
		          &sanitized_character_size,
		          error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
			 "%s: unable to determine sanitize character size.",
			 function );

			goto on_error;
		}
		if( libcpath_path_get_sanitized_character(
		     filename[ filename_index ],
		     sanitized_character_size,
		     safe_sanitized_filename,
		     safe_sanitized_filename_size,
		     &sanitized_filename_index,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
			 "%s: unable to determine sanitize character size.",
			 function );

			goto on_error;
		}
	}
	safe_sanitized_filename[ sanitized_filename_index ] = 0;

	*sanitized_filename      = safe_sanitized_filename;
	*sanitized_filename_size = safe_sanitized_filename_size;

	return( 1 );

on_error:
	if( safe_sanitized_filename != NULL )
	{
		memory_free(
		 safe_sanitized_filename );
	}
	return( -1 );
}

/* Retrieves a sanitized version of the path
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_get_sanitized_path(
     const char *path,
     size_t path_length,
     char **sanitized_path,
     size_t *sanitized_path_size,
     libcerror_error_t **error )
{
	static char *function                    = "libcpath_path_get_sanitized_path";
	char *safe_sanitized_path                = NULL;
	size_t path_index                        = 0;
	size_t safe_sanitized_path_size          = 0;
	size_t sanitized_character_size          = 0;
	size_t sanitized_path_index              = 0;

#if defined( WINAPI )
	size_t last_path_segment_seperator_index = 0;
#endif

	if( path == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid path.",
		 function );

		return( -1 );
	}
	if( ( path_length == 0 )
	 || ( path_length > (size_t) ( SSIZE_MAX - 1 ) ) )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid path length value out of bounds.",
		 function );

		return( -1 );
	}
	if( sanitized_path == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid sanitized path.",
		 function );

		return( -1 );
	}
	if( *sanitized_path != NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_ALREADY_SET,
		 "%s: invalid sanitized path value already set.",
		 function );

		return( -1 );
	}
	if( sanitized_path_size == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid sanitized path size.",
		 function );

		return( -1 );
	}
	safe_sanitized_path_size = 1;

	for( path_index = 0;
	     path_index < path_length;
	     path_index++ )
	{
		if( libcpath_path_get_sanitized_character_size(
		     path[ path_index ],
		     &sanitized_character_size,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
			 "%s: unable to determine sanitize character size.",
			 function );

			goto on_error;
		}
		safe_sanitized_path_size += sanitized_character_size;

#if defined( WINAPI )
		if( path[ path_index ] == LIBCPATH_SEPARATOR )
		{
			last_path_segment_seperator_index = path_index;
		}
#endif
	}
	if( safe_sanitized_path_size > (size_t) SSIZE_MAX )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_EXCEEDS_MAXIMUM,
		 "%s: invalid sanitized path size value exceeds maximum.",
		 function );

		goto on_error;
	}
#if defined( WINAPI )
	if( last_path_segment_seperator_index > 32767 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid last path segment separator value out of bounds.",
		 function );

		goto on_error;
	}
	if( safe_sanitized_path_size > 32767 )
	{
		safe_sanitized_path_size = 32767;
	}
#endif
	safe_sanitized_path = narrow_string_allocate(
	                       safe_sanitized_path_size );

	if( safe_sanitized_path == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_INSUFFICIENT,
		 "%s: unable to create sanitized path.",
		 function );

		goto on_error;
	}
	for( path_index = 0;
	     path_index < path_length;
	     path_index++ )
	{
		if( libcpath_path_get_sanitized_character_size(
		     path[ path_index ],
		     &sanitized_character_size,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
			 "%s: unable to determine sanitize character size.",
			 function );

			goto on_error;
		}
		if( libcpath_path_get_sanitized_character(
		     path[ path_index ],
		     sanitized_character_size,
		     safe_sanitized_path,
		     safe_sanitized_path_size,
		     &sanitized_path_index,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
			 "%s: unable to determine sanitize character size.",
			 function );

			goto on_error;
		}
	}
	if( sanitized_path_index >= safe_sanitized_path_size )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid sanitized path index value out of bounds.",
		 function );

		goto on_error;
	}
	safe_sanitized_path[ sanitized_path_index ] = 0;

	*sanitized_path      = safe_sanitized_path;
	*sanitized_path_size = safe_sanitized_path_size;

	return( 1 );

on_error:
	if( safe_sanitized_path != NULL )
	{
		memory_free(
		 safe_sanitized_path );
	}
	return( -1 );
}

/* Combines the directory name and filename into a path
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_join(
     char **path,
     size_t *path_size,
     const char *directory_name,
     size_t directory_name_length,
     const char *filename,
     size_t filename_length,
     libcerror_error_t **error )
{
	static char *function = "libcpath_path_join";
	size_t filename_index = 0;
	size_t path_index     = 0;

	if( path == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid path.",
		 function );

		return( -1 );
	}
	if( *path != NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_ALREADY_SET,
		 "%s: invalid path value already set.",
		 function );

		return( -1 );
	}
	if( path_size == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid path size.",
		 function );

		return( -1 );
	}
	if( directory_name == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid directory name.",
		 function );

		return( -1 );
	}
	if( directory_name_length > (size_t) SSIZE_MAX )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_EXCEEDS_MAXIMUM,
		 "%s: invalid directory name length value exceeds maximum.",
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
	if( filename_length > (size_t) SSIZE_MAX )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_EXCEEDS_MAXIMUM,
		 "%s: invalid filename length value exceeds maximum.",
		 function );

		return( -1 );
	}
/* TODO strip other patterns like /./ */
	while( directory_name_length > 0 )
	{
		if( directory_name[ directory_name_length - 1 ] != (char) LIBCPATH_SEPARATOR )
		{
			break;
		}
		directory_name_length--;
	}
	while( filename_length > 0 )
	{
		if( filename[ filename_index ] != (char) LIBCPATH_SEPARATOR )
		{
			break;
		}
		filename_index++;
		filename_length--;
	}
	*path_size = directory_name_length + filename_length + 2;

	*path = narrow_string_allocate(
	         *path_size );

	if( *path == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_INSUFFICIENT,
		 "%s: unable to create path.",
		 function );

		goto on_error;
	}
	if( narrow_string_copy(
	     *path,
	     directory_name,
	     directory_name_length ) == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_COPY_FAILED,
		 "%s: unable to copy directory name to path.",
		 function );

		goto on_error;
	}
	path_index = directory_name_length;

	( *path )[ path_index++ ] = (char) LIBCPATH_SEPARATOR;

	if( narrow_string_copy(
	     &( ( *path )[ path_index ] ),
	     &( filename[ filename_index ] ),
	     filename_length ) == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_COPY_FAILED,
		 "%s: unable to copy filename to path.",
		 function );

		goto on_error;
	}
	path_index += filename_length;

	( *path )[ path_index ] = 0;

	return( 1 );

on_error:
	if( *path != NULL )
	{
		memory_free(
		 *path );

		*path = NULL;
	}
	*path_size = 0;

	return( -1 );
}

#if defined( WINAPI ) && ( WINVER <= 0x0500 )

/* Cross Windows safe version of CreateDirectoryA
 * Returns TRUE if successful or FALSE on error
 */
BOOL libcpath_CreateDirectoryA(
      LPCSTR path,
      SECURITY_ATTRIBUTES *security_attributes )
{
	FARPROC function       = NULL;
	HMODULE library_handle = NULL;
	BOOL result            = FALSE;

	if( path == NULL )
	{
		return( 0 );
	}
	library_handle = LoadLibrary(
	                  _SYSTEM_STRING( "kernel32.dll" ) );

	if( library_handle == NULL )
	{
		return( 0 );
	}
	function = GetProcAddress(
		    library_handle,
		    (LPCSTR) "CreateDirectoryA" );

	if( function != NULL )
	{
		result = function(
			  path,
			  security_attributes );
	}
	/* This call should be after using the function
	 * in most cases kernel32.dll will still be available after free
	 */
	if( FreeLibrary(
	     library_handle ) != TRUE )
	{
		libcpath_CloseHandle(
		 library_handle );

		return( 0 );
	}
	return( result );
}

#endif /* defined( WINAPI ) && ( WINVER <= 0x0500 ) */

#if defined( WINAPI )

/* Makes the directory
 * This function uses the WINAPI function for Windows XP (0x0501) or later
 * or tries to dynamically call the function for Windows 2000 (0x0500) or earlier
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_make_directory(
     const char *directory_name,
     libcerror_error_t **error )
{
	static char *function = "libcpath_path_make_directory";
	DWORD error_code      = 0;

	if( directory_name == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid directory name.",
		 function );

		return( -1 );
	}
#if WINVER <= 0x0500
	if( libcpath_CreateDirectoryA(
	     directory_name,
	     NULL ) == 0 )
#else
	if( CreateDirectoryA(
	     directory_name,
	     NULL ) == 0 )
#endif
	{
		error_code = GetLastError();

		libcerror_system_set_error(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
		 error_code,
		 "%s: unable to make directory.",
		 function );

		return( -1 );
	}
	return( 1 );
}

#elif defined( HAVE_MKDIR )

/* Makes the directory
 * This function uses the POSIX mkdir function or equivalent
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_make_directory(
     const char *directory_name,
     libcerror_error_t **error )
{
	static char *function = "libcpath_path_make_directory";

	if( directory_name == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid directory name.",
		 function );

		return( -1 );
	}
#if defined( __MINGW32__ ) || defined( _MSC_VER )
	if( mkdir(
	     directory_name ) != 0 )
#else
	if( mkdir(
	     directory_name,
	     0755 ) != 0 )
#endif
	{
		libcerror_system_set_error(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
		 errno,
		 "%s: unable to make directory.",
		 function );

		return( -1 );
	}

	return( 1 );
}

#else
#error Missing make directory function
#endif

#if defined( HAVE_WIDE_CHARACTER_TYPE )

#if defined( WINAPI ) && ( WINVER <= 0x0500 )

/* Cross Windows safe version of SetCurrentDirectoryW
 * Returns TRUE if successful or FALSE on error
 */
BOOL libcpath_SetCurrentDirectoryW(
      LPCWSTR path )
{
	FARPROC function       = NULL;
	HMODULE library_handle = NULL;
	BOOL result            = FALSE;

	if( path == NULL )
	{
		return( FALSE );
	}
	library_handle = LoadLibrary(
	                  _SYSTEM_STRING( "kernel32.dll" ) );

	if( library_handle == NULL )
	{
		return( FALSE );
	}
	function = GetProcAddress(
		    library_handle,
		    (LPCSTR) "SetCurrentDirectoryW" );

	if( function != NULL )
	{
		result = function(
			  path );
	}
	/* This call should be after using the function
	 * in most cases kernel32.dll will still be available after free
	 */
	if( FreeLibrary(
	     library_handle ) != TRUE )
	{
		libcpath_CloseHandle(
		 library_handle );

		return( FALSE );
	}
	return( result );
}

#endif /* defined( WINAPI ) && ( WINVER <= 0x0500 ) */

#if defined( WINAPI )

/* Changes the directory
 * This function uses the WINAPI function for Windows XP (0x0501) or later
 * or tries to dynamically call the function for Windows 2000 (0x0500) or earlier
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_change_directory_wide(
     const wchar_t *directory_name,
     libcerror_error_t **error )
{
	static char *function = "libcpath_path_change_directory_wide";
	DWORD error_code      = 0;

	if( directory_name == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid directory name.",
		 function );

		return( -1 );
	}
#if WINVER <= 0x0500
	if( libcpath_SetCurrentDirectoryW(
	     directory_name ) == 0 )
#else
	if( SetCurrentDirectoryW(
	     directory_name ) == 0 )
#endif
	{
		error_code = GetLastError();

		libcerror_system_set_error(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
		 error_code,
		 "%s: unable to change directory.",
		 function );

		return( -1 );
	}
	return( 1 );
}

#elif defined( HAVE_CHDIR )

/* Changes the directory
 * This function uses the POSIX chdir function or equivalent
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_change_directory_wide(
     const wchar_t *directory_name,
     libcerror_error_t **error )
{
	static char *function             = "libcpath_path_change_directory_wide";
	char *narrow_directory_name       = 0;
	size_t directory_name_length      = 0;
	size_t narrow_directory_name_size = 0;

	if( directory_name == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid directory name.",
		 function );

		return( -1 );
	}
	directory_name_length = wide_string_length(
	                         directory_name );

	if( libcpath_system_string_size_from_wide_string(
	     directory_name,
	     directory_name_length + 1,
	     &narrow_directory_name_size,
	     error ) != 1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_CONVERSION,
		 LIBCERROR_CONVERSION_ERROR_GENERIC,
		 "%s: unable to determine narrow directory name size.",
		 function );

		goto on_error;
	}
	if( ( narrow_directory_name_size > (size_t) SSIZE_MAX )
	 || ( ( sizeof( char ) * narrow_directory_name_size )  > (size_t) SSIZE_MAX ) )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_EXCEEDS_MAXIMUM,
		 "%s: invalid narrow directory name size value exceeds maximum.",
		 function );

		goto on_error;
	}
	narrow_directory_name = narrow_string_allocate(
	                         narrow_directory_name_size );

	if( narrow_directory_name == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_INSUFFICIENT,
		 "%s: unable to create narrow directory name.",
		 function );

		goto on_error;
	}
	if( libcpath_system_string_copy_from_wide_string(
	     narrow_directory_name,
	     narrow_directory_name_size,
	     directory_name,
	     directory_name_length + 1,
	     error ) != 1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_CONVERSION,
		 LIBCERROR_CONVERSION_ERROR_GENERIC,
		 "%s: unable to set name.",
		 function );

		goto on_error;
	}
	if( chdir(
	     narrow_directory_name ) != 0 )
	{
		libcerror_system_set_error(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
		 errno,
		 "%s: unable to change directory.",
		 function );

		return( -1 );
	}
	memory_free(
	 narrow_directory_name );

	return( 1 );

on_error:
	if( narrow_directory_name != NULL )
	{
		memory_free(
		 narrow_directory_name );
	}
	return( -1 );
}

#else
#error Missing change directory function
#endif

#if defined( WINAPI ) && ( WINVER <= 0x0500 )

/* Cross Windows safe version of GetCurrentDirectoryW
 * Returns the number of characters in the current directory string or 0 on error
 */
DWORD libcpath_GetCurrentDirectoryW(
       DWORD buffer_size,
       LPCWSTR buffer )
{
	FARPROC function       = NULL;
	HMODULE library_handle = NULL;
	DWORD result           = 0;

	library_handle = LoadLibrary(
	                  _SYSTEM_STRING( "kernel32.dll" ) );

	if( library_handle == NULL )
	{
		return( 0 );
	}
	function = GetProcAddress(
		    library_handle,
		    (LPCSTR) "GetCurrentDirectoryW" );

	if( function != NULL )
	{
		result = function(
			  buffer_size,
			  buffer );
	}
	/* This call should be after using the function
	 * in most cases kernel32.dll will still be available after free
	 */
	if( FreeLibrary(
	     library_handle ) != TRUE )
	{
		libcpath_CloseHandle(
		 library_handle );

		return( 0 );
	}
	return( result );
}

#endif /* defined( WINAPI ) && ( WINVER <= 0x0500 ) */

#if defined( WINAPI )

/* Retrieves the current working directory
 * This function uses the WINAPI function for Windows XP (0x0501) or later
 * or tries to dynamically call the function for Windows 2000 (0x0500) or earlier
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_get_current_working_directory_wide(
     wchar_t **current_working_directory,
     size_t *current_working_directory_size,
     libcerror_error_t **error )
{
	static char *function                     = "libcpath_path_get_current_working_directory_wide";
	wchar_t *safe_current_working_directory   = NULL;
	DWORD error_code                          = 0;
	DWORD result                              = 0;
	DWORD safe_current_working_directory_size = 0;

	if( current_working_directory == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid current working directory.",
		 function );

		return( -1 );
	}
	if( *current_working_directory != NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_ALREADY_SET,
		 "%s: invalid current working directory value already set.",
		 function );

		return( -1 );
	}
	if( current_working_directory_size == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid current working directory size.",
		 function );

		return( -1 );
	}
#if WINVER <= 0x0500
	safe_current_working_directory_size = libcpath_GetCurrentDirectoryW(
	                                       0,
	                                       NULL );
#else
	safe_current_working_directory_size = GetCurrentDirectoryW(
	                                       0,
	                                       NULL );
#endif
	if( safe_current_working_directory_size == 0 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
		 "%s: unable to retrieve current working directory size.",
		 function );

		goto on_error;
	}
	if( (size_t) safe_current_working_directory_size > (size_t) SSIZE_MAX )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid current working directory size value out of bounds.",
		 function );

		goto on_error;
	}
	safe_current_working_directory = wide_string_allocate(
	                                  safe_current_working_directory_size );

	if( safe_current_working_directory == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_INSUFFICIENT,
		 "%s: unable to create current working directory.",
		 function );

		goto on_error;
	}
	if( memory_set(
	     safe_current_working_directory,
	     0,
	     sizeof( wchar_t ) * safe_current_working_directory_size ) == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_SET_FAILED,
		 "%s: unable to clear current working directory.",
		 function );

		goto on_error;
	}
#if WINVER <= 0x0500
	result = libcpath_GetCurrentDirectoryW(
	          safe_current_working_directory_size,
	          safe_current_working_directory )
#else
	result = GetCurrentDirectoryW(
	          safe_current_working_directory_size,
	          safe_current_working_directory );
#endif
	/* Note that safe_current_working_directory_size can be larger than result
	 */
	if( ( result == 0 )
	 || ( result > safe_current_working_directory_size ) )
	{
		error_code = GetLastError();

		libcerror_system_set_error(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
		 error_code,
		 "%s: unable to retrieve current working directory.",
		 function );

		goto on_error;
	}
	*current_working_directory      = safe_current_working_directory;
	*current_working_directory_size = (size_t) safe_current_working_directory_size;

	return( 1 );

on_error:
	if( safe_current_working_directory != NULL )
	{
		memory_free(
		 safe_current_working_directory );
	}
	return( -1 );
}

#elif defined( HAVE_GETCWD )

/* Retrieves the current working directory
 * This function uses the POSIX getcwd function or equivalent
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_get_current_working_directory_wide(
     wchar_t **current_working_directory,
     size_t *current_working_directory_size,
     libcerror_error_t **error )
{
	static char *function                          = "libcpath_path_get_current_working_directory_wide";
	wchar_t *safe_current_working_directory        = NULL;
	char *narrow_current_working_directory         = 0;
	size_t narrow_current_working_directory_length = 0;
	size_t safe_current_working_directory_size     = 0;

	if( current_working_directory == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid current working directory.",
		 function );

		return( -1 );
	}
	if( *current_working_directory != NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_ALREADY_SET,
		 "%s: invalid current working directory value already set.",
		 function );

		return( -1 );
	}
	if( current_working_directory_size == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid current working directory size.",
		 function );

		return( -1 );
	}
	narrow_current_working_directory = narrow_string_allocate(
	                                    PATH_MAX );

	if( narrow_current_working_directory == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_INSUFFICIENT,
		 "%s: unable to create narrow current working directory.",
		 function );

		goto on_error;
	}
	if( getcwd(
	     narrow_current_working_directory,
	     PATH_MAX ) == NULL )
	{
		libcerror_system_set_error(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
		 errno,
		 "%s: unable to retrieve current working directory.",
		 function );

		goto on_error;
	}
	narrow_current_working_directory_length = narrow_string_length(
	                                           narrow_current_working_directory );

	/* Convert the current working directory to a wide string
	 * if the platform has no wide character open function
	 */
	if( libcpath_system_string_size_from_narrow_string(
	     narrow_current_working_directory,
	     narrow_current_working_directory_length + 1,
	     &safe_current_working_directory_size,
	     error ) != 1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_CONVERSION,
		 LIBCERROR_CONVERSION_ERROR_GENERIC,
		 "%s: unable to determine wide character current working directory size.",
		 function );

		return( -1 );
	}
	safe_current_working_directory = wide_string_allocate(
	                                  safe_current_working_directory_size );

	if( safe_current_working_directory == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_INSUFFICIENT,
		 "%s: unable to create current working directory.",
		 function );

		goto on_error;
	}
	if( memory_set(
	     safe_current_working_directory,
	     0,
	     sizeof( wchar_t ) * safe_current_working_directory_size ) == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_SET_FAILED,
		 "%s: unable to clear current working directory.",
		 function );

		goto on_error;
	}
	if( libcpath_system_string_copy_to_wide_string(
	     narrow_current_working_directory,
	     narrow_current_working_directory_length + 1,
	     safe_current_working_directory,
	     safe_current_working_directory_size,
	     error ) != 1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_CONVERSION,
		 LIBCERROR_CONVERSION_ERROR_GENERIC,
		 "%s: unable to set current working directory.",
		 function );

		goto on_error;
	}
	memory_free(
	 narrow_current_working_directory );

	*current_working_directory      = safe_current_working_directory;
	*current_working_directory_size = safe_current_working_directory_size;

	return( 1 );

on_error:
	if( narrow_current_working_directory != NULL )
	{
		memory_free(
		 narrow_current_working_directory );
	}
	if( safe_current_working_directory != NULL )
	{
		memory_free(
		 safe_current_working_directory );
	}
	return( -1 );
}

#else
#error Missing get current working directory function
#endif

#if defined( WINAPI ) && ( WINVER <= 0x0500 )

/* Cross Windows safe version of GetFullPathNameW
 * Returns the number of characters in the current directory string or 0 on error
 */
DWORD libcpath_GetFullPathNameW(
       LPCWSTR filename,
       DWORD  buffer_size,
       LPWSTR  buffer,
       LPWSTR  *file_part )
{
	FARPROC function       = NULL;
	HMODULE library_handle = NULL;
	DWORD result           = 0;

	library_handle = LoadLibrary(
	                  _SYSTEM_STRING( "kernel32.dll" ) );

	if( library_handle == NULL )
	{
		return( 0 );
	}
	function = GetProcAddress(
		    library_handle,
		    (LPCSTR) "GetFullPathNameW" );

	if( function != NULL )
	{
		result = function(
			  filename,
			  buffer_size,
			  buffer,
			  file_part );
	}
	/* This call should be after using the function
	 * in most cases kernel32.dll will still be available after free
	 */
	if( FreeLibrary(
	     library_handle ) != TRUE )
	{
		libcpath_CloseHandle(
		 library_handle );

		return( 0 );
	}
	return( result );
}

#endif /* defined( WINAPI ) && ( WINVER <= 0x0500 ) */

#if defined( WINAPI )

/* Determines the path type
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_get_path_type_wide(
     const wchar_t *path,
     size_t path_length,
     uint8_t *path_type,
     libcerror_error_t **error )
{
	static char *function = "libcpath_path_get_path_type_wide";

	if( path == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid path.",
		 function );

		return( -1 );
	}
	if( ( path_length == 0 )
	 || ( path_length > (size_t) ( SSIZE_MAX - 1 ) ) )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid path length value out of bounds.",
		 function );

		return( -1 );
	}
	if( path_type == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid path type.",
		 function );

		return( -1 );
	}
	*path_type = LIBCPATH_TYPE_RELATIVE;

	/* Determine if the path is a special path
	 * device path prefix:          \\.\
	 * extended-length path prefix: \\?\
	 */
	if( ( path_length >= 4 )
	 && ( path[ 0 ] == (wchar_t) '\\' )
	 && ( path[ 1 ] == (wchar_t) '\\' )
	 && ( ( path[ 2 ] == (wchar_t) '.' )
	  ||  ( path[ 2 ] == (wchar_t) '?' ) )
	 && ( path[ 3 ] == (wchar_t) '\\' ) )
	{
		if( path[ 2 ] == (wchar_t) '.' )
		{
			*path_type = LIBCPATH_TYPE_DEVICE;
		}
		/* Determine if the path in an extended-length UNC path
		 * \\?\UNC\server\share
		 */
		else if( ( path_length >= 8 )
		      && ( path[ 4 ] == (wchar_t) 'U' )
		      && ( path[ 5 ] == (wchar_t) 'N' )
		      && ( path[ 6 ] == (wchar_t) 'C' )
		      && ( path[ 7 ] == (wchar_t) '\\' ) )
		{
			*path_type = LIBCPATH_TYPE_EXTENDED_LENGTH_UNC;
		}
		else
		{
			*path_type = LIBCPATH_TYPE_EXTENDED_LENGTH;
		}
	}
	/* Determine if the path is an UNC path
	 * \\server\share
	 */
	else if( ( path_length >= 2 )
	      && ( path[ 0 ] == (wchar_t) '\\' )
	      && ( path[ 1 ] == (wchar_t) '\\' ) )
	{
		*path_type = LIBCPATH_TYPE_UNC;
	}
	else if( path[ 0 ] == (wchar_t) '\\' )
	{
		*path_type = LIBCPATH_TYPE_ABSOLUTE;
	}
	else if( ( path_length >= 3 )
	      && ( path[ 1 ] == (wchar_t) ':' )
	      && ( path[ 2 ] == (wchar_t) '\\' )
	      && ( ( ( path[ 0 ] >= (wchar_t) 'A' )
	        &&   ( path[ 0 ] <= (wchar_t) 'Z' ) )
	       ||  ( ( path[ 0 ] >= (wchar_t) 'a' )
	        &&   ( path[ 0 ] <= (wchar_t) 'z' ) ) ) )
	{
		*path_type = LIBCPATH_TYPE_ABSOLUTE;
	}
	return( 1 );
}

/* Determines the volume name
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_get_volume_name_wide(
     const wchar_t *path,
     size_t path_length,
     wchar_t **volume_name,
     size_t *volume_name_length,
     size_t *directory_name_index,
     libcerror_error_t **error )
{
	static char *function    = "libcpath_path_get_volume_name_wide";
	size_t path_index        = 0;
	size_t share_name_index  = 0;
	size_t volume_name_index = 0;

	if( path == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid path.",
		 function );

		return( -1 );
	}
	if( ( path_length == 0 )
	 || ( path_length > (size_t) ( SSIZE_MAX - 1 ) ) )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid path length value out of bounds.",
		 function );

		return( -1 );
	}
	if( volume_name == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid volume name.",
		 function );

		return( -1 );
	}
	if( volume_name_length == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid volume name length.",
		 function );

		return( -1 );
	}
	if( directory_name_index == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid directory name index.",
		 function );

		return( -1 );
	}
	*volume_name          = NULL;
	*volume_name_length   = 0;
	*directory_name_index = 0;

	/* Determine if the path is a special path
	 * device path prefix:          \\.\
	 * extended-length path prefix: \\?\
	 */
	if( ( path_length >= 4 )
	 && ( path[ 0 ] == (wchar_t) '\\' )
	 && ( path[ 1 ] == (wchar_t) '\\' )
	 && ( ( path[ 2 ] == (wchar_t) '.' )
	  ||  ( path[ 2 ] == (wchar_t) '?' ) )
	 && ( path[ 3 ] == (wchar_t) '\\' ) )
	{
		if( path[ 2 ] == (wchar_t) '.' )
		{
			volume_name_index = 4;
		}
		/* Determine if the path in an extended-length UNC path
		 * \\?\UNC\server\share
		 */
		else if( ( path_length >= 8 )
		      && ( path[ 4 ] == (wchar_t) 'U' )
		      && ( path[ 5 ] == (wchar_t) 'N' )
		      && ( path[ 6 ] == (wchar_t) 'C' )
		      && ( path[ 7 ] == (wchar_t) '\\' ) )
		{
			volume_name_index = 8;
		}
		else
		{
			volume_name_index = 4;
		}
	}
	/* Determine if the path is an UNC path
	 * \\server\share
	 */
	else if( ( path_length >= 2 )
	      && ( path[ 0 ] == (wchar_t) '\\' )
	      && ( path[ 1 ] == (wchar_t) '\\' ) )
	{
		volume_name_index = 2;
	}
	/* Check if the path contains a volume letter
	 */
	if( ( path_length >= 2 )
	 && ( volume_name_index <= ( path_length - 2 ) )
	 && ( path[ volume_name_index + 1 ] == (wchar_t) ':' )
	 && ( ( ( path[ volume_name_index ] >= (wchar_t) 'A' )
	   &&   ( path[ volume_name_index ] <= (wchar_t) 'Z' ) )
	  ||  ( ( path[ volume_name_index ] >= (wchar_t) 'a' )
	   &&   ( path[ volume_name_index ] <= (wchar_t) 'z' ) ) ) )
	{
		path_index = volume_name_index + 2;

		if( ( path_index < path_length )
		 && ( path[ path_index ] == (wchar_t) '\\' ) )
		{
			path_index++;
		}
		*volume_name        = (wchar_t *) &( path[ volume_name_index ] );
		*volume_name_length = path_index - volume_name_index;

		if( path[ path_index - 1 ] == (wchar_t) '\\' )
		{
			*volume_name_length -= 1;
		}
		*directory_name_index = path_index;
	}
	else if( volume_name_index == 4 )
	{
		for( path_index = volume_name_index;
		     path_index < path_length;
		     path_index++ )
		{
			if( path[ path_index ] == (wchar_t) '\\' )
			{
				path_index++;

				break;
			}
		}
		*volume_name        = (wchar_t *) &( path[ 4 ] );
		*volume_name_length = path_index - 4;

		if( path[ path_index - 1 ] == (wchar_t) '\\' )
		{
			*volume_name_length -= 1;
		}
		*directory_name_index = path_index;
	}
	else if( ( volume_name_index == 2 )
	      || ( volume_name_index == 8 ) )
	{
		for( share_name_index = volume_name_index;
		     share_name_index < path_length;
		     share_name_index++ )
		{
			if( path[ share_name_index ] == (wchar_t) '\\' )
			{
				share_name_index++;

				break;
			}
		}
		if( share_name_index > path_length )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
			 "%s: invalid path - missing share name.",
			 function );

			return( -1 );
		}
		for( path_index = share_name_index;
		     path_index < path_length;
		     path_index++ )
		{
			if( path[ path_index ] == (wchar_t) '\\' )
			{
				path_index++;

				break;
			}
		}
		*volume_name        = (wchar_t *) &( path[ volume_name_index ] );
		*volume_name_length = path_index - volume_name_index;

		if( path[ path_index - 1 ] == (wchar_t) '\\' )
		{
			*volume_name_length -= 1;
		}
		*directory_name_index = path_index;
	}
	return( 1 );
}

/* Retrieves the current working directory of a specific volume
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_get_current_working_directory_by_volume_wide(
     wchar_t *volume_name,
     size_t volume_name_length,
     wchar_t **current_working_directory,
     size_t *current_working_directory_size,
     libcerror_error_t **error )
{
	static char *function                     = "libcpath_path_get_current_working_directory_by_volume_wide";
	wchar_t *safe_current_working_directory   = NULL;
	DWORD error_code                          = 0;
	DWORD result                              = 0;
	DWORD safe_current_working_directory_size = 0;

	if( volume_name == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid volume name.",
		 function );

		return( -1 );
	}
	if( ( volume_name_length == 0 )
	 || ( volume_name_length > (size_t) ( SSIZE_MAX - 1 ) ) )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid volume name length value out of bounds.",
		 function );

		return( -1 );
	}
	if( current_working_directory == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid current working directory.",
		 function );

		return( -1 );
	}
	if( *current_working_directory != NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_ALREADY_SET,
		 "%s: invalid current working directory value already set.",
		 function );

		return( -1 );
	}
	if( current_working_directory_size == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid current working directory size.",
		 function );

		return( -1 );
	}
#if WINVER <= 0x0500
	safe_current_working_directory_size = libcpath_GetFullPathNameW(
	                                       volume_name,
	                                       0,
	                                       NULL,
	                                       NULL );
#else
	safe_current_working_directory_size = GetFullPathNameW(
	                                       volume_name,
	                                       0,
	                                       NULL,
	                                       NULL );
#endif
	if( safe_current_working_directory_size == 0 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
		 "%s: unable to retrieve current working directory size.",
		 function );

		goto on_error;
	}
	if( (size_t) safe_current_working_directory_size > (size_t) SSIZE_MAX )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid current working directory size value out of bounds.",
		 function );

		goto on_error;
	}
	safe_current_working_directory = wide_string_allocate(
	                                  safe_current_working_directory_size );

	if( safe_current_working_directory == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_INSUFFICIENT,
		 "%s: unable to create current working directory.",
		 function );

		goto on_error;
	}
	if( memory_set(
	     safe_current_working_directory,
	     0,
	     sizeof( wchar_t ) * safe_current_working_directory_size ) == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_SET_FAILED,
		 "%s: unable to clear current working directory.",
		 function );

		goto on_error;
	}
#if WINVER <= 0x0500
	result = libcpath_GetFullPathNameW(
	          volume_name,
	          safe_current_working_directory_size,
	          safe_current_working_directory,
	          NULL );
#else
	result = GetFullPathNameW(
	          volume_name,
	          safe_current_working_directory_size,
	          safe_current_working_directory,
	          NULL );
#endif
	/* Note that safe_current_working_directory_size can be larger than result
	 */
	if( ( result == 0 )
	 || ( result > safe_current_working_directory_size ) )
	{
		error_code = GetLastError();

		libcerror_system_set_error(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
		 error_code,
		 "%s: unable to retrieve current working directory.",
		 function );

		goto on_error;
	}
	*current_working_directory      = safe_current_working_directory;
	*current_working_directory_size = (size_t) safe_current_working_directory_size;

	return( 1 );

on_error:
	if( safe_current_working_directory != NULL )
	{
		memory_free(
		 safe_current_working_directory );
	}
	return( -1 );
}

/* Determines the full path of the Windows path specified
 * The function uses the extended-length path format
 * (path with \\?\ prefix)
 *
 * Multiple successive \ not at the start of the path are combined into one
 *
 * Scenario's that are considered full paths:
 * Device path:			\\.\PhysicalDrive0
 * Extended-length path:	\\?\C:\directory\file.txt
 * Extended-length UNC path:	\\?\UNC\server\share\directory\file.txt
 *
 * Scenario's that are not considered full paths:
 * Local 'absolute' path:	\directory\file.txt
 * Local 'relative' path:	..\directory\file.txt
 * Local 'relative' path:	.\directory\file.txt
 * Volume 'absolute' path:	C:\directory\file.txt
 *                              C:\..\directory\file.txt
 * Volume 'relative' path:	C:directory\file.txt
 * UNC path:			\\server\share\directory\file.txt
 *
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_get_full_path_wide(
     const wchar_t *path,
     size_t path_length,
     wchar_t **full_path,
     size_t *full_path_size,
     libcerror_error_t **error )
{
	libcsplit_wide_split_string_t *current_directory_split_string = NULL;
	libcsplit_wide_split_string_t *path_split_string              = NULL;
	wchar_t *current_directory                                    = NULL;
	wchar_t *current_directory_string_segment                     = NULL;
	wchar_t *full_path_prefix                                     = NULL;
	wchar_t *last_used_path_string_segment                        = NULL;
	wchar_t *path_string_segment                                  = NULL;
	wchar_t *safe_full_path                                       = NULL;
	wchar_t *volume_name                                          = NULL;
	static char *function                                         = "libcpath_path_get_full_path_wide";
	size_t current_directory_length                               = 0;
	size_t current_directory_name_index                           = 0;
	size_t current_directory_size                                 = 0;
	size_t current_directory_string_segment_size                  = 0;
	size_t full_path_index                                        = 0;
	size_t full_path_prefix_length                                = 0;
	size_t last_used_path_string_segment_size                     = 0;
	size_t path_directory_name_index                              = 0;
	size_t path_string_segment_length                             = 0;
	size_t path_string_segment_size                               = 0;
	size_t safe_full_path_size                                    = 0;
	size_t volume_name_length                                     = 0;
	uint8_t path_type                                             = LIBCPATH_TYPE_RELATIVE;
	int current_directory_number_of_segments                      = 0;
	int current_directory_segment_index                           = 0;
	int last_used_path_segment_index                              = -1;
	int path_number_of_segments                                   = 0;
	int path_segment_index                                        = 0;
	int result                                                    = 0;

	if( path == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid path.",
		 function );

		return( -1 );
	}
	if( ( path_length == 0 )
	 || ( path_length > (size_t) ( SSIZE_MAX - 1 ) ) )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid path length value out of bounds.",
		 function );

		return( -1 );
	}
	if( full_path == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid full path.",
		 function );

		return( -1 );
	}
	if( *full_path != NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_ALREADY_SET,
		 "%s: invalid full path value already set.",
		 function );

		return( -1 );
	}
	if( full_path_size == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid full path size.",
		 function );

		return( -1 );
	}
	if( libcpath_path_get_path_type_wide(
	     path,
	     path_length,
	     &path_type,
	     error ) != 1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
		 "%s: unable to determine path type.",
		 function );

		goto on_error;
	}
	if( libcpath_path_get_volume_name_wide(
	     path,
	     path_length,
	     &volume_name,
	     &volume_name_length,
	     &path_directory_name_index,
	     error ) != 1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
		 "%s: unable to determine volume name.",
		 function );

		goto on_error;
	}
	/* If the path is a device path, an extended-length path or an UNC
	 * do not bother to lookup the current working directory
	 */
	if( ( path_type != LIBCPATH_TYPE_DEVICE )
	 && ( path_type != LIBCPATH_TYPE_EXTENDED_LENGTH )
	 && ( path_type != LIBCPATH_TYPE_EXTENDED_LENGTH_UNC )
	 && ( path_type != LIBCPATH_TYPE_UNC ) )
	{
		if( volume_name == NULL )
		{
			result = libcpath_path_get_current_working_directory_wide(
			          &current_directory,
			          &current_directory_size,
			          error );
		}
		else
		{
			result = libcpath_path_get_current_working_directory_by_volume_wide(
			          volume_name,
			          volume_name_length,
			          &current_directory,
			          &current_directory_size,
			          error );
		}
		if( result != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
			 "%s: unable to retrieve current working directory.",
			 function );

			goto on_error;
		}
		if( volume_name == NULL )
		{
			/* Determine the volume name using the current working directory if necessary
			 */
			if( libcpath_path_get_volume_name_wide(
			     current_directory,
			     current_directory_size - 1,
			     &volume_name,
			     &volume_name_length,
			     &current_directory_name_index,
			     error ) != 1 )
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_RUNTIME,
				 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
				 "%s: unable to determine volume name from current working directory.",
				 function );

				goto on_error;
			}
		}
	}
	/* The full path prefix consists of "\\?\" or "\\.\"
	 */
	full_path_prefix_length = 4;

	if( volume_name != NULL )
	{
		/* Add the volume or device name
		 */
		full_path_prefix_length += volume_name_length;
	}
	if( ( path_type == LIBCPATH_TYPE_EXTENDED_LENGTH_UNC )
	 || ( path_type == LIBCPATH_TYPE_UNC ) )
	{
		/* Add the "UNC\" prefix
		 */
		full_path_prefix_length += 4;
	}
	if( ( current_directory != NULL )
	 && ( current_directory_name_index < current_directory_size ) )
	{
		current_directory_length = wide_string_length(
		                            &( current_directory[ current_directory_name_index ] ) );

		if( libcsplit_wide_string_split(
		     &( current_directory[ current_directory_name_index ] ),
		     current_directory_length + 1,
		     (wchar_t) '\\',
		     &current_directory_split_string,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_INITIALIZE_FAILED,
			 "%s: unable to split current working directory.",
			 function );

			goto on_error;
		}
		if( libcsplit_wide_split_string_get_number_of_segments(
		     current_directory_split_string,
		     &current_directory_number_of_segments,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
			 "%s: unable to retrieve number of current working directory string segments.",
			 function );

			goto on_error;
		}
		current_directory_segment_index = current_directory_number_of_segments - 1;

		if( path_type == LIBCPATH_TYPE_RELATIVE )
		{
			/* If the path is relative
			 * add the size of the current working directory
			 * a directory separator, if necessary
			 */
			safe_full_path_size += ( current_directory_size - ( current_directory_name_index + 1 ) );

			if( ( current_directory_size >= 2 )
			 && ( current_directory[ current_directory_size - 2 ] != (wchar_t) '\\' ) )
			{
				safe_full_path_size += 1;
			}
		}
	}
	if( libcsplit_wide_string_split(
	     &( path[ path_directory_name_index ] ),
	     path_length - path_directory_name_index + 1,
	     (wchar_t) '\\',
	     &path_split_string,
	     error ) != 1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_INITIALIZE_FAILED,
		 "%s: unable to split path.",
		 function );

		goto on_error;
	}
	if( path_split_string != NULL )
	{
		if( libcsplit_wide_split_string_get_number_of_segments(
		     path_split_string,
		     &path_number_of_segments,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
			 "%s: unable to retrieve number of path string segments.",
			 function );

			goto on_error;
		}
		for( path_segment_index = 0;
		     path_segment_index < path_number_of_segments;
		     path_segment_index++ )
		{
			if( libcsplit_wide_split_string_get_segment_by_index(
			     path_split_string,
			     path_segment_index,
			     &path_string_segment,
			     &path_string_segment_size,
			     error ) != 1 )
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_RUNTIME,
				 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
				 "%s: unable to retrieve path string segment: %d.",
				 function,
				 path_segment_index );

				goto on_error;
			}
			if( path_string_segment == NULL )
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_RUNTIME,
				 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
				 "%s: missing path string segment: %d.",
				 function,
				 path_segment_index );

				goto on_error;
			}
			/* If the path is "" (empty) or "." (current) ignore the entry
			 */
			if( ( path_string_segment_size <= 1 )
			 || ( ( path_string_segment_size == 2 )
			  &&  ( path_string_segment[ 0 ] == (wchar_t) '.' ) ) )
			{
				if( libcsplit_wide_split_string_set_segment_by_index(
				     path_split_string,
				     path_segment_index,
				     NULL,
				     0,
				     error ) != 1 )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
					 "%s: unable to set path string segment: %d.",
					 function,
					 path_segment_index );

					goto on_error;
				}
			}
			/* If the path is ".." (parent) reverse the current path by one directory
			 */
			else if( ( path_string_segment_size == 3 )
			      && ( path_string_segment[ 0 ] == (wchar_t) '.' )
			      && ( path_string_segment[ 1 ] == (wchar_t) '.' ) )
			{
				if( ( current_directory_split_string != NULL )
				 && ( last_used_path_segment_index == -1 ) )
				{
					if( libcsplit_wide_split_string_get_segment_by_index(
					     current_directory_split_string,
					     current_directory_segment_index,
					     &current_directory_string_segment,
					     &current_directory_string_segment_size,
					     error ) != 1 )
					{
						libcerror_error_set(
						 error,
						 LIBCERROR_ERROR_DOMAIN_RUNTIME,
						 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
						 "%s: unable to retrieve current working directory string segment: %d.",
						 function,
						 current_directory_segment_index );

						goto on_error;
					}
					if( current_directory_string_segment == NULL )
					{
						libcerror_error_set(
						 error,
						 LIBCERROR_ERROR_DOMAIN_RUNTIME,
						 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
						 "%s: missing current working directory string segment: %d.",
						 function,
						 current_directory_segment_index );

						goto on_error;
					}
					/* Remove the size of the parent directory name and a directory separator
					 * Note that the size includes the end of string character
					 */
					if( current_directory_string_segment_size < safe_full_path_size )
					{
						safe_full_path_size -= current_directory_string_segment_size;
					}
					else
					{
						safe_full_path_size = 0;
					}
					if( libcsplit_wide_split_string_set_segment_by_index(
					     current_directory_split_string,
					     current_directory_segment_index,
					     NULL,
					     0,
					     error ) != 1 )
					{
						libcerror_error_set(
						 error,
						 LIBCERROR_ERROR_DOMAIN_RUNTIME,
						 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
						 "%s: unable to set current working directory string segment: %d.",
						 function,
						 current_directory_segment_index );

						goto on_error;
					}
					current_directory_segment_index--;
				}
				else if( last_used_path_segment_index >= 0 )
				{
					if( libcsplit_wide_split_string_get_segment_by_index(
					     path_split_string,
					     last_used_path_segment_index,
					     &last_used_path_string_segment,
					     &last_used_path_string_segment_size,
					     error ) != 1 )
					{
						libcerror_error_set(
						 error,
						 LIBCERROR_ERROR_DOMAIN_RUNTIME,
						 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
						 "%s: unable to retrieve last used path string segment: %d.",
						 function,
						 last_used_path_segment_index );

						goto on_error;
					}
					if( last_used_path_string_segment == NULL )
					{
						libcerror_error_set(
						 error,
						 LIBCERROR_ERROR_DOMAIN_RUNTIME,
						 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
						 "%s: missing last used path string string segment: %d.",
						 function,
						 last_used_path_segment_index );

						goto on_error;
					}
					/* Remove the size of the parent directory name and a directory separator
					 * Note that the size includes the end of string character
					 */
					if( last_used_path_string_segment_size < safe_full_path_size )
					{
						safe_full_path_size -= last_used_path_string_segment_size;
					}
					else
					{
						safe_full_path_size = 0;
					}
					if( libcsplit_wide_split_string_set_segment_by_index(
					     path_split_string,
					     last_used_path_segment_index,
					     NULL,
					     0,
					     error ) != 1 )
					{
						libcerror_error_set(
						 error,
						 LIBCERROR_ERROR_DOMAIN_RUNTIME,
						 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
						 "%s: unable to set path string segment: %d.",
						 function,
						 last_used_path_segment_index );

						goto on_error;
					}
					/* Find the previous path split value that contains a name
					 */
					while( last_used_path_segment_index > 0 )
					{
						last_used_path_segment_index--;

						if( libcsplit_wide_split_string_get_segment_by_index(
						     path_split_string,
						     last_used_path_segment_index,
						     &last_used_path_string_segment,
						     &last_used_path_string_segment_size,
						     error ) != 1 )
						{
							libcerror_error_set(
							 error,
							 LIBCERROR_ERROR_DOMAIN_RUNTIME,
							 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
							 "%s: unable to retrieve last used path string segment: %d.",
							 function,
							 last_used_path_segment_index );

							goto on_error;
						}
						if( last_used_path_string_segment_size != 0 )
						{
							break;
						}
					}
				}
				if( libcsplit_wide_split_string_set_segment_by_index(
				     path_split_string,
				     path_segment_index,
				     NULL,
				     0,
				     error ) != 1 )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
					 "%s: unable to set path string segment: %d.",
					 function,
					 path_segment_index );

					goto on_error;
				}
			}
			else
			{
				/* Add the size of the directory or file name and a directory separator
				 * Note that the size includes the end of string character
				 */
				safe_full_path_size += path_string_segment_size;

				last_used_path_segment_index = path_segment_index;
			}
		}
		/* Use the size reserved of the last directory separator for
		 * the directory separator after the path prefix.
		 */
	}
	safe_full_path_size += full_path_prefix_length + 1;

	full_path_index = 0;

	safe_full_path = wide_string_allocate(
	                  safe_full_path_size );

	if( safe_full_path == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_INSUFFICIENT,
		 "%s: unable to create full path.",
		 function );

		goto on_error;
	}
	if( memory_set(
	     safe_full_path,
	     0,
	     sizeof( wchar_t ) * safe_full_path_size ) == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_SET_FAILED,
		 "%s: unable to clear full path.",
		 function );

		goto on_error;
	}
	if( path_type == LIBCPATH_TYPE_DEVICE )
	{
		full_path_prefix        = L"\\\\.\\";
		full_path_prefix_length = 4;
	}
	else
	{
		full_path_prefix        = L"\\\\?\\";
		full_path_prefix_length = 4;
	}
	if( full_path_prefix_length > ( safe_full_path_size - full_path_index ) )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid full path size value out of bounds.",
		 function );

		goto on_error;
	}
	if( wide_string_copy(
	     &( safe_full_path[ full_path_index ] ),
	     full_path_prefix,
	     full_path_prefix_length ) == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
		 "%s: unable to set prefix in full path.",
		 function );

		goto on_error;
	}
	full_path_index += full_path_prefix_length;

	/* If there is a share name the path is an UNC path
	 */
	if( ( path_type == LIBCPATH_TYPE_EXTENDED_LENGTH_UNC )
	 || ( path_type == LIBCPATH_TYPE_UNC ) )
	{
		if( ( safe_full_path_size - full_path_index ) < 4 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
			 LIBCERROR_ARGUMENT_ERROR_VALUE_OUT_OF_BOUNDS,
			 "%s: invalid full path size value out of bounds.",
			 function );

			goto on_error;
		}
		if( wide_string_copy(
		     &( safe_full_path[ full_path_index ] ),
		     L"UNC\\",
		     4 ) == NULL )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
			 "%s: unable to set UNC\\ prefix in full path.",
			 function );

			goto on_error;
		}
		full_path_index += 4;
	}
	if( volume_name != NULL )
	{
		if( volume_name_length > ( safe_full_path_size - full_path_index ) )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
			 LIBCERROR_ARGUMENT_ERROR_VALUE_OUT_OF_BOUNDS,
			 "%s: invalid full path size value out of bounds.",
			 function );

			goto on_error;
		}
		if( wide_string_copy(
		     &( safe_full_path[ full_path_index ] ),
		     volume_name,
		     volume_name_length ) == NULL )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
			 "%s: unable to set volume name in full path.",
			 function );

			goto on_error;
		}
		full_path_index += volume_name_length;

		safe_full_path[ full_path_index++ ] = (wchar_t) '\\';
	}
	/* If the path is relative
	 * add the current working directory elements
	 */
	if( ( path_type == LIBCPATH_TYPE_RELATIVE )
	 && ( current_directory_split_string != NULL ) )
	{
		for( current_directory_segment_index = 0;
		     current_directory_segment_index < current_directory_number_of_segments;
		     current_directory_segment_index++ )
		{
			if( libcsplit_wide_split_string_get_segment_by_index(
			     current_directory_split_string,
			     current_directory_segment_index,
			     &current_directory_string_segment,
			     &current_directory_string_segment_size,
			     error ) != 1 )
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_RUNTIME,
				 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
				 "%s: unable to retrieve current working directory string segment: %d.",
				 function,
				 current_directory_segment_index );

				goto on_error;
			}
			if( current_directory_string_segment_size != 0 )
			{
				if( current_directory_string_segment == NULL )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
					 "%s: missing current working directory string segment: %d.",
					 function,
					 current_directory_segment_index );

					goto on_error;
				}
				/* Note that here we should have room for the segment string and
				 * one additional character
				 */
				if( current_directory_string_segment_size > ( safe_full_path_size - full_path_index ) )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_VALUE_OUT_OF_BOUNDS,
					 "%s: invalid current working directory string segment: %d size value out of bounds.",
					 function,
					 current_directory_segment_index );

					goto on_error;
				}
				path_string_segment_length = current_directory_string_segment_size - 1;

				if( wide_string_copy(
				     &( safe_full_path[ full_path_index ] ),
				     current_directory_string_segment,
				     path_string_segment_length ) == NULL )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
					 "%s: unable to set current working directory split value: %d in full path.",
					 function,
					 current_directory_segment_index );

					goto on_error;
				}
				full_path_index += path_string_segment_length;

				safe_full_path[ full_path_index++ ] = (wchar_t) '\\';
			}
		}
	}
	if( path_split_string != NULL )
	{
		for( path_segment_index = 0;
		     path_segment_index < path_number_of_segments;
		     path_segment_index++ )
		{
			if( libcsplit_wide_split_string_get_segment_by_index(
			     path_split_string,
			     path_segment_index,
			     &path_string_segment,
			     &path_string_segment_size,
			     error ) != 1 )
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_RUNTIME,
				 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
				 "%s: unable to retrieve path string segment: %d.",
				 function,
				 path_segment_index );

				goto on_error;
			}
			if( path_string_segment_size != 0 )
			{
				if( path_string_segment == NULL )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
					 "%s: missing path string segment: %d.",
					 function,
					 path_segment_index );

					goto on_error;
				}
				/* Note that here we should have room for the segment string and
				 * one additional character
				 */
				if( path_string_segment_size > ( safe_full_path_size - full_path_index ) )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_VALUE_OUT_OF_BOUNDS,
					 "%s: invalid current path string segment: %d size value out of bounds.",
					 function,
					 path_segment_index );

					goto on_error;
				}
				path_string_segment_length = path_string_segment_size - 1;

				if( wide_string_copy(
				     &( safe_full_path[ full_path_index ] ),
				     path_string_segment,
				     path_string_segment_length ) == NULL )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
					 "%s: unable to set path split value: %d in full path.",
					 function,
					 path_segment_index );

					goto on_error;
				}
				full_path_index += path_string_segment_length;

				safe_full_path[ full_path_index++ ] = (wchar_t) '\\';
			}
		}
	}
	safe_full_path[ full_path_index - 1 ] = 0;

	if( path_split_string != NULL )
	{
		if( libcsplit_wide_split_string_free(
		     &path_split_string,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_FINALIZE_FAILED,
			 "%s: unable to free path split string.",
			 function );

			goto on_error;
		}
	}
	if( current_directory_split_string != NULL )
	{
		if( libcsplit_wide_split_string_free(
		     &current_directory_split_string,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_FINALIZE_FAILED,
			 "%s: unable to free current working directory split string.",
			 function );

			goto on_error;
		}
	}
	if( current_directory != NULL )
	{
		memory_free(
		 current_directory );
	}
	*full_path      = safe_full_path;
	*full_path_size = safe_full_path_size;

	return( 1 );

on_error:
	if( safe_full_path != NULL )
	{
		memory_free(
		 safe_full_path );
	}
	if( path_split_string != NULL )
	{
		libcsplit_wide_split_string_free(
		 &path_split_string,
		 NULL );
	}
	if( current_directory_split_string != NULL )
	{
		libcsplit_wide_split_string_free(
		 &current_directory_split_string,
		 NULL );
	}
	if( current_directory != NULL )
	{
		memory_free(
		 current_directory );
	}
	return( -1 );
}

#else

/* Determines the full path of the POSIX path specified
 * Multiple successive / are combined into one
 *
 * Scenarios:
 * /home/user/file.txt
 * /home/user//file.txt
 * /home/user/../user/file.txt
 * /../home/user/file.txt
 * user/../user/file.txt
 *
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_get_full_path_wide(
     const wchar_t *path,
     size_t path_length,
     wchar_t **full_path,
     size_t *full_path_size,
     libcerror_error_t **error )
{
	libcsplit_wide_split_string_t *current_directory_split_string = NULL;
	libcsplit_wide_split_string_t *path_split_string              = NULL;
	wchar_t *current_directory                                    = NULL;
	wchar_t *current_directory_string_segment                     = NULL;
	wchar_t *last_used_path_string_segment                        = NULL;
	wchar_t *path_string_segment                                  = NULL;
	wchar_t *safe_full_path                                       = NULL;
	static char *function                                         = "libcpath_path_get_full_path_wide";
	size_t current_directory_index                                = 0;
	size_t current_directory_length                               = 0;
	size_t current_directory_size                                 = 0;
	size_t current_directory_string_segment_size                  = 0;
	size_t full_path_index                                        = 0;
	size_t full_path_prefix_length                                = 0;
	size_t last_used_path_string_segment_size                     = 0;
	size_t path_index                                             = 0;
	size_t path_string_segment_length                             = 0;
	size_t path_string_segment_size                               = 0;
	size_t safe_full_path_size                                    = 0;
	uint8_t path_type                                             = LIBCPATH_TYPE_RELATIVE;
	int current_directory_number_of_segments                      = 0;
	int current_directory_segment_index                           = 0;
	int last_used_path_segment_index                              = -1;
	int path_number_of_segments                                   = 0;
	int path_segment_index                                        = 0;

	if( path == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid path.",
		 function );

		return( -1 );
	}
#if defined( __MINGW32__ )
	if( ( path_length < 2 )
	 || ( path_length > (size_t) ( SSIZE_MAX - 1 ) ) )
#else
	if( ( path_length < 1 )
	 || ( path_length > (size_t) ( SSIZE_MAX - 1 ) ) )
#endif
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid path length value out of bounds.",
		 function );

		return( -1 );
	}
	if( full_path == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid full path.",
		 function );

		return( -1 );
	}
	if( *full_path != NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_ALREADY_SET,
		 "%s: invalid full path value already set.",
		 function );

		return( -1 );
	}
	if( full_path_size == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid full path size.",
		 function );

		return( -1 );
	}
	/* Determine the full path size
	 */
#if defined( __MINGW32__ )
	if( path[ 1 ] == (wchar_t) ':' )
	{
		path_index = 2;
	}
	current_directory_index = 3;
#endif
	full_path_prefix_length = current_directory_index;

	if( path[ path_index ] == LIBCPATH_SEPARATOR )
	{
		path_type = LIBCPATH_TYPE_ABSOLUTE;

#if !defined( __MINGW32__ )
		/* If the path is absolute
		 * a directory separator
		 */
		full_path_prefix_length += 1;
#endif
	}
	if( ( path_type == LIBCPATH_TYPE_RELATIVE )
	 || ( path_index != current_directory_index ) )
	{
		if( libcpath_path_get_current_working_directory_wide(
		     &current_directory,
		     &current_directory_size,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
			 "%s: unable to retrieve current working directory.",
			 function );

			goto on_error;
		}
		if( current_directory == NULL )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
			 "%s: missing current working directory.",
			 function );

			goto on_error;
		}
		/* We need to use the length here since current_directory_size will be PATH_MAX
		 */
		current_directory_length = wide_string_length(
		                            current_directory );

		if( current_directory_index > current_directory_length )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_VALUE_OUT_OF_BOUNDS,
			 "%s: invalid current working directory length value out of bounds.",
			 function );

			goto on_error;
		}
		current_directory_length -= current_directory_index;
	}
	if( path_type == LIBCPATH_TYPE_RELATIVE )
	{
		if( libcsplit_wide_string_split(
		     &( current_directory[ current_directory_index ] ),
		     current_directory_length + 1,
		     (wchar_t) LIBCPATH_SEPARATOR,
		     &current_directory_split_string,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_INITIALIZE_FAILED,
			 "%s: unable to split current working directory.",
			 function );

			goto on_error;
		}
		if( libcsplit_wide_split_string_get_number_of_segments(
		     current_directory_split_string,
		     &current_directory_number_of_segments,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
			 "%s: unable to retrieve number of current working directory string segments.",
			 function );

			goto on_error;
		}
		current_directory_segment_index = current_directory_number_of_segments - 1;

		/* If the path is relative
		 * add the size of the current working directory
		 * a directory separator, if necessary
		 */
		safe_full_path_size = current_directory_length;

		if( ( current_directory_length >= 1 )
		 && ( current_directory[ current_directory_length - 1 ] != (wchar_t) LIBCPATH_SEPARATOR ) )
		{
			safe_full_path_size++;
		}
	}
	if( path_index > path_length )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid path length value out of bounds.",
		 function );

		goto on_error;
	}
	if( libcsplit_wide_string_split(
	     &( path[ path_index ] ),
	     ( path_length - path_index ) + 1,
	     (wchar_t) LIBCPATH_SEPARATOR,
	     &path_split_string,
	     error ) != 1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_INITIALIZE_FAILED,
		 "%s: unable to split path.",
		 function );

		goto on_error;
	}
	if( path_split_string != NULL )
	{
		if( libcsplit_wide_split_string_get_number_of_segments(
		     path_split_string,
		     &path_number_of_segments,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
			 "%s: unable to retrieve number of path string segments.",
			 function );

			goto on_error;
		}
		for( path_segment_index = 0;
		     path_segment_index < path_number_of_segments;
		     path_segment_index++ )
		{
			if( libcsplit_wide_split_string_get_segment_by_index(
			     path_split_string,
			     path_segment_index,
			     &path_string_segment,
			     &path_string_segment_size,
			     error ) != 1 )
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_RUNTIME,
				 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
				 "%s: unable to retrieve path string segment: %d.",
				 function,
				 path_segment_index );

				goto on_error;
			}
			if( path_string_segment == NULL )
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_RUNTIME,
				 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
				 "%s: missing path string segment: %d.",
				 function,
				 path_segment_index );

				goto on_error;
			}
			/* If the path is "" (empty) or "." (current) ignore the entry
			 */
			if( ( path_string_segment_size <= 1 )
			 || ( ( path_string_segment_size == 2 )
			  &&  ( path_string_segment[ 0 ] == (wchar_t) '.' ) ) )
			{
				if( libcsplit_wide_split_string_set_segment_by_index(
				     path_split_string,
				     path_segment_index,
				     NULL,
				     0,
				     error ) != 1 )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
					 "%s: unable to set path string segment: %d.",
					 function,
					 path_segment_index );

					goto on_error;
				}
			}
			/* If the path is ".." (parent) reverse the current path by one directory
			 */
			else if( ( path_string_segment_size == 3 )
			      && ( path_string_segment[ 0 ] == (wchar_t) '.' )
			      && ( path_string_segment[ 1 ] == (wchar_t) '.' ) )
			{
				if( ( current_directory_split_string != NULL )
				 && ( last_used_path_segment_index == -1 ) )
				{
					if( libcsplit_wide_split_string_get_segment_by_index(
					     current_directory_split_string,
					     current_directory_segment_index,
					     &current_directory_string_segment,
					     &current_directory_string_segment_size,
					     error ) != 1 )
					{
						libcerror_error_set(
						 error,
						 LIBCERROR_ERROR_DOMAIN_RUNTIME,
						 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
						 "%s: unable to retrieve current working directory string segment: %d.",
						 function,
						 current_directory_segment_index );

						goto on_error;
					}
					if( current_directory_string_segment == NULL )
					{
						libcerror_error_set(
						 error,
						 LIBCERROR_ERROR_DOMAIN_RUNTIME,
						 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
						 "%s: missing current working directory string segment: %d.",
						 function,
						 current_directory_segment_index );

						goto on_error;
					}
					/* Remove the size of the parent directory name and a directory separator
					 * Note that the size includes the end of string character
					 */
					if( current_directory_string_segment_size < safe_full_path_size )
					{
						safe_full_path_size -= current_directory_string_segment_size;
					}
					else
					{
						safe_full_path_size = 0;
					}
					if( libcsplit_wide_split_string_set_segment_by_index(
					     current_directory_split_string,
					     current_directory_segment_index,
					     NULL,
					     0,
					     error ) != 1 )
					{
						libcerror_error_set(
						 error,
						 LIBCERROR_ERROR_DOMAIN_RUNTIME,
						 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
						 "%s: unable to set current working directory string segment: %d.",
						 function,
						 current_directory_segment_index );

						goto on_error;
					}
					current_directory_segment_index--;
				}
				else if( last_used_path_segment_index >= 0 )
				{
					if( libcsplit_wide_split_string_get_segment_by_index(
					     path_split_string,
					     last_used_path_segment_index,
					     &last_used_path_string_segment,
					     &last_used_path_string_segment_size,
					     error ) != 1 )
					{
						libcerror_error_set(
						 error,
						 LIBCERROR_ERROR_DOMAIN_RUNTIME,
						 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
						 "%s: unable to retrieve last used path string segment: %d.",
						 function,
						 last_used_path_segment_index );

						goto on_error;
					}
					if( last_used_path_string_segment == NULL )
					{
						libcerror_error_set(
						 error,
						 LIBCERROR_ERROR_DOMAIN_RUNTIME,
						 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
						 "%s: missing last used path string string segment: %d.",
						 function,
						 last_used_path_segment_index );

						goto on_error;
					}
					/* Remove the size of the parent directory name and a directory separator
					 * Note that the size includes the end of string character
					 */
					if( last_used_path_string_segment_size < safe_full_path_size )
					{
						safe_full_path_size -= last_used_path_string_segment_size;
					}
					else
					{
						safe_full_path_size = 0;
					}
					if( libcsplit_wide_split_string_set_segment_by_index(
					     path_split_string,
					     last_used_path_segment_index,
					     NULL,
					     0,
					     error ) != 1 )
					{
						libcerror_error_set(
						 error,
						 LIBCERROR_ERROR_DOMAIN_RUNTIME,
						 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
						 "%s: unable to set path string segment: %d.",
						 function,
						 last_used_path_segment_index );

						goto on_error;
					}
					/* Find the previous path split value that contains a name
					 */
					while( last_used_path_segment_index > 0 )
					{
						last_used_path_segment_index--;

						if( libcsplit_wide_split_string_get_segment_by_index(
						     path_split_string,
						     last_used_path_segment_index,
						     &last_used_path_string_segment,
						     &last_used_path_string_segment_size,
						     error ) != 1 )
						{
							libcerror_error_set(
							 error,
							 LIBCERROR_ERROR_DOMAIN_RUNTIME,
							 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
							 "%s: unable to retrieve last used path string segment: %d.",
							 function,
							 last_used_path_segment_index );

							goto on_error;
						}
						if( last_used_path_string_segment_size != 0 )
						{
							break;
						}
					}
				}
				if( libcsplit_wide_split_string_set_segment_by_index(
				     path_split_string,
				     path_segment_index,
				     NULL,
				     0,
				     error ) != 1 )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
					 "%s: unable to set path string segment: %d.",
					 function,
					 path_segment_index );

					goto on_error;
				}
			}
			else
			{
				/* Add the size of the directory or file name and a directory separator
				 * Note that the size includes the end of string character
				 */
				safe_full_path_size += path_string_segment_size;

				last_used_path_segment_index = path_segment_index;
			}
		}
#if defined( __MINGW32__ )
		if( ( safe_full_path_size > 0 )
		 && ( path_number_of_segments > 1 ) )
#else
		if( safe_full_path_size > 0 )
#endif
		{
			/* Remove the size reserved of the last directory separator
			 */
			safe_full_path_size -= 1;
		}
	}
	safe_full_path_size += full_path_prefix_length + 1;

	safe_full_path = wide_string_allocate(
	                  safe_full_path_size );

	if( safe_full_path == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_INSUFFICIENT,
		 "%s: unable to create full path.",
		 function );

		goto on_error;
	}
	if( memory_set(
	     safe_full_path,
	     0,
	     sizeof( wchar_t ) * safe_full_path_size ) == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_SET_FAILED,
		 "%s: unable to clear full path.",
		 function );

		goto on_error;
	}
	full_path_index = 0;

#if defined( __MINGW32__ )
	if( path_index == 2 )
	{
		safe_full_path[ full_path_index++ ] = path[ 0 ];
	}
	else
	{
		safe_full_path[ full_path_index++ ] = current_directory[ 0 ];
	}
	safe_full_path[ full_path_index++ ] = (wchar_t) ':';
	safe_full_path[ full_path_index++ ] = (wchar_t) LIBCPATH_SEPARATOR;
#else
	if( path_type == LIBCPATH_TYPE_ABSOLUTE )
	{
		safe_full_path[ full_path_index++ ] = (wchar_t) LIBCPATH_SEPARATOR;
	}
#endif
	/* If the path is relative
	 * add the current working directory elements
	 */
	if( current_directory_split_string != NULL )
	{
		for( current_directory_segment_index = 0;
		     current_directory_segment_index < current_directory_number_of_segments;
		     current_directory_segment_index++ )
		{
			if( libcsplit_wide_split_string_get_segment_by_index(
			     current_directory_split_string,
			     current_directory_segment_index,
			     &current_directory_string_segment,
			     &current_directory_string_segment_size,
			     error ) != 1 )
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_RUNTIME,
				 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
				 "%s: unable to retrieve current working directory string segment: %d.",
				 function,
				 current_directory_segment_index );

				goto on_error;
			}
			if( current_directory_string_segment_size != 0 )
			{
				if( current_directory_string_segment == NULL )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
					 "%s: missing current working directory string segment: %d.",
					 function,
					 current_directory_segment_index );

					goto on_error;
				}
				/* Note that here we should have room for the segment string and
				 * one additional character
				 */
				if( current_directory_string_segment_size > ( safe_full_path_size - full_path_index ) )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_VALUE_OUT_OF_BOUNDS,
					 "%s: invalid current working directory string segment: %d size value out of bounds.",
					 function,
					 current_directory_segment_index );

					goto on_error;
				}
				path_string_segment_length = current_directory_string_segment_size - 1;

				if( wide_string_copy(
				     &( safe_full_path[ full_path_index ] ),
				     current_directory_string_segment,
				     path_string_segment_length ) == NULL )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
					 "%s: unable to set current working directory split value: %d in full path.",
					 function,
					 current_directory_segment_index );

					goto on_error;
				}
				full_path_index += path_string_segment_length;

				safe_full_path[ full_path_index++ ] = (wchar_t) LIBCPATH_SEPARATOR;
			}
		}
	}
	if( path_split_string != NULL )
	{
		for( path_segment_index = 0;
		     path_segment_index < path_number_of_segments;
		     path_segment_index++ )
		{
			if( libcsplit_wide_split_string_get_segment_by_index(
			     path_split_string,
			     path_segment_index,
			     &path_string_segment,
			     &path_string_segment_size,
			     error ) != 1 )
			{
				libcerror_error_set(
				 error,
				 LIBCERROR_ERROR_DOMAIN_RUNTIME,
				 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
				 "%s: unable to retrieve path string segment: %d.",
				 function,
				 path_segment_index );

				goto on_error;
			}
			if( path_string_segment_size != 0 )
			{
				if( path_string_segment == NULL )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_VALUE_MISSING,
					 "%s: missing path string segment: %d.",
					 function,
					 path_segment_index );

					goto on_error;
				}
				/* Note that here we should have room for the segment string and
				 * one additional character
				 */
				if( path_string_segment_size > ( safe_full_path_size - full_path_index ) )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_VALUE_OUT_OF_BOUNDS,
					 "%s: invalid current path string segment: %d size value out of bounds.",
					 function,
					 path_segment_index );

					goto on_error;
				}
				path_string_segment_length = path_string_segment_size - 1;

				if( wide_string_copy(
				     &( safe_full_path[ full_path_index ] ),
				     path_string_segment,
				     path_string_segment_length ) == NULL )
				{
					libcerror_error_set(
					 error,
					 LIBCERROR_ERROR_DOMAIN_RUNTIME,
					 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
					 "%s: unable to set path split value: %d in full path.",
					 function,
					 path_segment_index );

					goto on_error;
				}
				full_path_index += path_string_segment_length;

				safe_full_path[ full_path_index++ ] = (wchar_t) LIBCPATH_SEPARATOR;
			}
		}
	}
	safe_full_path[ full_path_index - 1 ] = 0;

	if( path_split_string != NULL )
	{
		if( libcsplit_wide_split_string_free(
		     &path_split_string,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_FINALIZE_FAILED,
			 "%s: unable to free path split string.",
			 function );

			goto on_error;
		}
	}
	if( current_directory_split_string != NULL )
	{
		if( libcsplit_wide_split_string_free(
		     &current_directory_split_string,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_FINALIZE_FAILED,
			 "%s: unable to free current working directory split string.",
			 function );

			goto on_error;
		}
	}
	if( current_directory != NULL )
	{
		memory_free(
		 current_directory );
	}
	*full_path      = safe_full_path;
	*full_path_size = safe_full_path_size;

	return( 1 );

on_error:
	if( safe_full_path != NULL )
	{
		memory_free(
		 safe_full_path );
	}
	if( path_split_string != NULL )
	{
		libcsplit_wide_split_string_free(
		 &path_split_string,
		 NULL );
	}
	if( current_directory_split_string != NULL )
	{
		libcsplit_wide_split_string_free(
		 &current_directory_split_string,
		 NULL );
	}
	if( current_directory != NULL )
	{
		memory_free(
		 current_directory );
	}
	return( -1 );
}

#endif /* defined( WINAPI ) */

/* Retrieves the size of a sanitized version of the path character
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_get_sanitized_character_size_wide(
     wchar_t character,
     size_t *sanitized_character_size,
     libcerror_error_t **error )
{
	static char *function = "libcpath_path_get_sanitized_character_size_wide";

	if( sanitized_character_size == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid sanitized character size.",
		 function );

		return( -1 );
	}
	if( ( character >= 0x00 )
	 && ( character <= 0x1f ) )
	{
		*sanitized_character_size = 4;
	}
	else if( character == (wchar_t) LIBCPATH_ESCAPE_CHARACTER )
	{
		*sanitized_character_size = 2;
	}
#if defined( WINAPI )
	else if( character == (wchar_t) '/' )
	{
		*sanitized_character_size = 4;
	}
#endif
	else if( ( character == (wchar_t) '!' )
	      || ( character == (wchar_t) '$' )
	      || ( character == (wchar_t) '%' )
	      || ( character == (wchar_t) '&' )
	      || ( character == (wchar_t) '*' )
	      || ( character == (wchar_t) '+' )
	      || ( character == (wchar_t) ':' )
	      || ( character == (wchar_t) ';' )
	      || ( character == (wchar_t) '<' )
	      || ( character == (wchar_t) '>' )
	      || ( character == (wchar_t) '?' )
	      || ( character == (wchar_t) '|' )
	      || ( character == 0x7f ) )
	{
		*sanitized_character_size = 4;
	}
	else
	{
		*sanitized_character_size = 1;
	}
	return( 1 );
}

/* Retrieves a sanitized version of the path character
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_get_sanitized_character_wide(
     wchar_t character,
     size_t sanitized_character_size,
     wchar_t *sanitized_path,
     size_t sanitized_path_size,
     size_t *sanitized_path_index,
     libcerror_error_t **error )
{
	static char *function            = "libcpath_path_get_sanitized_character_wide";
	size_t safe_sanitized_path_index = 0;
	wchar_t lower_nibble             = 0;
	wchar_t upper_nibble             = 0;

	if( ( sanitized_character_size != 1 )
	 && ( sanitized_character_size != 2 )
	 && ( sanitized_character_size != 4 ) )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid sanitized character size value out of bounds.",
		 function );

		return( -1 );
	}
	if( sanitized_path == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid sanitized path.",
		 function );

		return( -1 );
	}
	if( sanitized_path_size > (size_t) SSIZE_MAX )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_EXCEEDS_MAXIMUM,
		 "%s: invalid sanitized path size value exceeds maximum.",
		 function );

		return( -1 );
	}
	if( sanitized_path_index == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid sanitized path index.",
		 function );

		return( -1 );
	}
	safe_sanitized_path_index = *sanitized_path_index;

	if( safe_sanitized_path_index > sanitized_path_size )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid sanitized path index value out of bounds.",
		 function );

		return( -1 );
	}
	if( ( sanitized_character_size > sanitized_path_size )
	 || ( safe_sanitized_path_index > ( sanitized_path_size - sanitized_character_size ) ) )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_TOO_SMALL,
		 "%s: invalid sanitized path size value too small.",
		 function );

		return( -1 );
	}
	if( sanitized_character_size == 1 )
	{
		sanitized_path[ safe_sanitized_path_index++ ] = character;
	}
	else if( sanitized_character_size == 2 )
	{
		sanitized_path[ safe_sanitized_path_index++ ] = (wchar_t) LIBCPATH_ESCAPE_CHARACTER;
		sanitized_path[ safe_sanitized_path_index++ ] = (wchar_t) LIBCPATH_ESCAPE_CHARACTER;
	}
	else if( sanitized_character_size == 4 )
	{
		lower_nibble = character & 0x0f;
		upper_nibble = ( character >> 4 ) & 0x0f;

		if( lower_nibble > 10 )
		{
			lower_nibble += (wchar_t) 'a' - 10;
		}
		else
		{
			lower_nibble += '0';
		}
		if( upper_nibble > 10 )
		{
			upper_nibble += (wchar_t) 'a' - 10;
		}
		else
		{
			upper_nibble += '0';
		}
		sanitized_path[ safe_sanitized_path_index++ ] = (wchar_t) LIBCPATH_ESCAPE_CHARACTER;
		sanitized_path[ safe_sanitized_path_index++ ] = (wchar_t) 'x';
		sanitized_path[ safe_sanitized_path_index++ ] = upper_nibble;
		sanitized_path[ safe_sanitized_path_index++ ] = lower_nibble;
	}
	*sanitized_path_index = safe_sanitized_path_index;

	return( 1 );
}

/* Retrieves a sanitized version of the filename
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_get_sanitized_filename_wide(
     const wchar_t *filename,
     size_t filename_length,
     wchar_t **sanitized_filename,
     size_t *sanitized_filename_size,
     libcerror_error_t **error )
{
	static char *function               = "libcpath_path_get_sanitized_filename_wide";
	wchar_t *safe_sanitized_filename    = NULL;
	size_t filename_index               = 0;
	size_t sanitized_character_size     = 0;
	size_t safe_sanitized_filename_size = 0;
	size_t sanitized_filename_index     = 0;

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
	if( ( filename_length == 0 )
	 || ( filename_length > (size_t) ( SSIZE_MAX - 1 ) ) )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid filename length value out of bounds.",
		 function );

		return( -1 );
	}
	if( sanitized_filename == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid sanitized filename.",
		 function );

		return( -1 );
	}
	if( *sanitized_filename != NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_ALREADY_SET,
		 "%s: invalid sanitized filename value already set.",
		 function );

		return( -1 );
	}
	if( sanitized_filename_size == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid sanitized filename size.",
		 function );

		return( -1 );
	}
	safe_sanitized_filename_size = 1;

	for( filename_index = 0;
	     filename_index < filename_length;
	     filename_index++ )
	{
		if( filename[ filename_index ] == LIBCPATH_SEPARATOR )
		{
			sanitized_character_size = 4;
		}
		else if( libcpath_path_get_sanitized_character_size_wide(
		          filename[ filename_index ],
		          &sanitized_character_size,
		          error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
			 "%s: unable to determine sanitize character size.",
			 function );

			goto on_error;
		}
		safe_sanitized_filename_size += sanitized_character_size;
	}
	if( safe_sanitized_filename_size > (size_t) SSIZE_MAX )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_EXCEEDS_MAXIMUM,
		 "%s: invalid sanitized filename size value exceeds maximum.",
		 function );

		goto on_error;
	}
	safe_sanitized_filename = wide_string_allocate(
	                           safe_sanitized_filename_size );

	if( safe_sanitized_filename == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_INSUFFICIENT,
		 "%s: unable to create sanitized filename.",
		 function );

		goto on_error;
	}
	for( filename_index = 0;
	     filename_index < filename_length;
	     filename_index++ )
	{
		if( filename[ filename_index ] == LIBCPATH_SEPARATOR )
		{
			sanitized_character_size = 4;
		}
		else if( libcpath_path_get_sanitized_character_size_wide(
		          filename[ filename_index ],
		          &sanitized_character_size,
		          error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
			 "%s: unable to determine sanitize character size.",
			 function );

			goto on_error;
		}
		if( libcpath_path_get_sanitized_character_wide(
		     filename[ filename_index ],
		     sanitized_character_size,
		     safe_sanitized_filename,
		     safe_sanitized_filename_size,
		     &sanitized_filename_index,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
			 "%s: unable to determine sanitize character size.",
			 function );

			goto on_error;
		}
	}
	safe_sanitized_filename[ sanitized_filename_index ] = 0;

	*sanitized_filename      = safe_sanitized_filename;
	*sanitized_filename_size = safe_sanitized_filename_size;

	return( 1 );

on_error:
	if( safe_sanitized_filename != NULL )
	{
		memory_free(
		 safe_sanitized_filename );
	}
	return( -1 );
}

/* Retrieves a sanitized version of the path
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_get_sanitized_path_wide(
     const wchar_t *path,
     size_t path_length,
     wchar_t **sanitized_path,
     size_t *sanitized_path_size,
     libcerror_error_t **error )
{
	static char *function                    = "libcpath_path_get_sanitized_path_wide";
	wchar_t *safe_sanitized_path             = NULL;
	size_t path_index                        = 0;
	size_t safe_sanitized_path_size          = 0;
	size_t sanitized_character_size          = 0;
	size_t sanitized_path_index              = 0;

#if defined( WINAPI )
	size_t last_path_segment_seperator_index = 0;
#endif

	if( path == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid path.",
		 function );

		return( -1 );
	}
	if( ( path_length == 0 )
	 || ( path_length > (size_t) ( SSIZE_MAX - 1 ) ) )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid path length value out of bounds.",
		 function );

		return( -1 );
	}
	if( sanitized_path == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid sanitized path.",
		 function );

		return( -1 );
	}
	if( *sanitized_path != NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_ALREADY_SET,
		 "%s: invalid sanitized path value already set.",
		 function );

		return( -1 );
	}
	if( sanitized_path_size == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid sanitized path size.",
		 function );

		return( -1 );
	}
	safe_sanitized_path_size = 1;

	for( path_index = 0;
	     path_index < path_length;
	     path_index++ )
	{
		if( libcpath_path_get_sanitized_character_size_wide(
		     path[ path_index ],
		     &sanitized_character_size,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
			 "%s: unable to determine sanitize character size.",
			 function );

			goto on_error;
		}
		safe_sanitized_path_size += sanitized_character_size;

#if defined( WINAPI )
		if( path[ path_index ] == LIBCPATH_SEPARATOR )
		{
			last_path_segment_seperator_index = path_index;
		}
#endif
	}
	if( safe_sanitized_path_size > (size_t) SSIZE_MAX )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_EXCEEDS_MAXIMUM,
		 "%s: invalid sanitized path size value exceeds maximum.",
		 function );

		goto on_error;
	}
#if defined( WINAPI )
	if( last_path_segment_seperator_index > 32767 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid last path segment separator value out of bounds.",
		 function );

		goto on_error;
	}
	if( safe_sanitized_path_size > 32767 )
	{
		safe_sanitized_path_size = 32767;
	}
#endif
	safe_sanitized_path = wide_string_allocate(
	                       safe_sanitized_path_size );

	if( safe_sanitized_path == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_INSUFFICIENT,
		 "%s: unable to create sanitized path.",
		 function );

		goto on_error;
	}
	for( path_index = 0;
	     path_index < path_length;
	     path_index++ )
	{
		if( libcpath_path_get_sanitized_character_size_wide(
		     path[ path_index ],
		     &sanitized_character_size,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
			 "%s: unable to determine sanitize character size.",
			 function );

			goto on_error;
		}
		if( libcpath_path_get_sanitized_character_wide(
		     path[ path_index ],
		     sanitized_character_size,
		     safe_sanitized_path,
		     safe_sanitized_path_size,
		     &sanitized_path_index,
		     error ) != 1 )
		{
			libcerror_error_set(
			 error,
			 LIBCERROR_ERROR_DOMAIN_RUNTIME,
			 LIBCERROR_RUNTIME_ERROR_GET_FAILED,
			 "%s: unable to determine sanitize character size.",
			 function );

			goto on_error;
		}
	}
	if( sanitized_path_index >= safe_sanitized_path_size )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_OUT_OF_BOUNDS,
		 "%s: invalid sanitized path index value out of bounds.",
		 function );

		goto on_error;
	}
	safe_sanitized_path[ sanitized_path_index ] = 0;

	*sanitized_path      = safe_sanitized_path;
	*sanitized_path_size = safe_sanitized_path_size;

	return( 1 );

on_error:
	if( safe_sanitized_path != NULL )
	{
		memory_free(
		 safe_sanitized_path );
	}
	return( -1 );
}

/* Combines the directory name and filename into a path
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_join_wide(
     wchar_t **path,
     size_t *path_size,
     const wchar_t *directory_name,
     size_t directory_name_length,
     const wchar_t *filename,
     size_t filename_length,
     libcerror_error_t **error )
{
	static char *function = "libcpath_path_join_wide";
	size_t filename_index = 0;
	size_t path_index     = 0;

	if( path == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid path.",
		 function );

		return( -1 );
	}
	if( *path != NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_ALREADY_SET,
		 "%s: invalid path value already set.",
		 function );

		return( -1 );
	}
	if( path_size == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid path size.",
		 function );

		return( -1 );
	}
	if( directory_name == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid directory name.",
		 function );

		return( -1 );
	}
	if( directory_name_length > (size_t) SSIZE_MAX )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_EXCEEDS_MAXIMUM,
		 "%s: invalid directory name length value exceeds maximum.",
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
	if( filename_length > (size_t) SSIZE_MAX )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_VALUE_EXCEEDS_MAXIMUM,
		 "%s: invalid filename length value exceeds maximum.",
		 function );

		return( -1 );
	}
/* TODO strip other patterns like /./ */
	while( directory_name_length > 0 )
	{
		if( directory_name[ directory_name_length - 1 ] != (wchar_t) LIBCPATH_SEPARATOR )
		{
			break;
		}
		directory_name_length--;
	}
	while( filename_length > 0 )
	{
		if( filename[ filename_index ] != (wchar_t) LIBCPATH_SEPARATOR )
		{
			break;
		}
		filename_index++;
		filename_length--;
	}
	*path_size = directory_name_length + filename_length + 2;

	*path = wide_string_allocate(
	         *path_size );

	if( *path == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_INSUFFICIENT,
		 "%s: unable to create path.",
		 function );

		goto on_error;
	}
	if( wide_string_copy(
	     *path,
	     directory_name,
	     directory_name_length ) == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_COPY_FAILED,
		 "%s: unable to copy directory name to path.",
		 function );

		goto on_error;
	}
	path_index = directory_name_length;

	( *path )[ path_index++ ] = (wchar_t) LIBCPATH_SEPARATOR;

	if( wide_string_copy(
	     &( ( *path )[ path_index ] ),
	     &( filename[ filename_index ] ),
	     filename_length ) == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_COPY_FAILED,
		 "%s: unable to copy filename to path.",
		 function );

		goto on_error;
	}
	path_index += filename_length;

	( *path )[ path_index ] = 0;

	return( 1 );

on_error:
	if( *path != NULL )
	{
		memory_free(
		 *path );

		*path = NULL;
	}
	*path_size = 0;

	return( -1 );
}

#if defined( WINAPI ) && ( WINVER <= 0x0500 )

/* Cross Windows safe version of CreateDirectoryW
 * Returns TRUE if successful or FALSE on error
 */
BOOL libcpath_CreateDirectoryW(
      LPCWSTR path,
      SECURITY_ATTRIBUTES *security_attributes )
{
	FARPROC function       = NULL;
	HMODULE library_handle = NULL;
	BOOL result            = FALSE;

	if( path == NULL )
	{
		return( 0 );
	}
	library_handle = LoadLibrary(
	                  _SYSTEM_STRING( "kernel32.dll" ) );

	if( library_handle == NULL )
	{
		return( 0 );
	}
	function = GetProcAddress(
		    library_handle,
		    (LPCSTR) "CreateDirectoryW" );

	if( function != NULL )
	{
		result = function(
			  path,
			  security_attributes );
	}
	/* This call should be after using the function
	 * in most cases kernel32.dll will still be available after free
	 */
	if( FreeLibrary(
	     library_handle ) != TRUE )
	{
		libcpath_CloseHandle(
		 library_handle );

		return( 0 );
	}
	return( result );
}

#endif /* defined( WINAPI ) && ( WINVER <= 0x0500 ) */

#if defined( WINAPI )

/* Makes the directory
 * This function uses the WINAPI function for Windows XP (0x0501) or later
 * or tries to dynamically call the function for Windows 2000 (0x0500) or earlier
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_make_directory_wide(
     const wchar_t *directory_name,
     libcerror_error_t **error )
{
	static char *function = "libcpath_path_make_directory_wide";
	DWORD error_code      = 0;

	if( directory_name == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid directory name.",
		 function );

		return( -1 );
	}
#if WINVER <= 0x0500
	if( libcpath_CreateDirectoryW(
	     directory_name,
	     NULL ) == 0 )
#else
	if( CreateDirectoryW(
	     directory_name,
	     NULL ) == 0 )
#endif
	{
		error_code = GetLastError();

		libcerror_system_set_error(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
		 error_code,
		 "%s: unable to make directory.",
		 function );

		return( -1 );
	}
	return( 1 );
}

#elif defined( HAVE_MKDIR )

/* Makes the directory
 * This function uses the POSIX mkdir function or equivalent
 * Returns 1 if successful or -1 on error
 */
int libcpath_path_make_directory_wide(
     const wchar_t *directory_name,
     libcerror_error_t **error )
{
	static char *function             = "libcpath_path_make_directory_wide";
	char *narrow_directory_name       = 0;
	size_t directory_name_length      = 0;
	size_t narrow_directory_name_size = 0;

	if( directory_name == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_ARGUMENTS,
		 LIBCERROR_ARGUMENT_ERROR_INVALID_VALUE,
		 "%s: invalid directory name.",
		 function );

		return( -1 );
	}
	directory_name_length = wide_string_length(
	                         directory_name );

	if( libcpath_system_string_size_from_wide_string(
	     directory_name,
	     directory_name_length + 1,
	     &narrow_directory_name_size,
	     error ) != 1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_CONVERSION,
		 LIBCERROR_CONVERSION_ERROR_GENERIC,
		 "%s: unable to determine narrow directory name size.",
		 function );

		goto on_error;
	}
	if( ( narrow_directory_name_size > (size_t) SSIZE_MAX )
	 || ( ( sizeof( char ) * narrow_directory_name_size )  > (size_t) SSIZE_MAX ) )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_VALUE_EXCEEDS_MAXIMUM,
		 "%s: invalid narrow directory name size value exceeds maximum.",
		 function );

		goto on_error;
	}
	narrow_directory_name = narrow_string_allocate(
	                         narrow_directory_name_size );

	if( narrow_directory_name == NULL )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_MEMORY,
		 LIBCERROR_MEMORY_ERROR_INSUFFICIENT,
		 "%s: unable to create narrow directory name.",
		 function );

		goto on_error;
	}
	if( libcpath_system_string_copy_from_wide_string(
	     narrow_directory_name,
	     narrow_directory_name_size,
	     directory_name,
	     directory_name_length + 1,
	     error ) != 1 )
	{
		libcerror_error_set(
		 error,
		 LIBCERROR_ERROR_DOMAIN_CONVERSION,
		 LIBCERROR_CONVERSION_ERROR_GENERIC,
		 "%s: unable to set name.",
		 function );

		goto on_error;
	}
#if defined( __MINGW32__ ) || defined( _MSC_VER )
	if( mkdir(
	     narrow_directory_name ) != 0 )
#else
	if( mkdir(
	     narrow_directory_name,
	     0755 ) != 0 )
#endif
	{
		libcerror_system_set_error(
		 error,
		 LIBCERROR_ERROR_DOMAIN_RUNTIME,
		 LIBCERROR_RUNTIME_ERROR_SET_FAILED,
		 errno,
		 "%s: unable to make directory.",
		 function );

		goto on_error;
	}
	memory_free(
	 narrow_directory_name );

	return( 1 );

on_error:
	if( narrow_directory_name != NULL )
	{
		memory_free(
		 narrow_directory_name );
	}
	return( -1 );
}

#else
#error Missing make directory function
#endif

#endif /* defined( HAVE_WIDE_CHARACTER_TYPE ) */

