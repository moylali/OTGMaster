package app.fayaz.otgmaster.luks

import app.fayaz.otgmaster.luks.Luks2Images.PASSWORD
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * LUKS2 headers the app must refuse or read exactly as cryptsetup does. Review of
 * 2026-11-11 (the Joplin note "VeraCrypt and LUKS2 implementation review"), L01 to L09.
 * Each image is made by cryptsetup and edited with both checksums recomputed, so the
 * edit is the only thing wrong with it; before these fixes the app returned a decrypted
 * device for most of them, or called an unsupported volume a wrong password.
 */
class Luks2ValidationTest {

    private val tmp = mutableListOf<File>()
    private fun image(vararg extra: String) =
        Luks2Images.format(File.createTempFile("luks2val", ".img").also { tmp += it }, *extra)

    @Before fun needTools() = assumeTrue("host toolchain or cryptsetup unavailable", Luks2Images.ready)
    @After fun cleanUp() { tmp.forEach { it.delete() } }

    private fun refused(file: File, mention: String, password: String = PASSWORD) {
        try {
            Luks2Images.unlock(file, password).close()
            fail("the app opened a volume it must refuse ($mention)")
        } catch (e: LuksUnlocker.UnsupportedFormatException) {
            assertTrue("refusal does not say why (wanted \"$mention\"): ${e.message}",
                e.message!!.contains(mention, ignoreCase = true))
        }
    }

    // ── L03: a key is accepted only through a digest bound to the data segment ──────

    @Test fun `no digest at all is refused, even with a wrong password`() {
        val img = image()
        Luks2Images.editJson(img) { it.put("digests", JSONObject()) }
        refused(img, "no key digest", password = "not the password")
    }

    @Test fun `a digest not bound to the data segment is refused`() {
        val img = image()
        Luks2Images.editJson(img) { j -> j.getJSONObject("digests").getJSONObject("0").put("segments", JSONArray()) }
        refused(img, "no key digest")
    }

    @Test fun `a keyslot no digest covers is not used`() {
        val img = image()
        Luks2Images.editJson(img) { j -> j.getJSONObject("digests").getJSONObject("0").put("keyslots", JSONArray()) }
        refused(img, "not bound to the data segment")
    }

    // ── L01: requirements, integrity and multi-segment layouts are refused ──────────

    @Test fun `a mandatory requirement is refused`() {
        val img = image()
        Luks2Images.editJson(img) { j ->
            j.getJSONObject("config").put("requirements", JSONObject().put("mandatory", JSONArray().put("online-reencrypt-v2")))
        }
        refused(img, "online-reencrypt-v2")
    }

    @Test fun `integrity metadata is refused`() {
        val img = image()
        Luks2Images.editJson(img) { j ->
            j.getJSONObject("segments").getJSONObject("0").put("integrity",
                JSONObject().put("type", "hmac(sha256)").put("journal_encryption", "none").put("journal_integrity", "none"))
        }
        refused(img, "dm-integrity")
    }

    @Test fun `two data segments are refused`() {
        val img = image()
        Luks2Images.editJson(img) { j ->
            val segs = j.getJSONObject("segments")
            segs.put("1", JSONObject(segs.getJSONObject("0").toString()))
        }
        refused(img, "2 data segments")
    }

    // ── L02: checksums and the second header copy ───────────────────────────────────

    @Test fun `damaged checksums in both copies are refused, as cryptsetup refuses them`() {
        val img = image()
        Luks2Images.corrupt(img, 4096 + 10)            // primary JSON
        Luks2Images.corrupt(img, 16384 + 4096 + 10)    // secondary JSON
        val (rc, _) = Luks2Images.run("cryptsetup", "luksDump", "--disable-locks", img.path)
        assertTrue("cryptsetup accepted the damaged image", rc != 0)
        refused(img, "damaged")
    }

    @Test fun `a damaged primary header falls back to the intact secondary`() {
        val img = image()
        Luks2Images.corrupt(img, 0)                    // primary magic
        Luks2Images.unlock(img).close()
    }

    @Test fun `the copy with the higher sequence id is used`() {
        val img = image()
        // The secondary says the segment is 1 MiB; the primary says dynamic. Newest wins.
        Luks2Images.editJsonOf(img, 16384) { j -> j.getJSONObject("segments").getJSONObject("0").put("size", "1048576") }
        Luks2Images.setSeqId(img, 16384, 1000)
        val dev = Luks2Images.unlock(img)
        assertEquals(1048576L, dev.blockCount * dev.blockSize)
        dev.close()
    }

    // ── L04: segment length and keyslot area bounds ────────────────────────────────

