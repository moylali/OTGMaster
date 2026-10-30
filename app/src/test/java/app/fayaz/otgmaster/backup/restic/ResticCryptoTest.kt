package app.fayaz.otgmaster.backup.restic

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ResticCryptoTest {

    private fun hex(s: String) = s.replace(Regex("[^0-9a-fA-F]"), "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `scrypt matches RFC 7914`() {
        assertArrayEquals(hex("77d6576238657b203b19ca42c18a0497f16b4844e3074ae8dfdffa3fede21442" +
            "fcd0069ded0948f8326a753a0fc81f17e8d3e0fb2e0d3628cf35e20c38d18906"),
            Scrypt.derive(ByteArray(0), ByteArray(0), 16, 1, 1, 64))
        assertArrayEquals(hex("fdbabe1c9d3472007856e7190d01e9fe7c6ad7cbc8237830e77376634b373162" +
            "2eaf30d92e22a3886ff109279d9830dac727afb94a83ee6d8360cbdfa2cc0640"),
            Scrypt.derive("password".toByteArray(), "NaCl".toByteArray(), 1024, 8, 16, 64))
    }

    @Test
    fun `poly1305 matches RFC 8439`() {
        val key = hex("85:d6:be:78:57:55:6d:33:7f:44:52:fe:42:d5:06:a8:01:03:80:8a:fb:0d:b2:fd:4a:bf:f6:af:41:49:f5:1b")
        val tag = Poly1305.mac(key, "Cryptographic Forum Research Group".toByteArray())
        assertArrayEquals(hex("a8:06:1d:c1:30:51:36:c6:c2:2b:8b:af:0c:01:27:a9"), tag)
        // Fed in uneven pieces, the same tag.
        val m = "Cryptographic Forum Research Group".toByteArray()
        val p = Poly1305(key).update(m, 0, 5).update(m, 5, 17).update(m, 22, m.size - 22)
        assertArrayEquals(tag, p.finish())
    }

    @Test
    fun `encrypt then decrypt round-trips, and a flipped byte is refused`() {
        val k = ResticKey.random()
        val msg = ByteArray(100_000) { (it * 31).toByte() }
        val c = k.encrypt(msg)
        assertArrayEquals(msg, k.decrypt(c))
        c[40] = (c[40].toInt() xor 1).toByte()
        assertEquals(true, runCatching { k.decrypt(c) }.exceptionOrNull() is ResticKey.AuthenticationException)
    }

    // ── Against restic itself ────────────────────────────────────────────────

    private fun restic(): String? = listOf(System.getenv("RESTIC"), System.getProperty("user.home") + "/.local/bin/restic", "/usr/bin/restic")
        .firstOrNull { it != null && File(it).canExecute() }

    private fun run(vararg cmd: String, password: String): String {
        val pb = ProcessBuilder(*cmd).redirectErrorStream(true)
        pb.environment()["RESTIC_PASSWORD"] = password
        val p = pb.start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        check(p.waitFor() == 0) { "${cmd.joinToString(" ")} failed:\n$out" }
        return out
    }

    @Test
    fun `a key file restic wrote opens with the password, and not without it`() {
        val restic = restic(); assumeTrue("restic not installed", restic != null)
        val repo = Files.createTempDirectory("restic-key").toFile()
        run(restic!!, "-r", repo.path, "init", "--repository-version", "2", password = "correct horse")
        val keyFile = repo.resolve("keys").listFiles()!!.single()
        val json = JSONObject(keyFile.readText())
        val master = ResticKeyFile.open(json, "correct horse".toByteArray())
        assertNotNull("restic's key file did not open", master)
        assertNull(ResticKeyFile.open(json, "wrong".toByteArray()))
        // The config is encrypted under the master key: it must decrypt to restic's JSON.
        val config = JSONObject(String(master!!.decrypt(repo.resolve("config").readBytes()), Charsets.UTF_8))
        assertEquals(2, config.getInt("version"))
        assertNotNull(config.getString("chunker_polynomial"))
        repo.deleteRecursively()
    }

    @Test
    fun `restic opens a key file and config this code wrote`() {
        val restic = restic(); assumeTrue("restic not installed", restic != null)
        val repo = Files.createTempDirectory("restic-ours").toFile()
        for (d in listOf("keys", "snapshots", "index", "locks", "data")) repo.resolve(d).mkdirs()
        for (i in 0 until 256) repo.resolve("data/%02x".format(i)).mkdirs()
        val master = ResticKey.random()
        val config = JSONObject().put("version", 2).put("id", "00".repeat(32).replaceRange(0, 4, "abcd"))
            .put("chunker_polynomial", "3dea92648f6e83")
        repo.resolve("config").writeBytes(master.encrypt(config.toString().toByteArray()))
        val key = ResticKeyFile.create(master, "battery staple".toByteArray(), "otg", "phone", "2026-10-30T12:00:00Z",
            n = 1 shl 14)
        val bytes = key.toString().toByteArray()
        repo.resolve("keys/" + sha256Hex(bytes)).writeBytes(bytes)
        val out = run(restic!!, "-r", repo.path, "cat", "config", password = "battery staple")
        assertEquals(2, JSONObject(out.substring(out.indexOf('{'))).getInt("version"))
        repo.deleteRecursively()
    }

    private fun sha256Hex(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b)
        .joinToString("") { "%02x".format(it) }
}
