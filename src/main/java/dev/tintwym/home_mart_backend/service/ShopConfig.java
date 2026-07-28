package dev.tintwym.home_mart_backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mirrors config/shop.php from the Laravel app.
 */
public final class ShopConfig {

    public record Currency(String code, String symbol, int decimals) {
    }

    public record Location(String name, double lat, double lng) {
    }

    public static final Map<String, Currency> CURRENCIES;
    public static final Currency DEFAULT_CURRENCY = new Currency("USD", "$", 2);
    public static final Map<String, BigDecimal> EXCHANGE_RATES;
    public static final List<String> REGIONS = List.of("MM", "SG", "US", "VN");
    public static final Map<String, String> REGION_LABELS;
    public static final Map<String, List<Location>> REGION_LOCATIONS;
    public static final Map<String, String> REGION_LOCALES;
    public static final List<String> SUPPORTED_LOCALES = List.of("en", "zh", "my", "ja", "vi");

    public static final int TREND_DURATION_DAYS = 7;
    public static final BigDecimal TREND_PRICE = new BigDecimal("10");
    public static final BigDecimal SLOT_PRICE = new BigDecimal("5");
    public static final int LISTING_LIMIT_INDIVIDUAL = 999_999;
    public static final int LISTING_LIMIT_BUSINESS = 999_999;

    public static int baseListingSlots(String sellerType) {
        if ("business".equalsIgnoreCase(sellerType)) {
            return LISTING_LIMIT_BUSINESS;
        }
        return LISTING_LIMIT_INDIVIDUAL;
    }

    public static int maxListingSlots(String sellerType, Integer extraSlots) {
        return baseListingSlots(sellerType) + (extraSlots == null ? 0 : extraSlots);
    }

