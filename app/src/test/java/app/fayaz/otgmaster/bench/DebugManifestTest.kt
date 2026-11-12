package app.fayaz.otgmaster.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Review of #141, PR-2: the debug and benchmark APKs carry receivers that copy, dump and
 * mount decrypted drives; they were exported with no permission, so any installed app could
 * trigger them. Every exported receiver in the debug manifest must require
 * android.permission.DUMP, which adb's shell holds and ordinary apps cannot be granted.
 */
class DebugManifestTest {
    private val manifest: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "app/src/debug/AndroidManifest.xml") }.first { it.exists() }

    @Test fun `every exported receiver requires the DUMP permission`() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest)
        val ns = "http://schemas.android.com/apk/res/android"
        val receivers = doc.getElementsByTagName("receiver").let { list -> (0 until list.length).map { list.item(it) as Element } }
        val exported = receivers.filter { it.getAttributeNS(ns, "exported") == "true" }
        assertTrue("no exported receivers found: is this the right manifest?", exported.isNotEmpty())
        for (r in exported) {
            assertEquals("${r.getAttributeNS(ns, "name")} is exported without the DUMP permission",
                "android.permission.DUMP", r.getAttributeNS(ns, "permission"))
        }
    }
}
