/*
 * libexfat I/O counters, compiled only when OTG_IO_STATS is defined — set from
 * the debug build type in app/build.gradle.kts.
 *
 * The counters live in io.c, next to the pread/pwrite hooks. The JNI accessors
 * live in ExFatNative.cpp instead, because io.c is compiled into the `exfat`
 * STATIC library: a JNI entry point there is referenced by nothing at link time
 * and the linker discards it. ExFatNative.cpp is a direct source of the shared
 * library, so its exports survive.
 */
#ifndef OTG_IO_STATS_H
#define OTG_IO_STATS_H

#ifdef OTG_IO_STATS

#include <stdint.h>
#include <stddef.h>

#define OTG_IO_BUCKETS 6

#ifdef __cplusplus
extern "C" {
#endif

extern volatile uint64_t otg_pread_calls;
extern volatile uint64_t otg_pread_bytes;
extern volatile uint64_t otg_pwrite_calls;
extern volatile uint64_t otg_pwrite_bytes;
/* <=512, <=4K, <=16K, <=64K, <=256K, >256K */
extern volatile uint64_t otg_size_hist[OTG_IO_BUCKETS];

void otg_io_count(size_t size, int is_write);
void otg_io_reset(void);

#ifdef __cplusplus
}
#endif

#define OTG_IO_COUNT(sz, w) otg_io_count((size_t)(sz), (w))

#else /* !OTG_IO_STATS */

#define OTG_IO_COUNT(sz, w) ((void) 0)

#endif
#endif /* OTG_IO_STATS_H */
