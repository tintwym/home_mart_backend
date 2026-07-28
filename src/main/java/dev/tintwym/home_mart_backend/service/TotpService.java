package dev.tintwym.home_mart_backend.service;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Base32;
import org.springframework.stereotype.Service;

/**
 * TOTP helper mirroring Laravel Fortify (SHA1, 6 digits, 30s step, ±1 window).
 */
@Service
public class TotpService {

    private static final String RECOVERY_CODE_ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int PERIOD_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    public String generateSecretBase32() {
        byte[] key = new byte[20];
        RANDOM.nextBytes(key);
        Base32 base32 = new Base32();
        return base32.encodeToString(key).replace("=", "").toUpperCase();
    }

    public boolean verifyCode(String base32Secret, String code) {
        if (code == null) {
            return false;
        }
        code = code.replace(" ", "").trim();
        if (code.isEmpty()) {
            return false;
        }

        byte[] secret;
        try {
            secret = decodeBase32(base32Secret);
        } catch (IllegalArgumentException e) {
            return false;
        }

        long timestep = Instant.now().getEpochSecond() / PERIOD_SECONDS;
        for (long drift = -1; drift <= 1; drift++) {
            String expected = generateTotp(secret, timestep + drift);
            if (constantTimeEquals(expected, code)) {
                return true;
            }
        }
        return false;
    }

    public String buildOtpAuthUri(String issuer, String account, String base32Secret) {
        String label = urlEncode(issuer) + ":" + urlEncode(account);
        return "otpauth://totp/" + label
                + "?secret=" + base32Secret
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=6&period=30";
    }

    public java.util.List<String> generateRecoveryCodes(int n) {
        java.util.List<String> codes = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            codes.add(randomSegment(10) + "-" + randomSegment(10));
        }
        return codes;
    }

    public java.util.List<String> generateRecoveryCodes() {
        return generateRecoveryCodes(8);
    }

    private static String randomSegment(int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = RECOVERY_CODE_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_CODE_ALPHABET.length()));
        }
        return new String(chars);
    }

    private static byte[] decodeBase32(String base32Secret) {
        String cleaned = base32Secret.replace(" ", "").toUpperCase();
        Base32 base32 = new Base32();
        return base32.decode(cleaned);
    }

    private static String generateTotp(byte[] secret, long timestep) {
        try {
            byte[] data = ByteBuffer.allocate(8).putLong(timestep).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int otp = binary % (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", otp);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP generation failed", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
