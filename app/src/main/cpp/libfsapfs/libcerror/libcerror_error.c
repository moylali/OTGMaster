/*
 * Error functions
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

#if defined( HAVE_STDARG_H ) || defined( WINAPI )
#include <stdarg.h>
#elif defined( HAVE_VARARGS_H )
#include <varargs.h>
#else
#error Missing headers stdarg.h and varargs.h
#endif

#include <errno.h>

#include "libcerror_definitions.h"
#include "libcerror_error.h"
#include "libcerror_types.h"

/* Creates an error
 * Returns 1 if successful or -1 on error
 */
int libcerror_error_initialize(
     libcerror_error_t **error,
     int error_domain,
     int error_code )
{
	libcerror_internal_error_t *internal_error = NULL;

	if( error == NULL )
	{
		return( -1 );
	}
	if( *error != NULL )
	{
		return( -1 );
	}
	internal_error = memory_allocate_structure(
	                  libcerror_internal_error_t );

	if( internal_error == NULL )
	{
		return( -1 );
	}
	internal_error->domain             = error_domain;
	internal_error->code               = error_code;
	internal_error->number_of_messages = 0;
	internal_error->messages           = NULL;
	internal_error->sizes              = NULL;

	*error = (libcerror_error_t *) internal_error;

	return( 1 );
}

/* Free an error and its elements
 */
void libcerror_error_free(
      libcerror_error_t **error )
{
	libcerror_internal_error_t *internal_error = NULL;
	int message_index                          = 0;

	if( error == NULL )
	{
		return;
	}
	if( *error != NULL )
	{
		internal_error = (libcerror_internal_error_t *) *error;

		if( internal_error->messages != NULL )
		{
			for( message_index = 0;
			     message_index < internal_error->number_of_messages;
			     message_index++ )
			{
				if( internal_error->messages[ message_index ] != NULL )
				{
					memory_free(
					 internal_error->messages[ message_index ] );
				}
			}
			memory_free(
			 internal_error->messages );
		}
		if( internal_error->sizes != NULL )
		{
			memory_free(
			 internal_error->sizes );
		}
		memory_free(
		 *error );

		*error = NULL;
	}
}

/* Resizes an error
 * Returns 1 if successful or -1 on error
 */
int libcerror_error_resize(
     libcerror_internal_error_t *internal_error )
{
	void *reallocation     = NULL;
	int message_index      = 0;
	int number_of_messages = 0;

	if( internal_error == NULL )
	{
		return( -1 );
	}
	message_index      = internal_error->number_of_messages;
	number_of_messages = internal_error->number_of_messages + 1;

	reallocation = memory_reallocate(
	                internal_error->messages,
	                sizeof( system_character_t * ) * number_of_messages );

	if( reallocation == NULL )
	{
		return( -1 );
	}
	internal_error->messages = (system_character_t **) reallocation;

	internal_error->messages[ message_index ] = NULL;

	reallocation = memory_reallocate(
	                internal_error->sizes,
	                sizeof( size_t ) * number_of_messages );

	if( reallocation == NULL )
	{
		return( -1 );
	}
	internal_error->sizes = (size_t *) reallocation;

	internal_error->sizes[ message_index ] = 0;

	internal_error->number_of_messages += 1;

	return( 1 );
}

#if defined( HAVE_WIDE_SYSTEM_CHARACTER )

/* Retrieves the format string as a system string
 */
