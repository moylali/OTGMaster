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

#if !defined( _LIBCFILE_WINAPI_H )
#define _LIBCFILE_WINAPI_H

#include <common.h>
#include <types.h>

#if defined( __cplusplus )
extern "C" {
#endif

#if defined( WINAPI ) && ( WINVER <= 0x0500 )

BOOL libcfile_CloseHandle(
      HANDLE file_handle );

HANDLE libcfile_CreateFileA(
        LPCSTR filename,
        DWORD desired_access,
        DWORD share_mode,
        SECURITY_ATTRIBUTES *security_attributes,
        DWORD creation_disposition,
        DWORD flags_and_attributes,
        HANDLE template_file );

#if defined( HAVE_WIDE_CHARACTER_TYPE )

HANDLE libcfile_CreateFileW(
        LPCWSTR filename,
        DWORD desired_access,
        DWORD share_mode,
        SECURITY_ATTRIBUTES *security_attributes,
        DWORD creation_disposition,
        DWORD flags_and_attributes,
        HANDLE template_file );

#endif /* defined( HAVE_WIDE_CHARACTER_TYPE ) */

BOOL libcfile_GetOverlappedResult(
      HANDLE file_handle,
      OVERLAPPED *overlapped,
      DWORD *read_count,
      BOOL wait_io_complete );

BOOL libcfile_ReadFile(
      HANDLE file_handle,
      VOID *buffer,
      DWORD read_size,
      DWORD *read_count,
      OVERLAPPED *overlapped );

BOOL libcfile_WriteFile(
      HANDLE file_handle,
      VOID *buffer,
      DWORD write_size,
      DWORD *write_count,
      OVERLAPPED *overlapped );

BOOL libcfile_SetFilePointerEx(
      HANDLE file_handle,
      LARGE_INTEGER distance_to_move_large_integer,
      LARGE_INTEGER *new_file_pointer_large_integer,
      DWORD move_method );

BOOL libcfile_SetEndOfFile(
      HANDLE file_handle );

BOOL libcfile_GetFileSizeEx(
      HANDLE file_handle,
      LARGE_INTEGER *file_size_large_integer );

DWORD libcfile_GetFileType(
       HANDLE file_handle );

DWORD libcfile_GetFileAttributesA(
        LPCSTR filename );

#if defined( HAVE_WIDE_CHARACTER_TYPE )

DWORD libcfile_GetFileAttributesW(
       LPCWSTR filename );

#endif /* defined( HAVE_WIDE_CHARACTER_TYPE ) */

BOOL libcfile_DeleteFileA(
      LPCSTR filename );

#if defined( HAVE_WIDE_CHARACTER_TYPE )

BOOL libcfile_DeleteFileW(
      LPCWSTR filename );

#endif /* defined( HAVE_WIDE_CHARACTER_TYPE ) */

#endif /* defined( WINAPI ) && ( WINVER <= 0x0500 ) */

#if defined( __cplusplus )
}
#endif

#endif /* !defined( _LIBCFILE_WINAPI_H ) */

