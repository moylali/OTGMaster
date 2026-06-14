import java.io.File

fun main() {
    val keyfiles = listOf(File("/Users/cfayaz/Documents/Backup_codes/Backup_2.key"))
    val keyfilePool = ByteArray(64)
    for (file in keyfiles) {
        val stream = file.inputStream()
        var crc = 0xFFFFFFFFL
        val buffer = ByteArray(8192)
        var totalRead = 0
        
        while (totalRead < 1048576) {
            val toRead = Math.min(8192, 1048576 - totalRead)
            val read = stream.read(buffer, 0, toRead)
            if (read <= 0) break
            
            for (i in 0 until read) {
                val byteVal = buffer[i].toInt() and 0xFF
                crc = crc xor byteVal.toLong()
                for (j in 0..7) {
                    crc = if ((crc and 1L) != 0L) {
                        (crc ushr 1) xor 0xEDB88320L
                    } else {
                        crc ushr 1
                    }
                }
                
                val poolIndex = (totalRead + i) % 64
                val crcHighByte = (crc ushr 24) and 0xFF
                keyfilePool[poolIndex] = (keyfilePool[poolIndex].toInt() + crcHighByte.toInt()).toByte()
            }
            totalRead += read
        }
        stream.close()
    }
    println(keyfilePool.joinToString("") { String.format("%02X", it) })
}