void libcerror_error_get_system_format_string(
      const char *format_string,
      size_t format_string_length,
      system_character_t **system_format_string )
{
	void *reallocation               = NULL;
	size_t next_format_string_length = 0;
	int print_count                  = 0;

#if defined( __BORLANDC__ ) || defined( _MSC_VER )
	size_t string_index              = 0;
#endif

	if( format_string == NULL )
	{
		return;
	}
	if( format_string_length > (size_t) SSIZE_MAX )
	{
		return;
	}
	if( system_format_string == NULL )
	{
		return;
	}
	next_format_string_length = format_string_length + 1;

	do
	{
		if( next_format_string_length >= LIBCERROR_MESSAGE_MAXIMUM_SIZE )
		{
			next_format_string_length = LIBCERROR_MESSAGE_MAXIMUM_SIZE;
		}
		reallocation = memory_reallocate(
		                *system_format_string,
		                sizeof( system_character_t ) * next_format_string_length );

		if( reallocation == NULL )
		{
			memory_free(
			 *system_format_string );

			*system_format_string = NULL;

			return;
		}
		*system_format_string = (system_character_t *) reallocation;

		format_string_length = next_format_string_length;

#if defined( __BORLANDC__ ) || defined( _MSC_VER )
		print_count = wide_string_snwprintf(
		               *system_format_string,
		               format_string_length,
		               L"%S",
		               format_string );
#else
		print_count = wide_string_snwprintf(
		               *system_format_string,
		               format_string_length,
		               L"%s",
		               format_string );
#endif
		if( print_count <= -1 )
		{
			next_format_string_length += LIBCERROR_MESSAGE_INCREMENT_SIZE;
		}
		else if( ( (size_t) print_count > format_string_length )
		      || ( ( *system_format_string )[ print_count ] != 0 ) )
		{
			next_format_string_length = (size_t) print_count;
			print_count               = -1;
		}
		if( next_format_string_length >= LIBCERROR_MESSAGE_MAXIMUM_SIZE )
		{
/* TODO handle similar to error string */
			memory_free(
			 *system_format_string );

			*system_format_string = NULL;

			return;
		}
	}
	while( print_count <= -1 );

#if defined( __BORLANDC__ ) || defined( _MSC_VER )
	/* Rewrite %s to %S
	 */
	string_index = 0;

	while( string_index < format_string_length )
	{
		if( ( *system_format_string )[ string_index ] == 0 )
		{
			break;
		}
		else if( ( *system_format_string )[ string_index ] == (system_character_t) '%' )
		{
			string_index++;

			if( ( *system_format_string )[ string_index ] == (system_character_t) 's' )
			{
				 ( *system_format_string )[ string_index ] = (system_character_t) 'S';
			}
		}
		string_index++;
	}
#endif /* defined( __BORLANDC__ ) || defined( _MSC_VER ) */
}

#endif /* defined( HAVE_WIDE_SYSTEM_CHARACTER ) */

#if defined( HAVE_STDARG_H ) || defined( WINAPI )
#define VARARGS( function, error, error_domain, error_code, type, argument ) \
        function( error, error_domain, error_code, type argument, ... )
#define VASTART( argument_list, type, name ) \
        va_start( argument_list, name )
#define VAEND( argument_list ) \
        va_end( argument_list )

#elif defined( HAVE_VARARGS_H )
#define VARARGS( function, error, error_domain, error_code, type, argument ) \
        function( error, error_domain, error_code, va_alist ) va_dcl
#define VASTART( argument_list, type, name ) \
        { type name; va_start( argument_list ); name = va_arg( argument_list, type )
#define VAEND( argument_list ) \
        va_end( argument_list ); }

#endif /* defined( HAVE_STDARG_H ) || defined( WINAPI ) */

/* Sets an error
 * Creates the error if necessary
 * The error domain and code are set only the first time and the error message is appended for back tracing
 */