    @Test fun `a fixed segment size bounds the decrypted device`() {
        val img = image()
        Luks2Images.editJson(img) { j -> j.getJSONObject("segments").getJSONObject("0").put("size", "4096") }
        val dev = Luks2Images.unlock(img)
        assertEquals("the device ran past the 4 KiB segment", 4096L, dev.blockCount * dev.blockSize)
        dev.close()
    }

    @Test fun `a segment past the end of the partition is refused`() {
        val img = image()
        Luks2Images.editJson(img) { j -> j.getJSONObject("segments").getJSONObject("0").put("size", (1L shl 40).toString()) }
        refused(img, "does not fit")
    }

    @Test fun `a keyslot area outside the metadata is not read`() {
        val img = image()
        Luks2Images.editJson(img) { j ->
            j.getJSONObject("keyslots").getJSONObject("0").getJSONObject("area").put("offset", (20L shl 20).toString())
        }
        refused(img, "keyslot area")
    }

    // ── L08: priorities ─────────────────────────────────────────────────────────────

    @Test fun `a priority-0 keyslot is never used`() {
        val img = image()
        Luks2Images.editJson(img) { j -> j.getJSONObject("keyslots").getJSONObject("0").put("priority", 0) }
        refused(img, "priority 0")
    }

    @Test fun `an Argon2 keyslot that cannot fit in free memory is refused, not tried`() {
        val img = image("--pbkdf", "argon2id", "--pbkdf-memory", "65536", "--pbkdf-force-iterations", "4")
        try {
            Luks2Images.unlock(img, availableMemoryBytes = 16L shl 20).close()
            fail("an Argon2 slot needing 64 MiB ran with 16 MiB free")
        } catch (e: LuksUnlocker.UnsupportedFormatException) {
            assertTrue(e.message!!, e.message!!.contains("MiB free"))
        }
    }

    // ── L09 and L06: unsupported algorithms and key sizes are named, not a wrong password ─

    @Test fun `an unknown AF hash is refused by name`() {
        val img = image()
        Luks2Images.editJson(img) { j -> j.getJSONObject("keyslots").getJSONObject("0").getJSONObject("af").put("hash", "whirlpool") }
        refused(img, "whirlpool")
    }

    @Test fun `AES-XTS with 256-bit keys is reported as unsupported`() {
        val img = File.createTempFile("luks2val", ".img").also { tmp += it }
        Luks2Images.format(img)                       // 512-bit, then re-format at 256
        val (rc, out) = Luks2Images.run("cryptsetup", "luksFormat", "--type", "luks2", "--batch-mode", "--disable-locks",
            "--cipher", "aes-xts-plain64", "--key-size", "256", "--pbkdf", "pbkdf2", "--iter-time", "50",
            "--luks2-metadata-size", "16k", "--luks2-keyslots-size", "1m", img.path, "-", input = PASSWORD)
        assertEquals(out, 0, rc)
        refused(img, "256-bit AES-XTS")
    }

    @Test fun `a keyslot with its own smaller key size is reported as unsupported`() {
        val img = image("--keyslot-cipher", "aes-xts-plain64", "--keyslot-key-size", "256")
        refused(img, "256-bit keyslot encryption key")
    }

    // ── L07: the IV offset on 512-byte sectors ─────────────────────────────────────

    @Test fun `iv_tweak shifts the IV, as cryptsetup and an independent AES read it`() {
        val img = image()
        Luks2Images.editJson(img) { j -> j.getJSONObject("segments").getJSONObject("0").put("iv_tweak", "8") }
        val (dumpRc, dump) = Luks2Images.run("cryptsetup", "luksDump", "--disable-locks", img.path)
        assertEquals(dump, 0, dumpRc)
        assumeTrue("python3 cryptography unavailable", Luks2Images.run("python3", "-c", "import cryptography").first == 0)
        // Encrypt a random plain image with scripts/luks_payload.py, which takes the key and
        // the IV offset from cryptsetup and does the AES itself.
        val plain = File.createTempFile("plain", ".img").also { tmp += it }
        val data = Random(7).nextBytes(64 * 1024)
        plain.writeBytes(data)
        val (rc, out) = Luks2Images.run("python3", File(Luks2Images.repo, "scripts/luks_payload.py").path,
            "encrypt", img.path, PASSWORD, plain.path)
        assertEquals(out, 0, rc)
        val dev = Luks2Images.unlock(img)
        val got = dev.readBlocks(0, data.size / 512)
        dev.close()
        assertArrayEquals("the app ignored iv_tweak", data, got)
    }

    @Test fun `an ordinary volume still unlocks and rejects a wrong password`() {
        val img = image()
        assertNotNull(Luks2Images.unlock(img).also { it.close() })
        try {
            Luks2Images.unlock(img, "wrong").close(); fail("a wrong password opened the volume")
        } catch (_: LuksUnlocker.WrongPasswordException) {}
    }
}
