package app.fayaz.containermaster

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/** Band arithmetic: reads across bands, missing and short bands as zeros, and Info.plist as hdiutil writes it. */
class SparseBundleDeviceTest {

    private val bandSize = 8L * 512
    private val disk = ByteArray(10 * 512 * 8).also { java.util.Random(3).nextBytes(it) }   // 10 bands

    /** The disk cut into bands, band 3 missing and band 6 cut short, as a sparse bundle leaves them. */
    private val bands: Map<String, ByteArray> = (0 until 10).filter { it != 3 }.associate { i ->
        val band = disk.copyOfRange((i * bandSize).toInt(), ((i + 1) * bandSize).toInt())
        i.toString(16) to if (i == 6) band.copyOf(1000) else band
    }

    private val expected = disk.copyOf().also {
        java.util.Arrays.fill(it, (3 * bandSize).toInt(), (4 * bandSize).toInt(), 0)
        java.util.Arrays.fill(it, (6 * bandSize + 1000).toInt(), (7 * bandSize).toInt(), 0)
    }

    private val dev = SparseBundleDevice(object : SparseBundleDevice.Files {
        override fun readBand(name: String, offset: Long, length: Int): ByteArray? {
            val b = bands[name] ?: return null
            if (offset >= b.size) return ByteArray(0)
            return b.copyOfRange(offset.toInt(), minOf(b.size.toLong(), offset + length).toInt())
        }
    }, disk.size.toLong(), bandSize)

    @Test
    fun `reads across bands, with missing and short bands as zeros`() {
        assertEquals(80L, dev.blockCount)
        assertArrayEquals(expected, dev.readBlocks(0, 80))
        for ((s, n) in listOf(7L to 3, 23L to 10, 48L to 9, 79L to 1)) {
            assertArrayEquals("at $s", expected.copyOfRange((s * 512).toInt(), ((s + n) * 512).toInt()), dev.readBlocks(s, n))
        }
        assertThrows(IllegalArgumentException::class.java) { dev.readBlocks(79, 2) }
        assertThrows(IOException::class.java) { dev.writeBlocks(0, ByteArray(512)) }
    }

    @Test
    fun `Info plist as hdiutil writes it`() {
        val plist = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleInfoDictionaryVersion</key>
	<string>6.0</string>
	<key>band-size</key>
	<integer>8388608</integer>
	<key>bundle-backingstore-version</key>
	<integer>1</integer>
	<key>diskimage-bundle-type</key>
	<string>com.apple.diskimage.sparsebundle</string>
	<key>size</key>
	<integer>107374182400</integer>
</dict>
</plist>"""
        assertEquals(107374182400L to 8388608L, SparseBundleDevice.parseInfo(plist))
        assertThrows(IOException::class.java) { SparseBundleDevice.parseInfo(plist.replace("band-size", "band")) }
        assertThrows(IOException::class.java) { SparseBundleDevice.parseInfo(plist.replace("com.apple.diskimage.sparsebundle", "other")) }
    }
}
