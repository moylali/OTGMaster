#include <stdio.h>
#include <stdint.h>

int main() {
    FILE* f = fopen("/Users/cfayaz/Documents/Backup_codes/Backup_2.key", "rb");
    if (!f) return 1;
    
    uint8_t keyPool[64] = {0};
    uint32_t crc = 0xFFFFFFFF;
    int totalRead = 0;
    int c;
    while ((c = fgetc(f)) != EOF) {
        uint8_t octet = (uint8_t)c;
        // UPDC32 manually
        crc = crc ^ octet;
        for (int j = 0; j < 8; j++) {
            if (crc & 1) {
                crc = (crc >> 1) ^ 0xEDB88320;
            } else {
                crc = (crc >> 1);
            }
        }
        
        keyPool[totalRead % 64] += (uint8_t)(crc >> 24);
        totalRead++;
        if (totalRead >= 1048576) break;
    }
    fclose(f);
    
    for (int i=0; i<64; i++) {
        printf("%02X", keyPool[i]);
    }
    printf("\n");
    return 0;
}
