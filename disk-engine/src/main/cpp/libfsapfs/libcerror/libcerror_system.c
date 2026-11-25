/*
 * System functions
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

#if defined( HAVE_STDLIB_H ) || defined( WINAPI )
#include <stdlib.h>
#endif

#if defined( HAVE_STRING_H ) || defined( WINAPI )
#include <string.h>
#endif

#if defined( HAVE_STDARG_H ) || defined( WINAPI )
#include <stdarg.h>
#elif defined( HAVE_VARARGS_H )
#include <varargs.h>
#else
#error Missing headers stdarg.h and varargs.h
#endif

#include "libcerror_definitions.h"
#include "libcerror_error.h"
#include "libcerror_system.h"
#include "libcerror_types.h"

#if defined( WINAPI )

/* The make language identifier macro for the WINAPI FormatMessage function
 */
#if !defined( MAKELANGID )
#define MAKELANGID( primary_language_identifier, sub_language_identifier ) \
	( ( ( (WORD) ( sub_language_identifier ) ) << 10 ) | (WORD) ( primary_language_identifier ) )
#endif

#if !defined( LANG_NEUTRAL )
#define LANG_NEUTRAL		0
#endif

#if !defined( SUBLANG_DEFAULT )
#define SUBLANG_DEFAULT		1
#endif

#endif /* defined( WINAPI ) */

#if defined( WINAPI ) && ( WINVER <= 0x0500 )

/* Cross Windows safe version of FormatMessageA
 * Returns the number of printed characters without the end-of-string character or 0 on error
 */
DWORD libcerror_FormatMessageA(
       DWORD flags,
       LPCVOID source,
       DWORD message_identifier,
       DWORD language_identifier,
       LPCSTR string,
       DWORD string_size,
       va_list *argument_list )
{
	FARPROC function       = NULL;
	HMODULE library_handle = NULL;
	DWORD print_count      = 0;

	if( string == NULL )
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
	            (LPCSTR) "FormatMessageA" );

	if( function != NULL )
	{
		print_count = function(
		               flags,
		               source,
		               message_identifier,
		               language_identifier,
		               string,
		               string_size,
		               argument_list );
	}
	/* This call should be after using the function
	 * in most cases kernel32.dll will still be available after free
	 */
	if( FreeLibrary(
	     library_handle ) != TRUE )
	{
		print_count = 0;
	}
	return( print_count );
}

/* Cross Windows safe version of FormatMessageW
 * Returns the number of printed characters without the end-of-string character or 0 on error
 */
DWORD libcerror_FormatMessageW(
       DWORD flags,
       LPCVOID source,
       DWORD message_identifier,
       DWORD language_identifier,
       LPWSTR string,
       DWORD string_size,
       va_list *argument_list )
{
	FARPROC function       = NULL;
	HMODULE library_handle = NULL;
	DWORD print_count      = 0;

	if( string == NULL )
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
	            (LPCSTR) "FormatMessageW" );

	if( function != NULL )
	{
		print_count = function(
		               flags,
		               source,
		               message_identifier,
		               language_identifier,
		               string,
		               string_size,
		               argument_list );
	}
	/* This call should be after using the function
	 * in most cases kernel32.dll will still be available after free
	 */
	if( FreeLibrary(
	     library_handle ) != TRUE )
	{
		print_count = 0;
	}
	return( print_count );
}

#endif /* defined( WINAPI ) && ( WINVER <= 0x0500 ) */

#if defined( WINAPI )

#if ( WINVER <= 0x0500 )
#if defined( HAVE_WIDE_SYSTEM_CHARACTER )
#define libcerror_system_FormatMessage libcerror_FormatMessageW
#else
#define libcerror_system_FormatMessage libcerror_FormatMessageA
#endif

#else
#if defined( HAVE_WIDE_SYSTEM_CHARACTER )
#define libcerror_system_FormatMessage FormatMessageW
#else
#define libcerror_system_FormatMessage FormatMessageA
#endif
#endif /* ( WINVER <= 0x0500 ) */

/* Retrieves a descriptive string of the error number
 * This function uses the WINAPI functions for Windows XP or later
 * Returns the string_length if successful or -1 on error
 */
int libcerror_system_copy_string_from_error_number(
     system_character_t *string,
     size_t string_size,
     uint32_t error_number )
{
	DWORD print_count = 0;

	if( string == NULL )
	{
		return( -1 );
	}
	if( string_size > (size_t) INT_MAX )
	{
		return( -1 );
	}
	print_count = libcerror_system_FormatMessage(
	               FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
	               NULL,
	               (DWORD) error_number,
	               MAKELANGID(
	                LANG_NEUTRAL,
	                SUBLANG_DEFAULT ),
	               string,
	               (DWORD) string_size,
	               NULL );

	if( print_count == 0 )
	{
		return( -1 );
	}
	return( (int) print_count );
}

