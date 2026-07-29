package dev.tintwym.home_mart_backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;

/**
 * Builds EMVCo merchant-presented QR payloads for Myanmar (MMQR) and Vietnam (VNQR / VietQR).
 */
public final class EmvQrService {

    private EmvQrService() {
    }

    /** ISO 4217 numeric: MMK. */
    public static final String CURRENCY_MMK = "104";
    /** ISO 4217 numeric: VND. */
    public static final String CURRENCY_VND = "704";

    /**
     * VietQR / VNQR (NAPAS) dynamic payload — tag 38 GUID A000000727.
     *
     * @param amountWhole whole VND units (no decimals)
     */
    public static String buildVnqr(
            String bankBin,
            String accountNumber,
            BigDecimal amountWhole,
            String billNumber,
            String purpose,
            String merchantName,
            String merchantCity,
            String mcc) {
        String bin = nullToEmpty(bankBin);
        String account = nullToEmpty(accountNumber);
        String name = truncate(nullToEmpty(merchantName), 25);
        String city = truncate(nullToEmpty(merchantCity), 15);
        String category = (mcc == null || mcc.isBlank()) ? "0000" : mcc.trim();

        String beneficiary = tlv("00", bin) + tlv("01", account);
        String merchantAccount =
                tlv("00", "A000000727")
                        + tlv("01", beneficiary)
                        + tlv("02", "QRIBFTTA");

        StringBuilder payload = new StringBuilder();
        payload.append(tlv("00", "01"));
        payload.append(tlv("01", "12")); // dynamic
        payload.append(tlv("38", merchantAccount));
        payload.append(tlv("52", category));
        payload.append(tlv("53", CURRENCY_VND));
        if (amountWhole != null && amountWhole.compareTo(BigDecimal.ZERO) > 0) {
            payload.append(tlv("54", amountWhole.setScale(0, RoundingMode.HALF_UP).toPlainString()));
        }
        payload.append(tlv("58", "VN"));
        payload.append(tlv("59", name.isEmpty() ? "HOME MART" : name));
        payload.append(tlv("60", city.isEmpty() ? "HANOI" : city));

        StringBuilder additional = new StringBuilder();
        if (billNumber != null && !billNumber.isBlank()) {
            additional.append(tlv("01", truncate(billNumber.trim(), 25)));
        }
        if (purpose != null && !purpose.isBlank()) {
            additional.append(tlv("08", truncate(purpose.trim(), 25)));
        }
        if (!additional.isEmpty()) {
            payload.append(tlv("62", additional.toString()));
        }

        return withCrc(payload.toString());
    }

    /**
     * MMQR (MyanmarPay) EMVCo dynamic payload.
     * Merchant account uses configurable GUID + merchant id (tag 26).
     */
    public static String buildMmqr(
            String guid,
            String merchantId,
            BigDecimal amountWhole,
            String billNumber,
            String purpose,
            String merchantName,
            String merchantCity,
            String mcc) {
        String aid = (guid == null || guid.isBlank()) ? "A0000006150001" : guid.trim();
        String mid = nullToEmpty(merchantId);
        String name = truncate(nullToEmpty(merchantName), 25);
        String city = truncate(nullToEmpty(merchantCity), 15);
        String category = (mcc == null || mcc.isBlank()) ? "0000" : mcc.trim();

        String merchantAccount = tlv("00", aid) + tlv("01", mid.isEmpty() ? "HOMEMART" : mid);

        StringBuilder payload = new StringBuilder();
        payload.append(tlv("00", "01"));
        payload.append(tlv("01", "12"));
        payload.append(tlv("26", merchantAccount));
        payload.append(tlv("52", category));
        payload.append(tlv("53", CURRENCY_MMK));
        if (amountWhole != null && amountWhole.compareTo(BigDecimal.ZERO) > 0) {
            payload.append(tlv("54", amountWhole.setScale(0, RoundingMode.HALF_UP).toPlainString()));
        }
        payload.append(tlv("58", "MM"));
        payload.append(tlv("59", name.isEmpty() ? "HOME MART" : name));
        payload.append(tlv("60", city.isEmpty() ? "YANGON" : city));

        StringBuilder additional = new StringBuilder();
        if (billNumber != null && !billNumber.isBlank()) {
            additional.append(tlv("01", truncate(billNumber.trim(), 25)));
        }
        if (purpose != null && !purpose.isBlank()) {
            additional.append(tlv("08", truncate(purpose.trim(), 25)));
        }
        if (!additional.isEmpty()) {
            payload.append(tlv("62", additional.toString()));
        }

        return withCrc(payload.toString());
    }

    public static String tlv(String id, String value) {
        if (value == null) {
            value = "";
        }
        if (value.length() > 99) {
            value = value.substring(0, 99);
        }
        return id + String.format("%02d", value.length()) + value;
    }

    public static String withCrc(String payloadWithoutCrc) {
        String withId = payloadWithoutCrc + "6304";
        String crc = crc16Ccitt(withId);
        return withId + crc;
    }

    /** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF) — EMVCo QR. */
    public static String crc16Ccitt(String data) {
        int crc = 0xFFFF;
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc <<= 1;
                }
                crc &= 0xFFFF;
            }
        }
        return String.format("%04X", crc);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }
}
