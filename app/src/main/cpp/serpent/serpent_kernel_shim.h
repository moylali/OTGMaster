/*
 * New code (not from Linux). What serpent_generic.c needs from the kernel headers it
 * includes there, for building it as plain C (docs/VENDOR_FIXES.md, Serpent section).
 * The kernel's include/crypto/serpent.h is GPL-2.0-only and is not used; the context and
 * prototypes below are written here to the same shape.
 */
#ifndef SERPENT_KERNEL_SHIM_H
#define SERPENT_KERNEL_SHIM_H

#include <stdint.h>
#include <string.h>

typedef uint8_t u8;
typedef uint32_t u32;
/* The key is read as little-endian words in place (__serpent_setkey). Every Android ABI
 * and the x86-64 host are little-endian, so the conversion is the identity. */
typedef uint32_t __le32;
#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__
#error "serpent_kernel_shim.h assumes a little-endian target"
#endif
static inline u32 le32_to_cpu(__le32 v) { return v; }

#define noinline __attribute__((noinline))
#define EXPORT_SYMBOL_GPL(sym)

static inline u32 rol32(u32 w, unsigned int s) { return (w << (s & 31)) | (w >> ((-s) & 31)); }
static inline u32 ror32(u32 w, unsigned int s) { return (w >> (s & 31)) | (w << ((-s) & 31)); }

static inline u32 get_unaligned_le32(const void *p) {
    const u8 *b = (const u8 *) p;
    return (u32) b[0] | ((u32) b[1] << 8) | ((u32) b[2] << 16) | ((u32) b[3] << 24);
}
static inline void put_unaligned_le32(u32 v, void *p) {
    u8 *b = (u8 *) p;
    b[0] = (u8) v; b[1] = (u8) (v >> 8); b[2] = (u8) (v >> 16); b[3] = (u8) (v >> 24);
}

#define SERPENT_MAX_KEY_SIZE 32
#define SERPENT_EXPKEY_WORDS 132
#define SERPENT_BLOCK_SIZE 16

struct serpent_ctx {
    u32 expkey[SERPENT_EXPKEY_WORDS];
};

#ifdef __cplusplus
extern "C" {
#endif
int __serpent_setkey(struct serpent_ctx *ctx, const u8 *key, unsigned int keylen);
void __serpent_encrypt(const void *ctx, u8 *dst, const u8 *src);
void __serpent_decrypt(const void *ctx, u8 *dst, const u8 *src);
#ifdef __cplusplus
}
#endif

#endif
