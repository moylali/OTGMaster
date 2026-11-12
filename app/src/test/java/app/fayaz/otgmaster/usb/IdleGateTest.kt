package app.fayaz.otgmaster.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review of #141, follow-up: a short pause between two files of a copy is not "finished". */
class IdleGateTest {
    @Test fun `a short gap between files does not open the gate`() {
        val g = IdleGate(5_000)
        assertFalse(g.ready(busy = true, now = 0))
        assertFalse("idle for 4 s counted as finished", g.ready(busy = false, now = 4_000))
        assertFalse(g.ready(busy = true, now = 4_500))         // the next file
        assertFalse("the quiet spell did not restart", g.ready(busy = false, now = 8_000))
        assertFalse(g.ready(busy = false, now = 12_000))
    }

    @Test fun `a continuous quiet spell opens it`() {
        val g = IdleGate(5_000)
        assertFalse(g.ready(busy = false, now = 1_000))
        assertTrue(g.ready(busy = false, now = 6_000))
    }
}