    public static String localPaymentTypeLabel(String type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case "mpu" -> "MPU";
            case "kbz_pay" -> "KBZ Pay";
            case "aya_pay" -> "AYA Pay";
            case "wave_pay" -> "Wave Pay";
            case "cb_pay" -> "CB Pay";
            case "momo" -> "MoMo";
            case "zalopay" -> "ZaloPay";
            case "shopeepay" -> "ShopeePay";
            case "vietqr" -> "VietQR";
            case "atm_card" -> "ATM Card";
            case "bank" -> "Bank transfer";
            default -> type;
        };
    }

    public static final List<String> MYANMAR_PAYMENT_METHODS =
            List.of("mpu", "kbz_pay", "aya_pay", "wave_pay", "cb_pay");
    public static final List<String> VIETNAM_PAYMENT_METHODS =
            List.of("momo", "zalopay", "shopeepay", "vietqr", "atm_card", "bank");

    /** GPS fallback anchors (km nearest) — mirrors shop.gps_fallback_anchors. */
    public static final Map<String, Location> GPS_FALLBACK_ANCHORS;

    static {
        Map<String, Currency> currencies = new LinkedHashMap<>();
        currencies.put("SG", new Currency("SGD", "S$", 2));
        currencies.put("MM", new Currency("MMK", "Ks ", 0));
        currencies.put("US", new Currency("USD", "$", 2));
        currencies.put("VN", new Currency("VND", "₫", 0));
        CURRENCIES = Collections.unmodifiableMap(currencies);

        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        rates.put("USD", BigDecimal.ONE);
        rates.put("SGD", new BigDecimal("1.35"));
        rates.put("MMK", new BigDecimal("4500"));
        rates.put("VND", new BigDecimal("26000"));
        EXCHANGE_RATES = Collections.unmodifiableMap(rates);

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("SG", "Singapore");
        labels.put("MM", "Myanmar");
        labels.put("US", "United States");
        labels.put("VN", "Vietnam");
        REGION_LABELS = Collections.unmodifiableMap(labels);

        Map<String, List<Location>> locations = new LinkedHashMap<>();
        locations.put("MM", List.of(
                new Location("Yangon", 16.8661, 96.1951),
                new Location("Mandalay", 21.9588, 96.0891),
                new Location("Naypyidaw", 19.7475, 96.1153),
                new Location("Mawlamyine", 16.4919, 97.628),
                new Location("Taunggyi", 20.7891, 97.0378)));
        locations.put("US", List.of(
                new Location("New York", 40.7128, -74.006),
                new Location("Los Angeles", 34.0522, -118.2437),
                new Location("Chicago", 41.8781, -87.6298)));
        locations.put("SG", List.of(
                new Location("Central", 1.2789, 103.8507),
                new Location("East", 1.3248, 103.9273),
                new Location("North", 1.4382, 103.7891)));
        locations.put("VN", List.of(
                new Location("Hanoi", 21.0285, 105.8542),
                new Location("Ho Chi Minh City", 10.8231, 106.6297),
                new Location("Da Nang", 16.0544, 108.2022)));
        REGION_LOCATIONS = Collections.unmodifiableMap(locations);

        Map<String, String> locales = new LinkedHashMap<>();
        locales.put("VN", "vi");
        locales.put("MM", "my");
        locales.put("SG", "en");
        locales.put("US", "en");
        REGION_LOCALES = Collections.unmodifiableMap(locales);

        Map<String, Location> anchors = new LinkedHashMap<>();
        anchors.put("MM", new Location("MM", 16.8661, 96.1951));
        anchors.put("SG", new Location("SG", 1.2789, 103.8507));
        anchors.put("US", new Location("US", 39.8283, -98.5795));
        anchors.put("VN", new Location("VN", 14.0583, 108.2772));
        GPS_FALLBACK_ANCHORS = Collections.unmodifiableMap(anchors);
    }

    private ShopConfig() {
    }

    public static String currencyCodeForRegion(String region) {
        if (region != null && CURRENCIES.containsKey(region)) {
            return CURRENCIES.get(region).code();
        }
        return DEFAULT_CURRENCY.code();
    }

    public static BigDecimal convert(BigDecimal amount, String fromCode, String toCode) {
        if (fromCode != null && fromCode.equalsIgnoreCase(toCode)) {
            return amount;
        }
        BigDecimal fromRate = EXCHANGE_RATES.get(fromCode);
        BigDecimal toRate = EXCHANGE_RATES.get(toCode);
        if (fromRate == null || fromRate.compareTo(BigDecimal.ZERO) <= 0) {
            return amount;
        }
        if (toRate == null || toRate.compareTo(BigDecimal.ZERO) <= 0) {
            return amount;
        }
        return amount.divide(fromRate, 10, RoundingMode.HALF_UP).multiply(toRate);
    }

    public static BigDecimal convertFromUsd(BigDecimal usdAmount, String toCode) {
        return convert(usdAmount, "USD", toCode);
    }

    public static BigDecimal convertToUsd(BigDecimal amount, String fromCurrencyOrRegion) {
        if (fromCurrencyOrRegion == null || fromCurrencyOrRegion.isEmpty()) {
            return amount;
        }
        if (EXCHANGE_RATES.containsKey(fromCurrencyOrRegion)) {
            return convert(amount, fromCurrencyOrRegion, "USD");
        }
        return convert(amount, currencyCodeForRegion(fromCurrencyOrRegion), "USD");
    }

    public static String formatUsdFee(BigDecimal usdAmount, Currency display, String suffix) {
        BigDecimal converted = convertFromUsd(usdAmount, display.code());
        String rounded;
        if (display.decimals() <= 0) {
            rounded = converted.setScale(0, RoundingMode.HALF_UP).toPlainString();
        } else {
            rounded = converted.setScale(display.decimals(), RoundingMode.HALF_UP).toPlainString();
        }
        return display.symbol() + rounded + " " + suffix;
    }

    public static long toStripeAmount(BigDecimal amount, String currencyCode) {
        String code = currencyCode.toLowerCase();
        if (code.equals("vnd") || code.equals("jpy") || code.equals("krw")) {
            return amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
        }
        return amount.multiply(new BigDecimal("100")).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
