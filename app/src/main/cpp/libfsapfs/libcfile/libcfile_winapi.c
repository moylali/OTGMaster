/*
 * WINAPI fallback functions
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
#include <narrow_string.h>
#include <system_string.h>
#include <types.h>
#include <wide_string.h>

#if defined( WINAPI )
#include <io.h>
#endif

#if defined( WINAPI ) && !defined( __CYGWIN__ )
#include <share.h>
#endif

#include "libcfile_winapi.h"

#if defined( WINAPI ) && ( WINVER <= 0x0500 )

/* Cross Windows safe version of CloseHandle
 * Returns TRUE if successful or FALSE on error
 */
BOOL libcfile_CloseHandle(
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

/* Cross Windows safe version of CreateFileA
 * Returns a handle if successful or INVALID_HANDLE_VALUE on error
 */
HANDLE libcfile_CreateFileA(
        LPCSTR filename,
        DWORD desired_access,
        DWORD share_mode,
        SECURITY_ATTRIBUTES *security_attributes,
        DWORD creation_disposition,
        DWORD flags_and_attributes,
        HANDLE template_file )
{
	FARPROC function       = NULL;
	HANDLE result          = INVALID_HANDLE_VALUE;
	HMODULE library_handle = NULL;

	if( filename == NULL )
	{
		return( INVALID_HANDLE_VALUE );
	}
	library_handle = LoadLibrary(
	                  _SYSTEM_STRING( "kernel32.dll" ) );

	if( library_handle == NULL )
	{
		return( INVALID_HANDLE_VALUE );
	}
	function = GetProcAddress(
		    library_handle,
		    (LPCSTR) "CreateFileA" );

	if( function != NULL )
	{
		result = (HANDLE) function(
		                   filename,
		                   desired_access,
		                   share_mode,
		                   security_attributes,
		                   creation_disposition,
		                   flags_and_attributes,
		                   template_file );
	}
	/* This call should be after using the function
	 * in most cases kernel32.dll will still be available after free
	 */
	if( FreeLibrary(
	     library_handle ) != TRUE )
	{
		libcfile_CloseHandle(
		 result );

		return( INVALID_HANDLE_VALUE );
	}
	return( result );
}

#if defined( HAVE_WIDE_CHARACTER_TYPE )

/* Cross Windows safe version of CreateFileW
 * Returns a handle if successful or INVALID_HANDLE_VALUE on error
 */
HANDLE libcfile_CreateFileW(
        LPCWSTR filename,
        DWORD desired_access,
        DWORD share_mode,
        SECURITY_ATTRIBUTES *security_attributes,
        DWORD creation_disposition,
        DWORD flags_and_attributes,
        HANDLE template_file )
{
	FARPROC function       = NULL;
	HANDLE result          = INVALID_HANDLE_VALUE;
	HMODULE library_handle = NULL;

	if( filename == NULL )
	{
		return( INVALID_HANDLE_VALUE );
	}
	library_handle = LoadLibrary(
	                  _SYSTEM_STRING( "kernel32.dll" ) );

	if( library_handle == NULL )
	{
		return( INVALID_HANDLE_VALUE );
	}
	function = GetProcAddress(
		    library_handle,
		    (LPCSTR) "CreateFileW" );

	if( function != NULL )
	{
		result = (HANDLE) function(
		                   filename,
		                   desired_access,
		                   share_mode,
		                   security_attributes,
		                   creation_disposition,
		                   flags_and_attributes,
		                   template_file );
	}
	/* This call should be after using the function
	 * in most cases kernel32.dll will still be available after free
	 */
	if( FreeLibrary(
	     library_handle ) != TRUE )
	{
		libcfile_CloseHandle(
		 result );

		return( INVALID_HANDLE_VALUE );
	}
	return( result );
}

#endif /* defined( HAVE_WIDE_CHARACTER_TYPE ) */

/* Cross Windows safe version of GetOverlappedResult
 * Returns TRUE if successful or FALSE on error
 */
BOOL libcfile_GetOverlappedResult(
      HANDLE file_handle,
      OVERLAPPED *overlapped,
      DWORD *read_count,
      BOOL wait_io_complete )
{
	FARPROC function       = NULL;
	HMODULE library_handle = NULL;
	BOOL result            = FALSE;

	if( file_handle == NULL )
	{
		return( FALSE );
	}
	if( overlapped == NULL )
	{
		return( FALSE );
	}
	if( read_count == NULL )
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
		    (LPCSTR) "GetOverlappedResult" );

	if( function != NULL )
	{
		result = function(
			  file_handle,
			  overlapped,
			  read_count,
			  wait_io_complete );
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

/* Cross Windows safe version of ReadFile
 * Returns TRUE if successful or FALSE on error
 */
BOOL libcfile_ReadFile(
      HANDLE file_handle,
      VOID *buffer,
      DWORD read_size,
      DWORD *read_count,
      OVERLAPPED *overlapped )
{
	FARPROC function       = NULL;
	HMODULE library_handle = NULL;
	BOOL result            = FALSE;

	if( file_handle == NULL )
	{
		return( FALSE );
	}
	if( buffer == NULL )
	{
		return( FALSE );
	}
	if( read_count == NULL )
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
		    (LPCSTR) "ReadFile" );

	if( function != NULL )
	{
		result = function(
			  file_handle,
			  buffer,
			  read_size,
			  read_count,
			  overlapped );
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

/* Cross Windows safe version of WriteFile
 * Returns TRUE if successful or FALSE on error
 */
BOOL libcfile_WriteFile(
      HANDLE file_handle,
      VOID *buffer,
      DWORD write_size,
      DWORD *write_count,
      OVERLAPPED *overlapped )
{
	FARPROC function       = NULL;
	HMODULE library_handle = NULL;
	BOOL result            = FALSE;

	if( file_handle == NULL )
	{
		return( FALSE );
	}
	if( buffer == NULL )
	{
		return( FALSE );
	}
	if( write_count == NULL )
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
		    (LPCSTR) "WriteFile" );

	if( function != NULL )
	{
		result = function(
			  file_handle,
			  buffer,
			  write_size,
			  write_count,
			  overlapped );
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

#if !defined( INVALID_SET_FILE_POINTER )
#define INVALID_SET_FILE_POINTER	((LONG) -1)
#endif

/* Cross Windows safe version of SetFilePointerEx
 * Returns TRUE if successful or FALSE on error
 */
BOOL libcfile_SetFilePointerEx(
      HANDLE file_handle,
      LARGE_INTEGER distance_to_move_large_integer,
      LARGE_INTEGER *new_file_pointer_large_integer,
      DWORD move_method )
{
	FARPROC function                 = NULL;
	HMODULE library_handle           = NULL;
	LONG distance_to_move_lower_long = 0;
	LONG distance_to_move_upper_long = 0;
	DWORD error_number               = 0;
	BOOL result                      = FALSE;

	if( file_handle == NULL )
	{
		return( FALSE );
	}
	if( new_file_pointer_large_integer == NULL )
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
		    (LPCSTR) "SetFilePointerEx" );

	if( function != NULL )
	{
		result = function(
			  file_handle,
			  distance_to_move_large_integer,
			  new_file_pointer_large_integer,
			  move_method );
	}
	else
	{
		function = GetProcAddress(
			    library_handle,
			    (LPCSTR) "SetFilePointer" );

		if( function != NULL )
		{
#if defined( __BORLANDC__ ) && __BORLANDC__ <= 0x520
			distance_to_move_lower_long = distance_to_move_large_integer.QuadPart & 0xffffffffUL;
			distance_to_move_upper_long = distance_to_move_large_integer.QuadPart >> 32;
#else
			distance_to_move_lower_long = distance_to_move_large_integer.LowPart;
			distance_to_move_upper_long = distance_to_move_large_integer.HighPart;
#endif

			distance_to_move_lower_long = function(
						       file_handle,
						       distance_to_move_lower_long,
						       &distance_to_move_upper_long,
						       move_method );

			error_number = GetLastError();

			if( ( distance_to_move_lower_long != (LONG) INVALID_SET_FILE_POINTER )
			 || ( error_number == NO_ERROR ) )
			{
#if defined( __BORLANDC__ ) && __BORLANDC__ <= 0x520
				new_file_pointer_large_integer->QuadPart   = distance_to_move_upper_long;
				new_file_pointer_large_integer->QuadPart <<= 32;
				new_file_pointer_large_integer->QuadPart  += distance_to_move_lower_long;
#else
				new_file_pointer_large_integer->HighPart = distance_to_move_upper_long;
				new_file_pointer_large_integer->LowPart  = distance_to_move_lower_long;
#endif

				result = TRUE;
			}
		}
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

/* Cross Windows safe version of SetEndOfFile
 * Returns TRUE if successful or FALSE on error
 */
BOOL libcfile_SetEndOfFile(
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
		    (LPCSTR) "SetEndOfFile" );

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

/* Cross Windows safe version of GetFileSizeEx
 * Returns TRUE if successful or FALSE on error
 */
BOOL libcfile_GetFileSizeEx(
      HANDLE file_handle,
      LARGE_INTEGER *file_size_large_integer )
{
	FARPROC function            = NULL;
	HMODULE library_handle      = NULL;
	DWORD error_number          = 0;
	DWORD file_size_upper_dword = 0;
	DWORD file_size_lower_dword = 0;
	BOOL result                 = FALSE;

	if( file_handle == NULL )
	{
		return( FALSE );
	}
	if( file_size_large_integer == NULL )
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
		    (LPCSTR) "GetFileSizeEx" );

	if( function != NULL )
	{
		result = function(
			  file_handle,
			  file_size_large_integer );
	}
	else
	{
		function = GetProcAddress(
			    library_handle,
			    (LPCSTR) "GetFileSize" );

		if( function != NULL )
		{
			file_size_lower_dword = function(
			                         file_handle,
			                         &file_size_upper_dword );

			error_number = GetLastError();

			if( ( file_size_lower_dword != INVALID_FILE_SIZE )
			 || ( error_number == NO_ERROR ) )
			{
#if defined( __BORLANDC__ ) && __BORLANDC__ <= 0x520
				file_size_large_integer->QuadPart   = file_size_upper_dword;
				file_size_large_integer->QuadPart <<= 32;
				file_size_large_integer->QuadPart  += file_size_lower_dword;
#else
				file_size_large_integer->HighPart = file_size_upper_dword;
				file_size_large_integer->LowPart  = file_size_lower_dword;
#endif

				result = TRUE;
			}
		}
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

/* Cross Windows safe version of GetFileType
 * Returns the file type if successful or FILE_TYPE_UNKNOWN on error
 */
DWORD libcfile_GetFileType(
       HANDLE file_handle )
{
	FARPROC function       = NULL;
	HMODULE library_handle = NULL;
	DWORD result           = FILE_TYPE_UNKNOWN;

	if( file_handle == NULL )
	{
		return( FILE_TYPE_UNKNOWN );
	}
	library_handle = LoadLibrary(
	                  _SYSTEM_STRING( "kernel32.dll" ) );

	if( library_handle == NULL )
	{
		return( FILE_TYPE_UNKNOWN );
	}
	function = GetProcAddress(
		    library_handle,
		    (LPCSTR) "GetFileType" );

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
		result = FILE_TYPE_UNKNOWN;
	}
	return( result );
}

#endif /* defined( WINAPI ) && ( WINVER <= 0x0500 ) */

#if defined( WINAPI ) && ( WINVER <= 0x0500 )

/* Cross Windows safe version of GetFileAttributesA
 * Returns the file attributs if successful or INVALID_FILE_ATTRIBUTES on error
 */
DWORD libcfile_GetFileAttributesA(
       LPCSTR filename )
{
	FARPROC function       = NULL;
	HMODULE library_handle = NULL;
	DWORD result           = 0;

	if( filename == NULL )
	{
		return( INVALID_FILE_ATTRIBUTES );
	}
	library_handle = LoadLibrary(
	                  _SYSTEM_STRING( "kernel32.dll" ) );

	if( library_handle == NULL )
	{
		return( INVALID_FILE_ATTRIBUTES );
	}
	function = GetProcAddress(
		    library_handle,
		    (LPCSTR) "GetFileAttributesA" );

	if( function != NULL )
	{
		result = function(
			  filename );
	}
	/* This call should be after using the function
	 * in most cases kernel32.dll will still be available after free
	 */
	if( FreeLibrary(
	     library_handle ) != TRUE )
	{
		result = INVALID_FILE_ATTRIBUTES;
	}
	return( result );
}

#endif /* defined( WINAPI ) && ( WINVER <= 0x0500 ) */

#if defined( HAVE_WIDE_CHARACTER_TYPE )

#if defined( WINAPI ) && ( WINVER <= 0x0500 )

/* Cross Windows safe version of GetFileAttributesW
 * Returns the file attributs if successful or INVALID_FILE_ATTRIBUTES on error
 */
DWORD libcfile_GetFileAttributesW(
       LPCWSTR filename )
{
	FARPROC function       = NULL;
	HMODULE library_handle = NULL;
	DWORD result           = 0;

	if( filename == NULL )
	{
		return( INVALID_FILE_ATTRIBUTES );
	}
	library_handle = LoadLibrary(
	                  _SYSTEM_STRING( "kernel32.dll" ) );

	if( library_handle == NULL )
	{
		return( INVALID_FILE_ATTRIBUTES );
	}
	function = GetProcAddress(
		    library_handle,
		    (LPCSTR) "GetFileAttributesW" );

	if( function != NULL )
	{
		result = function(
			  filename );
	}
	/* This call should be after using the function
	 * in most cases kernel32.dll will still be available after free
	 */
	if( FreeLibrary(
	     library_handle ) != TRUE )
	{
		result = INVALID_FILE_ATTRIBUTES;
	}
	return( result );
}

#endif /* defined( WINAPI ) && ( WINVER <= 0x0500 ) */

#endif /* defined( HAVE_WIDE_CHARACTER_TYPE ) */

#if defined( WINAPI ) && ( WINVER <= 0x0500 )

/* Cross Windows safe version of DeleteFileA
 * Returns TRUE if successful or FALSE on error
 */
BOOL libcfile_DeleteFileA(
      LPCSTR filename )
{
	FARPROC function       = NULL;
	HMODULE library_handle = NULL;
	BOOL result            = FALSE;

	if( filename == NULL )
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
		    (LPCSTR) "DeleteFileA" );

	if( function != NULL )
	{
		result = function(
			  filename );
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

#if defined( HAVE_WIDE_CHARACTER_TYPE )

#if defined( WINAPI ) && ( WINVER <= 0x0500 )

/* Cross Windows safe version of DeleteFileW
 * Returns TRUE if successful or FALSE on error
 */
BOOL libcfile_DeleteFileW(
      LPCWSTR filename )
{
	FARPROC function       = NULL;
	HMODULE library_handle = NULL;
	BOOL result            = FALSE;

	if( filename == NULL )
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
		    (LPCSTR) "DeleteFileW" );

	if( function != NULL )
	{
		result = function(
			  filename );
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

#endif /* defined( HAVE_WIDE_CHARACTER_TYPE ) */

