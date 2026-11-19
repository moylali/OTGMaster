package app.fayaz.otgmaster.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey

/**
 * The envelope and the migration of [LockedStore] and [CredentialStore], with a software
 * keypair in place of the keystore. What the keystore adds, refusing the private key
 * outside 30 s of the phone's lock, is Android's and is checked on a device.
 */
class LockedStoreTest {

    private class MapKv : LockedStore.Kv {
        val map = LinkedHashMap<String, String>()
        override fun all() = map.toMap()
        override fun put(name: String, value: String) { map[name] = value }
        override fun remove(name: String) { map.remove(name) }
        override fun clear() = map.clear()
    }

    /** [locked] stands in for "outside the window": the private key is refused. */
    private class SoftKeys(var available: Boolean = true) : LockedStore.Keys {
        private var pair: KeyPair? = null
        var locked = false
        override fun publicKey(): PublicKey? {
            if (!available) return null
            if (pair == null) pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            return pair!!.public
        }
        override fun privateKey(): PrivateKey? {
            if (locked) throw IllegalStateException("user not authenticated")
            return pair?.private
        }
        override fun delete() { pair = null }
    }

    @Test
    fun `a value saved with the public key opens with the private key`() {
        val kv = MapKv(); val keys = SoftKeys()
        val store = LockedStore(kv, keys)
        val name = LockedStore.nameFor("2385_5734_ABC123", 2048)
        assertTrue(store.put(name, "correct horse".toByteArray()))
        assertArrayEquals("correct horse".toByteArray(), store.get(name))
        assertFalse("the password is in the file in the clear", kv.map.values.single().contains("horse"))
    }

    @Test
    fun `saving needs no unlock, reading does`() {
        val keys = SoftKeys()
        val store = LockedStore(MapKv(), keys)
        keys.publicKey()
        keys.locked = true
        assertTrue("saving was refused while locked", store.put("d_x_sb0", "pw".toByteArray()))
        assertTrue("existence needs no unlock", store.has("d_x_sb0"))
        try {
            store.get("d_x_sb0"); fail("read while locked")
        } catch (e: IllegalStateException) { /* the keystore's UserNotAuthenticatedException */ }
    }

    @Test
    fun `no screen lock, so no key and nothing saved`() {
        val store = LockedStore(MapKv(), SoftKeys(available = false))
        assertFalse(store.put("d_x_sb0", "pw".toByteArray()))
        assertFalse(store.has("d_x_sb0"))
    }

    @Test
    fun `names hide the drive serial`() {
        val name = LockedStore.nameFor(CredentialStore.sanitize("2385:5734:ABC123"), 2048)
        assertFalse(name.contains("ABC123"))
        assertTrue(name.startsWith(LockedStore.devicePrefix(CredentialStore.sanitize("2385:5734:ABC123"))))
        assertTrue(name.endsWith("_sb2048"))
    }

    @Test
    fun `an envelope moved to another name does not open`() {
        val kv = MapKv()
        val store = LockedStore(kv, SoftKeys())
        store.put("d_a_sb0", "pw".toByteArray())
        kv.map["d_b_sb0"] = kv.map["d_a_sb0"]!!
        try {
            store.get("d_b_sb0"); fail("a moved envelope opened")
        } catch (e: javax.crypto.AEADBadTagException) { /* bound to its name */ }
    }

    @Test
    fun `passwords saved before 0_5_4 move across under their new names`() {
        val kv = MapKv()
        val store = LockedStore(kv, SoftKeys())
        val dev = CredentialStore.sanitize("2385:5734:ABC123")
        val perPartition = """{"password":"p1","startBlock":2048}"""
        val oldestFormat = """{"password":"p0","startBlock":40}"""
        val legacy = mapOf(
            "cred_${dev}_sb2048" to perPartition,
            "cred_${CredentialStore.sanitize("1:2:S9")}" to oldestFormat,
            "unrelated" to "x",
        )
        assertTrue(CredentialStore.migrate(legacy, store))
        assertEquals(perPartition, String(store.get(LockedStore.nameFor(dev, 2048))!!))
        assertEquals(oldestFormat, String(store.get(LockedStore.nameFor(CredentialStore.sanitize("1:2:S9"), 40))!!))
        assertEquals("only the two credentials", 2, kv.map.size)
    }

    @Test
    fun `without a screen lock the migration reports failure, so the old file is kept`() {
        val store = LockedStore(MapKv(), SoftKeys(available = false))
        assertFalse(CredentialStore.migrate(mapOf("cred_x_sb0" to """{"password":"p"}"""), store))
    }

    @Test
    fun `a missing value is null`() {
        assertNull(LockedStore(MapKv(), SoftKeys()).get("d_none_sb0"))
    }
}
