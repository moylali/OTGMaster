package app.fayaz.otgmaster.veracrypt

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * cryptsetup gives a LUKS2 key slot four lanes by default. Built without threads, Argon2 still
 * derives the right key from them, one lane after another, so a test of the key cannot see the
 * difference; 0.5.0 shipped that way and LUKS2 unlocks took more than twice as long.
 *
 * Timing would be flaky on a busy host, so this counts threads instead. Only those named after
 * the deriving thread count, which is the name a native thread inherits from the thread that
 * starts it: the JVM starts threads of its own at any moment, and counting those passed on a
 * build with no threads at all.
 */
class Argon2LanesTest {

    companion object {
        private val repo: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "scripts/build_host_native.sh").exists() }
        private var ready = false

        @BeforeClass @JvmStatic
        fun setUp() {
            if (ProcessBuilder("sh", "-c", "command -v gcc").start().waitFor() != 0) return
            val lib = File(repo, "app/build/host-native/libotg-host.so")
            val p = ProcessBuilder(File(repo, "scripts/build_host_native.sh").path, lib.path)
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (p.waitFor() != 0) throw AssertionError("host native build failed:\n$out")
            System.setProperty("otg.native.hostlib", lib.absolutePath)
            ready = true
        }
    }

    private val tasks = File("/proc/self/task")
    private val name = "argon2-lanes"

    private fun threadsNamed(): Int = tasks.listFiles().orEmpty().count { task ->
        runCatching { File(task, "comm").readText().trim() }.getOrNull() == name
    }

    @Test fun `four lanes run on four threads`() {
        assumeTrue("needs gcc and /proc", ready && tasks.exists())
        var key: ByteArray? = null
        val derive = Thread {
            Thread.currentThread().name = name
            key = VeraCryptNative.argon2id("password".toByteArray(), ByteArray(32) { 1 }, 4, 128 * 1024, 4, 64)
        }
        derive.start()
        var most = 0
        while (derive.isAlive) {
            most = maxOf(most, threadsNamed())
            Thread.sleep(1)
        }
        derive.join()
        assertNotNull(key)
        // The deriving thread itself, waiting, and one worker per lane.
        assertTrue("$most threads named $name, wanted the deriving thread and 4 workers", most >= 5)
    }
}