#elif defined( HAVE_STRERROR_R )

#if defined( HAVE_WIDE_SYSTEM_CHARACTER )
#error Missing wide character strerror_r function
#endif

/* Retrieves a descriptive string of the error number
 * This function uses the POSIX strerror_r function or equivalent
 * Returns the string_length if successful or -1 on error
 */
int libcerror_system_copy_string_from_error_number(
     system_character_t *string,
     size_t string_size,
     uint32_t error_number )
{
	size_t string_length = 0;

	if( string == NULL )
	{
		return( -1 );
	}
	if( string_size > (size_t) INT_MAX )
	{
		return( -1 );
	}
#if defined( STRERROR_R_CHAR_P )
	if( strerror_r(
	     (int) error_number,
	     string,
	     string_size ) == NULL )
#else
	if( strerror_r(
	     (int) error_number,
	     string,
	     string_size ) != 0 )
#endif
	{
		return( -1 );
	}
	string[ string_size - 1 ] = (system_character_t) 0;

	string_length = system_string_length(
	                 string );

	return( (int) string_length );
}

#elif defined( HAVE_STRERROR )

#if defined( HAVE_WIDE_SYSTEM_CHARACTER )
#error Missing wide character strerror function
#endif

/* Retrieves a descriptive string of the error number
 * This function uses the POSIX strerror function or equivalent
 * Returns the string_length if successful or -1 on error
 */
int libcerror_system_copy_string_from_error_number(
     system_character_t *string,
     size_t string_size,
     uint32_t error_number )
{
#if defined( HAVE_WIDE_SYSTEM_CHARACTER )
	const wchar_t *static_error_string = NULL;
#else
	const char *static_error_string    = NULL;
#endif
	size_t static_error_string_length  = 0;

	if( string == NULL )
	{
		return( -1 );
	}
	if( string_size > (size_t) INT_MAX )
	{
		return( -1 );
	}
#if defined( HAVE_WIDE_SYSTEM_CHARACTER )
	static_error_string = _wcserror(
	                       (int) error_number );
#else
	static_error_string = strerror(
	                       (int) error_number );
#endif

	if( static_error_string == NULL )
	{
		return( -1 );
	}
	static_error_string_length = system_string_length(
	                              static_error_string );

	if( system_string_copy(
	     string,
	     static_error_string,
	     static_error_string_length ) == NULL )
	{
		return( -1 );
	}
	string[ static_error_string_length ] = 0;

	return( (int) static_error_string_length );
}

#else
#error Missing error to string system function
#endif

#if defined( HAVE_STDARG_H ) || defined( WINAPI )
#define VARARGS( function, error, error_domain, error_code, system_error_code, type, argument ) \
        function( error, error_domain, error_code, system_error_code, type argument, ... )
#define VASTART( argument_list, type, name ) \
        va_start( argument_list, name )
#define VAEND( argument_list ) \
        va_end( argument_list )

#elif defined( HAVE_VARARGS_H )
#define VARARGS( function, error, error_domain, error_code, system_error_code, type, argument ) \
        function( error, error_domain, error_code, system_error_code, va_alist ) va_dcl
#define VASTART( argument_list, type, name ) \
        { type name; va_start( argument_list ); name = va_arg( argument_list, type )
#define VAEND( argument_list ) \
        va_end( argument_list ); }

#endif

/* Sets an error and adds a system specific error string if possible
 * Creates the error if necessary
 * The error domain and code are set only the first time and the error message is appended for back tracing
 */