void VARARGS(
      libcerror_error_set,
      libcerror_error_t **error,
      int error_domain,
      int error_code,
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

#if defined( HAVE_WIDE_SYSTEM_CHARACTER )
	memory_free(
	 system_format_string );

	system_format_string = NULL;
#endif
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

/* Determines if an error equals a certain error code of a domain
 * Returns 1 if error matches or 0 if not
 */
int libcerror_error_matches(
     libcerror_error_t *error,
     int error_domain,
     int error_code )
{
	if( error == NULL )
	{
		return( 0 );
	}
	if( ( ( (libcerror_internal_error_t *) error )->domain == error_domain )
	 && ( ( (libcerror_internal_error_t *) error )->code == error_code ) )
	{
		return( 1 );
	}
	return( 0 );
}

/* Prints a descriptive string of the error to the stream
 * Returns the number of printed characters if successful or -1 on error
 */
int libcerror_error_fprint(
     libcerror_error_t *error,
     FILE *stream )
{
	libcerror_internal_error_t *internal_error = NULL;
	system_character_t *error_string           = NULL;
	int message_index                          = 0;
	int print_count                            = 0;

#if defined( WINAPI ) || defined( __MINGW32__ )
	const char *format_string                  = "%" PRIs_SYSTEM "\r\n";
#else
	const char *format_string                  = "%" PRIs_SYSTEM "\n";
#endif

	if( error == NULL )
	{
		return( -1 );
	}
	internal_error = (libcerror_internal_error_t *) error;

	if( internal_error->messages == NULL )
	{
		return( -1 );
	}
	if( stream == NULL )
	{
		return( -1 );
	}
	message_index = internal_error->number_of_messages - 1;
	error_string  = internal_error->messages[ message_index ];

	if( error_string != NULL )
	{
		print_count = fprintf(
		               stream,
		               format_string,
		               error_string );

		if( print_count <= -1 )
		{
			return( -1 );
		}
	}
	return( print_count );
}

/* Prints a descriptive string of the error to the string
 * The end-of-string character is not included in the return value
 * Returns the number of printed characters if successful or -1 on error
 */
int libcerror_error_sprint(
     libcerror_error_t *error,
     char *string,
     size_t size )
{
	libcerror_internal_error_t *internal_error = NULL;
	system_character_t *error_string           = NULL;
	size_t message_index                       = 0;
	size_t print_count                         = 0;

#if !defined( HAVE_WIDE_SYSTEM_CHARACTER )
	size_t error_string_size                   = 0;
#endif

	if( error == NULL )
	{
		return( -1 );
	}
	internal_error = (libcerror_internal_error_t *) error;

	if( internal_error->messages == NULL )
	{
		return( -1 );
	}
	if( internal_error->sizes == NULL )
	{
		return( -1 );
	}
	if( string == NULL )
	{
		return( -1 );
	}
#if INT_MAX < SSIZE_MAX
	if( size > (size_t) INT_MAX )
#else
	if( size > (size_t) SSIZE_MAX )
#endif
	{
		return( -1 );
	}
	message_index = internal_error->number_of_messages - 1;
	error_string  = internal_error->messages[ message_index ];

	if( error_string != NULL )
	{
#if defined( HAVE_WIDE_SYSTEM_CHARACTER )
#if defined( _MSC_VER )
		if( wcstombs_s(
		     &print_count,
		     string,
		     size,
		     error_string,
		     _TRUNCATE ) != 0 )
		{
			return( -1 );
		}
#else
		print_count = wcstombs(
		               string,
		               error_string,
		               size );

		if( print_count == (size_t) -1 )
		{
			return( -1 );
		}
#endif /* defined( _MSC_VER ) */

		if( print_count >= size )
		{
			return( -1 );
		}
#else
		error_string_size = internal_error->sizes[ message_index ];

		if( size < ( error_string_size + 1 ) )
		{
			return( -1 );
		}
		if( narrow_string_copy(
		     string,
		     error_string,
		     error_string_size ) == NULL )
		{
			return( -1 );
		}
		print_count = error_string_size;

#endif /* defined( HAVE_WIDE_SYSTEM_CHARACTER ) */
	}
	if( print_count > (size_t) INT_MAX )
	{
		return( -1 );
	}
	return( (int) print_count );
}

/* Prints a backtrace of the error to the stream
 * Returns the number of printed characters if successful or -1 on error
 */
int libcerror_error_backtrace_fprint(
     libcerror_error_t *error,
     FILE *stream )
{
	libcerror_internal_error_t *internal_error = NULL;
	system_character_t *error_string           = NULL;
	int message_index                          = 0;
	int print_count                            = 0;
	int total_print_count                      = 0;

#if defined( WINAPI ) || defined( __MINGW32__ )
	const char *format_string                  = "%" PRIs_SYSTEM "\r\n";
#else
	const char *format_string                  = "%" PRIs_SYSTEM "\n";
#endif

	if( error == NULL )
	{
		return( -1 );
	}
	internal_error = (libcerror_internal_error_t *) error;

	if( internal_error->messages == NULL )
	{
		return( -1 );
	}
	if( stream == NULL )
	{
		return( -1 );
	}
	for( message_index = 0;
	     message_index < internal_error->number_of_messages;
	     message_index++ )
	{
		error_string = internal_error->messages[ message_index ];

		if( error_string != NULL )
		{
			print_count = fprintf(
			               stream,
			               format_string,
			               error_string );

			if( print_count <= -1 )
			{
				return( -1 );
			}
			total_print_count += print_count;
		}
	}
	return( total_print_count );
}

/* Prints a backtrace of the error to the string
 * The end-of-string character is not included in the return value
 * Returns the number of printed characters if successful or -1 on error
 */
int libcerror_error_backtrace_sprint(
     libcerror_error_t *error,
     char *string,
     size_t size )
{
	libcerror_internal_error_t *internal_error = NULL;
	system_character_t *error_string           = NULL;
	size_t string_index                        = 0;
	int message_index                          = 0;

#if defined( HAVE_WIDE_SYSTEM_CHARACTER )
	size_t print_count                         = 0;
#else
	size_t error_string_size                   = 0;
#endif

	if( error == NULL )
	{
		return( -1 );
	}
	internal_error = (libcerror_internal_error_t *) error;

	if( internal_error->messages == NULL )
	{
		return( -1 );
	}
	if( internal_error->sizes == NULL )
	{
		return( -1 );
	}
	if( string == NULL )
	{
		return( -1 );
	}
#if INT_MAX < SSIZE_MAX
	if( size > (size_t) INT_MAX )
#else
	if( size > (size_t) SSIZE_MAX )
#endif
	{
		return( -1 );
	}
	for( message_index = 0;
	     message_index < internal_error->number_of_messages;
	     message_index++ )
	{
		error_string = internal_error->messages[ message_index ];

		if( error_string != NULL )
		{
			if( string_index > 0 )
			{
#if defined( WINAPI ) || defined( __MINGW32__ )
				if( ( string_index + 2 ) >= size )
				{
					return( -1 );
				}
				string[ string_index++ ] = (system_character_t) '\r';
#else
				if( ( string_index + 1 ) >= size )
				{
					return( -1 );
				}
#endif /* defined( WINAPI ) || defined( __MINGW32__ ) */

				string[ string_index++ ] = (system_character_t) '\n';
				string[ string_index ]   = (system_character_t) 0;
			}
#if defined( HAVE_WIDE_SYSTEM_CHARACTER )
#if defined( _MSC_VER )
			if( wcstombs_s(
			     &print_count,
			     &( string[ string_index ] ),
			     size - string_index,
			     error_string,
			     _TRUNCATE ) != 0 )
			{
				return( -1 );
			}
#else
			print_count = wcstombs(
			               &( string[ string_index ] ),
			               error_string,
			               size - string_index );

			if( print_count == (size_t) -1 )
			{
				return( -1 );
			}
#endif /*defined( _MSC_VER ) */

			string_index += print_count;

			if( string_index >= size )
			{
				return( -1 );
			}
			if( string[ string_index - 1 ] == 0 )
			{
				string_index--;
			}
#else
			error_string_size = internal_error->sizes[ message_index ];

			if( size < ( string_index + error_string_size + 1 ) )
			{
				return( -1 );
			}
			if( narrow_string_copy(
			     &( string[ string_index ] ),
			     error_string,
			     error_string_size ) == NULL )
			{
				return( -1 );
			}
			string_index += error_string_size - 1;

#endif /* defined( HAVE_WIDE_SYSTEM_CHARACTER ) */
		}
	}
	string_index++;

	if( string_index > (size_t) INT_MAX )
	{
		return( -1 );
	}
	return( (int) string_index );
}

