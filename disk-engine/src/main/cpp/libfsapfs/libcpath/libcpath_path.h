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

#if !defined( _LIBCPATH_PATH_H )
#define _LIBCPATH_PATH_H

#include <common.h>
#include <types.h>

#include "libcpath_extern.h"
#include "libcpath_libcerror.h"

#if defined( __cplusplus )
extern "C" {
#endif

#if defined( WINAPI ) && ( WINVER <= 0x0500 )

BOOL libcpath_CloseHandle(
      HANDLE file_handle );

#endif /* defined( WINAPI ) && ( WINVER <= 0x0500 ) */

#if defined( WINAPI ) && ( WINVER <= 0x0500 )

BOOL libcpath_SetCurrentDirectoryA(
      LPCSTR path );

#endif /* defined( WINAPI ) && ( WINVER <= 0x0500 ) */

LIBCPATH_EXTERN \
int libcpath_path_change_directory(
     const char *directory_name,
     libcerror_error_t **error );

#if defined( WINAPI ) && ( WINVER <= 0x0500 )

DWORD libcpath_GetCurrentDirectoryA(
       DWORD buffer_size,
       LPCSTR buffer );

#endif /* defined( WINAPI ) && ( WINVER <= 0x0500 ) */

LIBCPATH_EXTERN \
int libcpath_path_get_current_working_directory(
     char **current_working_directory,
     size_t *current_working_directory_size,
     libcerror_error_t **error );

#if defined( WINAPI ) && ( WINVER <= 0x0500 )

DWORD libcpath_GetFullPathNameA(
       LPCSTR filename,
       DWORD  buffer_size,
       LPSTR  buffer,
       LPSTR  *file_part )

#endif /* defined( WINAPI ) && ( WINVER <= 0x0500 ) */

#if defined( WINAPI )

int libcpath_path_get_path_type(
     const char *path,
     size_t path_length,
     uint8_t *path_type,
     libcerror_error_t **error );

int libcpath_path_get_volume_name(
     const char *path,
     size_t path_length,
     char **volume_name,
     size_t *volume_name_length,
     size_t *directory_name_index,
     libcerror_error_t **error );

int libcpath_path_get_current_working_directory_by_volume(
     char *volume_name,
     size_t volume_name_length,
     char **current_working_directory,
     size_t *current_working_directory_size,
     libcerror_error_t **error );

#endif /* defined( WINAPI ) */

LIBCPATH_EXTERN \
int libcpath_path_get_full_path(
     const char *path,
     size_t path_length,
     char **full_path,
     size_t *full_path_size,
     libcerror_error_t **error );

int libcpath_path_get_sanitized_character_size(
     char character,
     size_t *sanitized_character_size,
     libcerror_error_t **error );

int libcpath_path_get_sanitized_character(
     char character,
     size_t sanitized_character_size,
     char *sanitized_path,
     size_t sanitized_path_size,
     size_t *sanitized_path_index,
     libcerror_error_t **error );

LIBCPATH_EXTERN \
int libcpath_path_get_sanitized_filename(
     const char *filename,
     size_t filename_length,
     char **sanitized_filename,
     size_t *sanitized_filename_size,
     libcerror_error_t **error );

LIBCPATH_EXTERN \
int libcpath_path_get_sanitized_path(
     const char *path,
     size_t path_length,
     char **sanitized_path,
     size_t *sanitized_path_size,
     libcerror_error_t **error );

LIBCPATH_EXTERN \
int libcpath_path_join(
     char **path,
     size_t *path_size,
     const char *directory_name,
     size_t directory_name_length,
     const char *filename,
     size_t filename_length,
     libcerror_error_t **error );

#if defined( WINAPI ) && ( WINVER <= 0x0500 )

BOOL libcpath_CreateDirectoryA(
      LPCSTR path,
      SECURITY_ATTRIBUTES *security_attributes );

#endif /* defined( WINAPI ) && ( WINVER <= 0x0500 ) */

LIBCPATH_EXTERN \
int libcpath_path_make_directory(
     const char *directory_name,
     libcerror_error_t **error );

#if defined( HAVE_WIDE_CHARACTER_TYPE )

#if defined( WINAPI ) && ( WINVER <= 0x0500 )

BOOL libcpath_SetCurrentDirectoryW(
      LPCWSTR path );

#endif /* defined( WINAPI ) && ( WINVER <= 0x0500 ) */

LIBCPATH_EXTERN \
int libcpath_path_change_directory_wide(
     const wchar_t *directory_name,
     libcerror_error_t **error );

#if defined( WINAPI ) && ( WINVER <= 0x0500 )

DWORD libcpath_GetCurrentDirectoryW(
       DWORD buffer_size,
       LPCWSTR buffer );

#endif /* defined( WINAPI ) && ( WINVER <= 0x0500 ) */

LIBCPATH_EXTERN \
int libcpath_path_get_current_working_directory_wide(
     wchar_t **current_working_directory,
     size_t *current_working_directory_size,
     libcerror_error_t **error );

#if defined( WINAPI ) && ( WINVER <= 0x0500 )

DWORD libcpath_GetFullPathNameW(
       LPCWSTR filename,
       DWORD  buffer_size,
       LPWSTR  buffer,
       LPWSTR  *file_part )

#endif /* defined( WINAPI ) && ( WINVER <= 0x0500 ) */

#if defined( WINAPI )

int libcpath_path_get_path_type_wide(
     const wchar_t *path,
     size_t path_length,
     uint8_t *path_type,
     libcerror_error_t **error );

int libcpath_path_get_volume_name_wide(
     const wchar_t *path,
     size_t path_length,
     wchar_t **volume_name,
     size_t *volume_name_length,
     size_t *directory_name_index,
     libcerror_error_t **error );

int libcpath_path_get_current_working_directory_by_volume_wide(
     wchar_t *volume_name,
     size_t volume_name_length,
     wchar_t **current_working_directory,
     size_t *current_working_directory_size,
     libcerror_error_t **error );

#endif /* defined( WINAPI ) */

LIBCPATH_EXTERN \
int libcpath_path_get_full_path_wide(
     const wchar_t *path,
     size_t path_length,
     wchar_t **full_path,
     size_t *full_path_size,
     libcerror_error_t **error );

int libcpath_path_get_sanitized_character_size_wide(
     wchar_t character,
     size_t *sanitized_character_size,
     libcerror_error_t **error );

int libcpath_path_get_sanitized_character_wide(
     wchar_t character,
     size_t sanitized_character_size,
     wchar_t *sanitized_path,
     size_t sanitized_path_size,
     size_t *sanitized_path_index,
     libcerror_error_t **error );

LIBCPATH_EXTERN \
int libcpath_path_get_sanitized_filename_wide(
     const wchar_t *filename,
     size_t filename_length,
     wchar_t **sanitized_filename,
     size_t *sanitized_filename_size,
     libcerror_error_t **error );

LIBCPATH_EXTERN \
int libcpath_path_get_sanitized_path_wide(
     const wchar_t *path,
     size_t path_length,
     wchar_t **sanitized_path,
     size_t *sanitized_path_size,
     libcerror_error_t **error );

LIBCPATH_EXTERN \
int libcpath_path_join_wide(
     wchar_t **path,
     size_t *path_size,
     const wchar_t *directory_name,
     size_t directory_name_length,
     const wchar_t *filename,
     size_t filename_length,
     libcerror_error_t **error );

#if defined( WINAPI ) && ( WINVER <= 0x0500 )

BOOL libcpath_CreateDirectoryW(
      LPCWSTR path,
      SECURITY_ATTRIBUTES *security_attributes );

#endif /* defined( WINAPI ) && ( WINVER <= 0x0500 ) */

LIBCPATH_EXTERN \
int libcpath_path_make_directory_wide(
     const wchar_t *directory_name,
     libcerror_error_t **error );

#endif /* defined( HAVE_WIDE_CHARACTER_TYPE ) */

#if defined( __cplusplus )
}
#endif

#endif /* !defined( _LIBCPATH_PATH_H ) */

