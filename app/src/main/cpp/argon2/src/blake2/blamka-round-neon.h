/*
 * LOCAL ADDITION (docs/VENDOR_FIXES.md, argon2): not upstream. An AArch64 NEON port of the
 * SSSE3 path of upstream's blamka-round-opt.h at the pinned commit, one 128-bit NEON
 * register (two 64-bit words) per __m128i, macro for macro:
 *
 *   _mm_add_epi64 / _mm_xor_si128   vaddq_u64 / veorq_u64
 *   _mm_mul_epu32                   vmull_u32 of the low 32 bits of each word (vmovn_u64)
 *   rotate right by 32              vrev64q_u32
 *   rotate right by 24, 16, 63      vsriq_n_u64(vshlq_n_u64(x, 64 - n), x, n)
 *   _mm_alignr_epi8(a, b, 8)        vextq_u64(b, a, 1): { b[1], a[0] }
 *
 * Same licence as the rest of Argon2: CC0 1.0 or Apache 2.0, at your option.
 */

#ifndef BLAKE_ROUND_MKA_NEON_H
#define BLAKE_ROUND_MKA_NEON_H

#include <arm_neon.h>

#include "blake2-impl.h"

static BLAKE2_INLINE uint64x2_t neon_rotr32(uint64x2_t x) {
    return vreinterpretq_u64_u32(vrev64q_u32(vreinterpretq_u32_u64(x)));
}
#define NEON_ROTR(x, n) vsriq_n_u64(vshlq_n_u64((x), 64 - (n)), (x), (n))

/* x + y + 2 * lo32(x) * lo32(y), per 64-bit word. */
static BLAKE2_INLINE uint64x2_t fBlaMka(uint64x2_t x, uint64x2_t y) {
    const uint64x2_t z = vmull_u32(vmovn_u64(x), vmovn_u64(y));
    return vaddq_u64(vaddq_u64(x, y), vaddq_u64(z, z));
}

#define G1(A0, B0, C0, D0, A1, B1, C1, D1)                                     \
    do {                                                                       \
        A0 = fBlaMka(A0, B0);                                                  \
        A1 = fBlaMka(A1, B1);                                                  \
        D0 = veorq_u64(D0, A0);                                                \
        D1 = veorq_u64(D1, A1);                                                \
        D0 = neon_rotr32(D0);                                                       \
        D1 = neon_rotr32(D1);                                                       \
        C0 = fBlaMka(C0, D0);                                                  \
        C1 = fBlaMka(C1, D1);                                                  \
        B0 = veorq_u64(B0, C0);                                                \
        B1 = veorq_u64(B1, C1);                                                \
        B0 = NEON_ROTR(B0, 24);                                                   \
        B1 = NEON_ROTR(B1, 24);                                                   \
    } while ((void)0, 0)

#define G2(A0, B0, C0, D0, A1, B1, C1, D1)                                     \
    do {                                                                       \
        A0 = fBlaMka(A0, B0);                                                  \
        A1 = fBlaMka(A1, B1);                                                  \
        D0 = veorq_u64(D0, A0);                                                \
        D1 = veorq_u64(D1, A1);                                                \
        D0 = NEON_ROTR(D0, 16);                                                   \
        D1 = NEON_ROTR(D1, 16);                                                   \
        C0 = fBlaMka(C0, D0);                                                  \
        C1 = fBlaMka(C1, D1);                                                  \
        B0 = veorq_u64(B0, C0);                                                \
        B1 = veorq_u64(B1, C1);                                                \
        B0 = NEON_ROTR(B0, 63);                                                   \
        B1 = NEON_ROTR(B1, 63);                                                   \
    } while ((void)0, 0)

/* Upstream's SSSE3 DIAGONALIZE, with _mm_alignr_epi8(a, b, 8) as vextq_u64(b, a, 1). */
#define DIAGONALIZE(A0, B0, C0, D0, A1, B1, C1, D1)                            \
    do {                                                                       \
        uint64x2_t t0 = vextq_u64(B0, B1, 1);                                  \
        uint64x2_t t1 = vextq_u64(B1, B0, 1);                                  \
        B0 = t0;                                                               \
        B1 = t1;                                                               \
        t0 = C0;                                                               \
        C0 = C1;                                                               \
        C1 = t0;                                                               \
        t0 = vextq_u64(D0, D1, 1);                                             \
        t1 = vextq_u64(D1, D0, 1);                                             \
        D0 = t1;                                                               \
        D1 = t0;                                                               \
    } while ((void)0, 0)

#define UNDIAGONALIZE(A0, B0, C0, D0, A1, B1, C1, D1)                          \
    do {                                                                       \
        uint64x2_t t0 = vextq_u64(B1, B0, 1);                                  \
        uint64x2_t t1 = vextq_u64(B0, B1, 1);                                  \
        B0 = t0;                                                               \
        B1 = t1;                                                               \
        t0 = C0;                                                               \
        C0 = C1;                                                               \
        C1 = t0;                                                               \
        t0 = vextq_u64(D1, D0, 1);                                             \
        t1 = vextq_u64(D0, D1, 1);                                             \
        D0 = t1;                                                               \
        D1 = t0;                                                               \
    } while ((void)0, 0)

#define BLAKE2_ROUND(A0, A1, B0, B1, C0, C1, D0, D1)                           \
    do {                                                                       \
        G1(A0, B0, C0, D0, A1, B1, C1, D1);                                    \
        G2(A0, B0, C0, D0, A1, B1, C1, D1);                                    \
        DIAGONALIZE(A0, B0, C0, D0, A1, B1, C1, D1);                           \
        G1(A0, B0, C0, D0, A1, B1, C1, D1);                                    \
        G2(A0, B0, C0, D0, A1, B1, C1, D1);                                    \
        UNDIAGONALIZE(A0, B0, C0, D0, A1, B1, C1, D1);                         \
    } while ((void)0, 0)

#endif /* BLAKE_ROUND_MKA_NEON_H */
