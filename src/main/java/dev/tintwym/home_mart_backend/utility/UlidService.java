package dev.tintwym.home_mart_backend.utility;

import java.security.SecureRandom;

/**
 * Generates ULIDs compatible with Laravel's HasUlids trait
 * (26-char Crockford base32, stored lowercase).
 */
public final class UlidService {

    private static final String ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();

    private UlidService() {
    }

    public static String newUlid() {
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);
        long timestamp = System.currentTimeMillis();

        char[] output = new char[26];

        // 48-bit timestamp → 10 chars
        for (int i = 9; i >= 0; i--) {
            output[i] = ALPHABET.charAt((int) (timestamp & 0x1F));
            timestamp >>= 5;
        }

        // 80 bits of randomness → 16 chars
        int bitBuffer = 0;
        int bitCount = 0;
        int outIndex = 10;
        for (byte b : random) {
            bitBuffer = (bitBuffer << 8) | (b & 0xFF);
            bitCount += 8;
            while (bitCount >= 5) {
                bitCount -= 5;
                output[outIndex++] = ALPHABET.charAt((bitBuffer >> bitCount) & 0x1F);
            }
        }

        return new String(output);
    }
}
