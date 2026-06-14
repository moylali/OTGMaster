import java.io.FileInputStream;
import java.io.File;

public class CRCTest {
    public static void main(String[] args) throws Exception {
        File file = new File("/Users/cfayaz/Documents/Backup_codes/Backup_2.key");
        byte[] keyfilePool = new byte[64];
        FileInputStream stream = new FileInputStream(file);
        
        long crc = 0xFFFFFFFFL;
        byte[] buffer = new byte[8192];
        int totalRead = 0;
        
        while (totalRead < 1048576) {
            int toRead = Math.min(8192, 1048576 - totalRead);
            int read = stream.read(buffer, 0, toRead);
            if (read <= 0) break;
            
            for (int i = 0; i < read; i++) {
                int byteVal = buffer[i] & 0xFF;
                crc = crc ^ byteVal;
                for (int j = 0; j < 8; j++) {
                    if ((crc & 1L) != 0L) {
                        crc = (crc >>> 1) ^ 0xEDB88320L;
                    } else {
                        crc = (crc >>> 1);
                    }
                }
                
                int poolIndex = (totalRead + i) % 64;
                long crcHighByte = (crc >>> 24) & 0xFFL;
                keyfilePool[poolIndex] = (byte)(keyfilePool[poolIndex] + crcHighByte);
            }
            totalRead += read;
        }
        stream.close();
        
        for (int i = 0; i < 64; i++) {
            System.out.printf("%02X", keyfilePool[i]);
        }
        System.out.println();
    }
}