void VARARGS(
      libcerror_system_set_error,
      libcerror_error_t **error,
      int error_domain,
      int error_code,
      uint32_t system_error_code,
      const char *,
      format_string )
{
	va_list argument_list;

	libcerror_internal_error_t *internal_error = NULL;
	system_character_t *error_string           = NULL;
	system_character_t *system_format_string   = NULL;
	void *reallocation                         = NULL;
	size_t error_string_size                   = 0;
	size_t format_string_length                = 0;
	size_t message_size                        = 0;
	size_t next_message_size                   = LIBCERROR_MESSAGE_INCREMENT_SIZE;
	size_t string_index                        = 0;
	int message_index                          = 0;
	int print_count                            = 0;

	if( error == NULL )
	{
		return;
	}
	if( format_string == NULL )
	{
		return;
	}
	format_string_length = narrow_string_length(
	                        format_string );

#if defined( HAVE_WIDE_SYSTEM_CHARACTER )
	libcerror_error_get_system_format_string(
	 format_string,
	 format_string_length,
	 &system_format_string );

	if( system_format_string == NULL )
	{
		return;
	}
#else
	system_format_string = (system_character_t *) format_string;
#endif
	if( *error == NULL )
	{
		if( libcerror_error_initialize(
		     error,
		     error_domain,
		     error_code ) != 1 )
		{
			goto on_error;
		}
	}
	internal_error = (libcerror_internal_error_t *) *error;

	if( libcerror_error_resize(
	     internal_error ) != 1 )
	{
		goto on_error;
	}
	if( format_string_length > next_message_size )
	{
		next_message_size = ( ( format_string_length / LIBCERROR_MESSAGE_INCREMENT_SIZE ) + 1 )
		                  * LIBCERROR_MESSAGE_INCREMENT_SIZE;
	}
	do
	{
		if( next_message_size >= LIBCERROR_MESSAGE_MAXIMUM_SIZE )
		{
			next_message_size = LIBCERROR_MESSAGE_MAXIMUM_SIZE;
		}
		reallocation = memory_reallocate(
		                error_string,
		                sizeof( system_character_t ) * next_message_size );

		if( reallocation == NULL )
		{
			memory_free(
			 error_string );

			goto on_error;
		}
		error_string = (system_character_t *) reallocation;

		message_size = next_message_size;

		/* argument_list cannot be reused in successive calls to vsnprintf
		 */
		VASTART(
		 argument_list,
		 const char *,
		 format_string );

		print_count = system_string_vsnprintf(
		               error_string,
		               message_size,
		               system_format_string,
		               argument_list );

		VAEND(
		 argument_list );

		if( print_count <= -1 )
		{
			next_message_size += LIBCERROR_MESSAGE_INCREMENT_SIZE;
		}
		else if( ( (size_t) print_count >= message_size )
		      || ( error_string[ print_count ] != (system_character_t) 0 ) )
		{
			next_message_size = (size_t) ( print_count + 1 );
			print_count       = -1;
		}
		else
		{
			error_string_size = (size_t) print_count + 1;
		}
		if( message_size >= LIBCERROR_MESSAGE_MAXIMUM_SIZE )
		{
			break;
		}
	}
	while( print_count <= -1 );

#if defined( HAVE_WIDE_SYSTEM_CHARACTER )
	memory_free(
	 system_format_string );

	system_format_string = NULL;
#endif

	if( message_size < LIBCERROR_MESSAGE_MAXIMUM_SIZE )
	{
/* TODO move to separate helper function */
		if( error_string != NULL )
		{
			string_index = error_string_size - 1;

			if( error_string[ string_index - 1 ] == (system_character_t) '.' )
			{
				string_index -= 1;
			}
			error_string[ string_index ] = 0;
			error_string_size            = string_index + 1;

			reallocation = memory_reallocate(
			                error_string,
			                sizeof( system_character_t ) * ( message_size + 13 + 512 ) );

			if( reallocation != NULL )
			{
				error_string = (system_character_t *) reallocation;

				if( system_string_copy(
				     &( error_string[ string_index ] ),
				     _SYSTEM_STRING( " with error: " ),
				     13 ) != NULL )
				{
					error_string_size += 13;
					string_index      += 13;

					print_count = libcerror_system_copy_string_from_error_number(
						       &( error_string[ string_index ] ),
						       512,
						       system_error_code );

					if( print_count != -1 )
					{
						error_string_size += print_count;
					}
				}
			}
		}
	}
	if( message_size >= LIBCERROR_MESSAGE_MAXIMUM_SIZE )
	{
		error_string[ LIBCERROR_MESSAGE_MAXIMUM_SIZE - 4 ] = (system_character_t) '.';
		error_string[ LIBCERROR_MESSAGE_MAXIMUM_SIZE - 3 ] = (system_character_t) '.';
		error_string[ LIBCERROR_MESSAGE_MAXIMUM_SIZE - 2 ] = (system_character_t) '.';
		error_string[ LIBCERROR_MESSAGE_MAXIMUM_SIZE - 1 ] = 0;
		error_string_size                                  = (size_t) LIBCERROR_MESSAGE_MAXIMUM_SIZE;
	}
	message_index = internal_error->number_of_messages - 1;

	internal_error->messages[ message_index ] = error_string;
	internal_error->sizes[ message_index ]    = error_string_size;

	return;

on_error:
#if defined( HAVE_WIDE_SYSTEM_CHARACTER )
	if( system_format_string != NULL )
	{
		memory_free(
		 system_format_string );
	}
#endif
	return;
}

#undef VARARGS
#undef VASTART
#undef VAEND

