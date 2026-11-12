package app.fayaz.otgmaster.veracrypt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * VeraCrypt credential and header rules from the review of 2026-11-11 (V01, V04, V06).
 */
class VeraCryptCredentialRulesTest {

    // ── V06: PIM range ──────────────────────────────────────────────────────────────

    @Test fun `a negative PIM is refused, not taken as the default`() {
        try { KdfParams.forVolume(VeraCryptHash.SHA512, -5); fail("PIM -5 was accepted") }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun `a PIM whose iteration count would overflow is refused`() {
        try { KdfParams.forVolume(VeraCryptHash.SHA512, KdfParams.MAX_PIM + 1); fail("an overflowing PIM was accepted") }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun `the largest PIM gives VeraCrypt's iteration count`() {
        assertEquals(15_000 + KdfParams.MAX_PIM * 1000, KdfParams.forVolume(VeraCryptHash.SHA512, KdfParams.MAX_PIM).iterations)
        assertEquals(500_000, KdfParams.forVolume(VeraCryptHash.SHA512, null).iterations)
        assertEquals(500_000, KdfParams.forVolume(VeraCryptHash.SHA512, 0).iterations)
    }

    // ── V04: keyfiles fail closed ───────────────────────────────────────────────────

    @Test fun `an empty keyfile fails the unlock`() {
        try {
            VeraCryptKeyfiles.apply("pw".toByteArray(), listOf({ ByteArrayInputStream(ByteArray(0)) }))
            fail("an empty keyfile was used")
        } catch (e: IOException) { assertTrue(e.message!!, e.message!!.contains("empty")) }
    }

    @Test fun `a keyfile that cannot be opened fails the unlock`() {
        try {
            VeraCryptKeyfiles.apply("pw".toByteArray(), listOf({ null }))
            fail("an unopenable keyfile was skipped")
        } catch (_: IOException) {}
    }

    @Test fun `a keyfile whose read fails fails the unlock and wipes the password`() {
        val password = "secret".toByteArray()
        try {
            VeraCryptKeyfiles.apply(password, listOf({ throw SecurityException("grant revoked") }))
            fail("an unreadable keyfile was skipped")
        } catch (_: IOException) {}
        assertTrue("the password was left in memory", password.all { it == 0.toByte() })
    }

    // ── V01: headers this app cannot use, even when they decrypt and pass their CRCs ──

    private fun header(
        formatVersion: Int = 5, minProgramVersion: Int = 0x010B, flags: Int = 0,
        volumeSize: Long = 10L shl 20, encryptedAreaSize: Long = volumeSize, sectorSize: Int = 512,
    ) = VeraCryptHeader(formatVersion, minProgramVersion, 0, volumeSize, 131072, encryptedAreaSize, flags, sectorSize, ByteArray(256))

    @Test fun `an ordinary header is usable`() = assertNull(header().unsupportedReason(512))

    @Test fun `a system-encryption header is refused`() =
        assertTrue(header(flags = 1).unsupportedReason(512)!!.contains("system"))

    @Test fun `a volume whose in-place encryption has not finished is refused`() =
        assertTrue(header(encryptedAreaSize = 4L shl 20).unsupportedReason(512)!!.contains("in-place"))

    @Test fun `a newer header format is refused`() =
        assertTrue(header(formatVersion = 6).unsupportedReason(512)!!.contains("format"))

    @Test fun `a header needing a newer VeraCrypt is refused`() =
        assertTrue(header(minProgramVersion = 0x0200).unsupportedReason(512)!!.contains("2.00"))

    @Test fun `a header made with other sectors than the drive's is refused`() =
        assertTrue(header(sectorSize = 4096).unsupportedReason(512)!!.contains("4096-byte"))

    @Test fun `the app's own Encrypt output is usable`() {
        // VeraCryptVolumeCreator writes format 5, minimum 1.11 (0x010B), flags 0, and an
        // encrypted area equal to the volume size.
        assertNull(header(formatVersion = 5, minProgramVersion = 0x010B).unsupportedReason(512))
    }
}
