package app.fayaz.otgmaster.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class EngineLogTest {

    private class Recorder : EngineLog.Sink {
        val lines = mutableListOf<String>()
        override fun log(line: String) { lines += line }
        override fun logFailure(kind: String, what: String, e: Throwable) { lines += "$kind|$what|${e.message}" }
    }

    @After fun reset() = EngineLog.resetForTest()

    @Test
    fun `lines go to the installed sink in order`() {
        val r = Recorder()
        EngineLog.install(r)
        EngineLog.log("one")
        EngineLog.logFailure("exfat-io", "reading", IOException("EIO"))
        assertEquals(listOf("one", "exfat-io|reading|EIO"), r.lines)
    }

    @Test
    fun `lines logged before a sink are delivered when it is installed, oldest first`() {
        EngineLog.log("early")
        EngineLog.logFailure("detect", "no start", IOException("short read"))
        val r = Recorder()
        EngineLog.install(r)
        EngineLog.log("late")
        assertEquals(listOf("early", "detect|no start|short read", "late"), r.lines)
    }

    @Test
    fun `at most MAX_PENDING lines wait for a sink, and the newest are the ones kept`() {
        repeat(EngineLog.MAX_PENDING + 5) { EngineLog.log("line $it") }
        val r = Recorder()
        EngineLog.install(r)
        assertEquals(EngineLog.MAX_PENDING, r.lines.size)
        assertEquals("line 5", r.lines.first())
        assertEquals("line ${EngineLog.MAX_PENDING + 4}", r.lines.last())
    }
}
