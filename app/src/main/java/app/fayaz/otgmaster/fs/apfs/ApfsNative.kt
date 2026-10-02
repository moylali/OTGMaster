package app.fayaz.otgmaster.fs.apfs

import androidx.annotation.Keep
import app.fayaz.otgmaster.block.RawBlockDevice
import java.nio.ByteBuffer

@Keep
object ApfsNative {
    init {
        System.loadLibrary("apfs-native")
    }

    @JvmStatic external fun mount(device: RawBlockDevice, password: String?): Long
    @JvmStatic external fun listDirectory(contextPtr: Long, path: String): Array<String>?
    @JvmStatic external fun unmount(contextPtr: Long)
    @JvmStatic external fun getFileSize(contextPtr: Long, path: String): Long
    @JvmStatic external fun isDirectory(contextPtr: Long, path: String): Boolean
    @JvmStatic external fun readFile(contextPtr: Long, path: String, offset: Long, byteBuffer: ByteBuffer, size: Int): Int
    @JvmStatic external fun getVolumeName(contextPtr: Long): String
}
