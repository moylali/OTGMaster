package app.fayaz.otgmaster

import org.junit.Assert.assertEquals
import org.junit.Test

/** The UI log and the report keep 200 time-stamped lines, with repeats folded into one. */
class LogFoldingTest {
    private val t0 = java.util.Calendar.getInstance().apply { set(2026, 9, 30, 12, 1, 3) }.timeInMillis

    @Test
    fun `a repeated line is counted, not added again`() {
        val list = mutableListOf<String>()
        OtgMasterState.appendFolded(list, "Found 1 USB devices.", 200, t0)
        repeat(9) { OtgMasterState.appendFolded(list, "Found 1 USB devices.", 200, t0 + (it + 1) * 1000L) }
        assertEquals(listOf("12:01:03 Found 1 USB devices. (repeated ×10, last 12:01:12)"), list)
        OtgMasterState.appendFolded(list, "USB device detached.", 200, t0 + 20_000)
        OtgMasterState.appendFolded(list, "Found 1 USB devices.", 200, t0 + 21_000)
        assertEquals(3, list.size)
        assertEquals("12:01:24 Found 1 USB devices.", list.last())
    }

    @Test
    fun `the oldest lines go once the limit is reached`() {
        val list = mutableListOf<String>()
        (1..250).forEach { OtgMasterState.appendFolded(list, "line $it", 200, t0) }
        assertEquals(200, list.size)
        assertEquals("12:01:03 line 51", list.first())
        assertEquals("12:01:03 line 250", list.last())
    }
}
