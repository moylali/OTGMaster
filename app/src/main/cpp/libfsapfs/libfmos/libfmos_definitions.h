/*
 * The internal definitions
 *
 * Copyright (C) 2019-2026, Joachim Metz <joachim.metz@gmail.com>
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

#if !defined( LIBFMOS_INTERNAL_DEFINITIONS_H )
#define LIBFMOS_INTERNAL_DEFINITIONS_H

#include <common.h>
#include <types.h>

/* Define HAVE_LOCAL_LIBFMOS for local use of libfmos
 */
#if !defined( HAVE_LOCAL_LIBFMOS )
#include <libfmos/definitions.h>

/* The definitions in <libfmos/definitions.h> are copied here
 * for local use of libfmos
 */
#else
#include <byte_stream.h>

#define LIBFMOS_VERSION					20260520

/* The version string
 */
#define LIBFMOS_VERSION_STRING				"20260520"

#endif /* !defined( HAVE_LOCAL_LIBFMOS ) */

#define LIBFMOS_LZFSE_NUMBER_OF_LITERAL_STATES		1024
#define LIBFMOS_LZFSE_NUMBER_OF_LITERAL_SYMBOLS		256

#define LIBFMOS_LZFSE_NUMBER_OF_L_VALUE_STATES		64
#define LIBFMOS_LZFSE_NUMBER_OF_L_VALUE_SYMBOLS		20

#define LIBFMOS_LZFSE_NUMBER_OF_M_VALUE_STATES		64
#define LIBFMOS_LZFSE_NUMBER_OF_M_VALUE_SYMBOLS		20

#define LIBFMOS_LZFSE_NUMBER_OF_D_VALUE_STATES		256
#define LIBFMOS_LZFSE_NUMBER_OF_D_VALUE_SYMBOLS		64

#define LIBFMOS_LZFSE_ENDOFSTREAM_BLOCK_MARKER		0x24787662UL
#define LIBFMOS_LZFSE_UNCOMPRESSED_BLOCK_MARKER		0x2d787662UL
#define LIBFMOS_LZFSE_COMPRESSED_BLOCK_V1_MARKER	0x31787662UL
#define LIBFMOS_LZFSE_COMPRESSED_BLOCK_V2_MARKER	0x32787662UL
#define LIBFMOS_LZFSE_COMPRESSED_BLOCK_LZVN_MARKER	0x6e787662UL

#define LIBFMOS_LZFSE_MATCHES_PER_BLOCK			10000
#define LIBFMOS_LZFSE_LITERALS_PER_BLOCK		( 4 * LIBFMOS_LZFSE_MATCHES_PER_BLOCK )

#endif /* !defined( LIBFMOS_INTERNAL_DEFINITIONS_H ) */

