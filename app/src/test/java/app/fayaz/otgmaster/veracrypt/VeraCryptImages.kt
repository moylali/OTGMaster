package app.fayaz.otgmaster.veracrypt

import app.fayaz.otgmaster.block.RawBlockDevice
import java.io.File
import java.io.RandomAccessFile

/** Volumes made by desktop VeraCrypt for host tests, and a file-backed block device to open them. */
object VeraCryptImages {
    private val repo: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "scripts/build_host_native.sh").exists() }

    /** True once the host native library is built and desktop VeraCrypt is present. */
    val ready: Boolean by lazy {
        if (ProcessBuilder("sh", "-c", "command -v gcc && command -v veracrypt").start().waitFor() != 0) return@lazy false
        val lib = File(repo, "app/build/host-native/libotg-host.so")
        val p = ProcessBuilder(File(repo, "scripts/build_host_native.sh").path, lib.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor() != 0) throw AssertionError("host native build failed:\n$out")
        System.setProperty("otg.native.hostlib", lib.absolutePath)
        true
    }

    /** Creates a VeraCrypt volume of [size] (e.g. "8M") in [file], AES/SHA-512, PIM 1, no filesystem. */
    fun create(file: File, size: String, password: String, type: String = "normal") {
        val p = ProcessBuilder("veracrypt", "-t", "-c", "--volume-type=$type", file.path, "--size=$size",
            "--password=$password", "--encryption=AES", "--hash=SHA-512", "--filesystem=none", "--pim=1",
            "--random-source=/dev/urandom", "--non-interactive").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "veracrypt failed: $out" }
    }

    /** Overwrites [length] bytes at [offset] with a fixed pattern. */
    fun clobber(file: File, offset: Long, length: Int) =
        RandomAccessFile(file, "rw").use { it.seek(offset); it.write(ByteArray(length) { 0x5A }) }

    class ImageDevice(file: File) : RawBlockDevice {
        private val raf = RandomAccessFile(file, "rw")
        override val blockSize = 512
        override val blockCount = file.length() / 512
        override fun readBlocks(startBlock: Long, blockCount: Int): ByteArray {
            val out = ByteArray(blockCount * 512); raf.seek(startBlock * 512); raf.readFully(out); return out
        }
        override fun writeBlocks(startBlock: Long, data: ByteArray) { raf.seek(startBlock * 512); raf.write(data) }
        override fun close() = raf.close()
    }

    fun candidate(dev: RawBlockDevice) = VolumeCandidate("image", 0, dev.blockCount, ContainerType.VERACRYPT)
}
