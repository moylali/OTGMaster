/*
 * The internal definitions
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

#if !defined( LIBCPATH_INTERNAL_DEFINITIONS_H )
#define LIBCPATH_INTERNAL_DEFINITIONS_H

#include <common.h>
#include <types.h>

/* Define HAVE_LOCAL_LIBCPATH for local use of libcpath
 */
#if !defined( HAVE_LOCAL_LIBCPATH )
#include <libcpath/definitions.h>

/* The definitions in <libcpath/definitions.h> are copied here
 * for local use of libcpath
 */
#else

#define LIBCPATH_VERSION			20260703

/* The libcpath version string
 */
#define LIBCPATH_VERSION_STRING			"20260703"

#if defined( WINAPI ) || defined( __MINGW32__ )
#define LIBCPATH_SEPARATOR			'\\'
#else
#define LIBCPATH_SEPARATOR			'/'
#endif

#endif /* !defined( HAVE_LOCAL_LIBCPATH ) */

#if defined( WINAPI ) || defined( __MINGW32__ )
#define LIBCPATH_ESCAPE_CHARACTER		'^'
#else
#define LIBCPATH_ESCAPE_CHARACTER		'\\'
#endif

#if defined( WINAPI )
enum LIBCPATH_TYPES
{
	LIBCPATH_TYPE_ABSOLUTE,
	LIBCPATH_TYPE_DEVICE,
	LIBCPATH_TYPE_EXTENDED_LENGTH,
	LIBCPATH_TYPE_EXTENDED_LENGTH_UNC,
	LIBCPATH_TYPE_RELATIVE,
	LIBCPATH_TYPE_UNC
};

#else
enum LIBCPATH_TYPES
{
	LIBCPATH_TYPE_ABSOLUTE,
	LIBCPATH_TYPE_RELATIVE
};

#endif /* defined( WINAPI ) */

#endif /* !defined( LIBCPATH_INTERNAL_DEFINITIONS_H ) */

