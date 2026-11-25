package app.fayaz.otgmaster.veracrypt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Review of #141 (PR-4): a one-password request on several partitions tried hidden headers
 * only if nothing at all had mounted, so a password that opened a standard volume on A and a
 * hidden volume on B left B locked; a LUKS or BitLocker match did the same.
 */
class MountPlanTest {
    private fun vc(start: Long) = VolumeCandidate("p$start", start, 100, ContainerType.VERACRYPT)
    private val luks = VolumeCandidate("luks", 900, 100, ContainerType.LUKS2)

    @Test fun `a standard match on one partition does not stop another's hidden pass`() {
        val a = vc(10); val b = vc(20)
        val mounted = setOf(a)
        val pass = MountPlan.hiddenPass(listOf("usb" to a, "usb" to b), hiddenHeaders = true) { _, c -> c !in mounted }
        assertEquals(listOf("usb" to b), pass)
    }

    @Test fun `a LUKS match does not stop a VeraCrypt hidden pass, and LUKS has none`() {
        val b = vc(20)
        val mounted = setOf(luks)
        val pass = MountPlan.hiddenPass(listOf("usb" to luks, "hub" to b), hiddenHeaders = true) { _, c -> c !in mounted }
        assertEquals(listOf("hub" to b), pass)
    }

    @Test fun `standard headers only means no hidden pass`() {
        assertEquals(emptyList<Pair<String, VolumeCandidate>>(),
            MountPlan.hiddenPass(listOf("usb" to vc(10)), hiddenHeaders = false) { _, _ -> true })
    }
}
