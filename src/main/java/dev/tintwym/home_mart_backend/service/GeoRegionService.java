package dev.tintwym.home_mart_backend.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.tintwym.home_mart_backend.config.AppProperties;
import dev.tintwym.home_mart_backend.config.ShopProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Detects shop region: cookie shop_region → GPS header/cookie → IP API → timezone.
 * Regions: MM, SG, US, VN.
 */
@Service
public class GeoRegionService {

    private static final Logger log = LoggerFactory.getLogger(GeoRegionService.class);
    public static final List<String> SUPPORTED_REGIONS = List.of("MM", "SG", "US", "VN");

    private static final Map<String, String> TIMEZONE_TO_REGION = Map.of(
            "Asia/Singapore", "SG",
            "Asia/Yangon", "MM",
            "Asia/Rangoon", "MM",
            "Asia/Ho_Chi_Minh", "VN",
            "Asia/Saigon", "VN");

    private static final Map<String, String> COUNTRY_TO_REGION = Map.ofEntries(
            Map.entry("MM", "MM"),
            Map.entry("SG", "SG"),
            Map.entry("US", "US"),
            Map.entry("VN", "VN"),
            Map.entry("MY", "SG"),
            Map.entry("TH", "SG"),
            Map.entry("PH", "SG"),
            Map.entry("ID", "SG"),
            Map.entry("BN", "SG"),
            Map.entry("KH", "SG"),
            Map.entry("LA", "SG"),
            Map.entry("TL", "SG"),
            Map.entry("HK", "SG"),
            Map.entry("MO", "SG"),
            Map.entry("TW", "SG"));

    private final ShopProperties shopProperties;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public GeoRegionService(
            ShopProperties shopProperties, AppProperties appProperties, ObjectMapper objectMapper) {
        this.shopProperties = shopProperties;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create();
    }

    public String detect(HttpServletRequest request) {
        String fromPicker = fromShopRegionCookie(request);
        if (fromPicker != null) {
            return fromPicker;
        }

        if (shopProperties.isGpsRegionEnabled()) {
            String fromGps = fromGps(request);
            if (fromGps != null) {
                return fromGps;
            }
        }

        String ip = clientIp(request);
        if (isPrivateOrLocalIp(ip)) {
            String fromTz = fromTimezone(request);
            if (fromTz != null) {
                return fromTz;
            }
            return shopProperties.getDefaultRegionPrivate();
        }

        return regionFromPublicIp(ip);
    }

    private String fromShopRegionCookie(HttpServletRequest request) {
        String raw = cookieValue(request, "shop_region");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String code = raw.trim().toUpperCase();
        return SUPPORTED_REGIONS.contains(code) ? code : null;
    }

    private String fromGps(HttpServletRequest request) {
        String raw = request.getHeader("X-User-Gps");
        if (raw == null || raw.isBlank()) {
            raw = cookieValue(request, "user_gps");
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        raw = URLDecoder.decode(raw.trim(), StandardCharsets.UTF_8);
        String[] parts = raw.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            double lat = Double.parseDouble(parts[0].trim());
            double lng = Double.parseDouble(parts[1].trim());
            if (!areValidCoordinates(lat, lng)) {
                return null;
            }
            return regionFromCoordinates(lat, lng);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean areValidCoordinates(double lat, double lng) {
        if (lat < -90.0 || lat > 90.0 || lng < -180.0 || lng > 180.0) {
            return false;
        }
        return !(Math.abs(lat) < 1e-6 && Math.abs(lng) < 1e-6);
    }

    private String regionFromCoordinates(double lat, double lng) {
        String cacheKey = "gps:" + round3(lat) + ":" + round3(lng);
        String cached = getCached(cacheKey);
        if (cached != null) {
            return cached;
        }

        String cc = reverseGeocodeCountryCode(lat, lng);
        String region;
        if (cc != null && !cc.isBlank()) {
            region = mapCountryCodeToRegion(cc.toUpperCase());
        } else {
            region = regionFromNearestAnchor(lat, lng);
        }
        putCached(cacheKey, region, Duration.ofHours(6));
        return region;
    }

    private String reverseGeocodeCountryCode(double lat, double lng) {
        try {
            String ua = (appProperties.getName() + " " + appProperties.getUrl()).trim();
            if (ua.isBlank()) {
                ua = "HomeMart/1.0";
            }
            String body = restClient.get()
                    .uri(URI.create(
                            "https://nominatim.openstreetmap.org/reverse?lat="
                                    + lat + "&lon=" + lng + "&format=json"))
                    .header("User-Agent", ua)
                    .header("Accept-Language", "en")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            if (body == null) {
                return null;
            }
            JsonNode root = objectMapper.readTree(body);
            JsonNode cc = root.path("address").path("country_code");
            return cc.isMissingNode() || cc.isNull() ? null : cc.asString();
        } catch (Exception e) {
            log.debug("Nominatim reverse geocode failed: {}", e.getMessage());
            return null;
        }
    }

    private String regionFromNearestAnchor(double lat, double lng) {
        String best = null;
        double bestKm = Double.MAX_VALUE;
        for (Map.Entry<String, ShopConfig.Location> e : ShopConfig.GPS_FALLBACK_ANCHORS.entrySet()) {
            double km = haversineKm(lat, lng, e.getValue().lat(), e.getValue().lng());
            if (km < bestKm) {
                bestKm = km;
                best = e.getKey();
            }
        }
        return best != null ? best : shopProperties.getDefaultRegion();
    }

    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double earth = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * earth * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    private String mapCountryCodeToRegion(String countryCode) {
        return COUNTRY_TO_REGION.getOrDefault(countryCode, "US");
    }

    private String fromTimezone(HttpServletRequest request) {
        String tz = cookieValue(request, "user_timezone");
        if (tz == null || tz.isBlank()) {
            return null;
        }
        tz = URLDecoder.decode(tz, StandardCharsets.UTF_8);
        return TIMEZONE_TO_REGION.get(tz);
    }

    private String regionFromPublicIp(String ip) {
        String cacheKey = "ip:" + ip;
        String cached = getCached(cacheKey);
        if (cached != null) {
            return cached;
        }
        String countryCode = fetchCountryCode(ip);
        String region;
        if (countryCode == null || countryCode.isBlank()) {
            region = shopProperties.getDefaultRegion();
        } else {
            region = mapCountryCodeToRegion(countryCode.toUpperCase());
        }
        putCached(cacheKey, region, Duration.ofDays(1));
        return region;
    }

    private String fetchCountryCode(String ip) {
        try {
            String body = restClient.get()
                    .uri(URI.create("http://ip-api.com/json/" + ip + "?fields=countryCode"))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            if (body == null) {
                return null;
            }
            JsonNode root = objectMapper.readTree(body);
            JsonNode cc = root.path("countryCode");
            return cc.isMissingNode() || cc.isNull() ? null : cc.asString();
        } catch (Exception e) {
            log.debug("IP geolocation failed: {}", e.getMessage());
            return null;
        }
    }

    public static boolean isPrivateOrLocalIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return true;
        }
        if ("127.0.0.1".equals(ip) || "::1".equals(ip)) {
            return true;
        }
        if (ip.startsWith("10.")) {
            return true;
        }
        if (ip.startsWith("192.168.")) {
            return true;
        }
        if (ip.matches("^172\\.(1[6-9]|2\\d|3[0-1])\\..*")) {
            return true;
        }
        return false;
    }

    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private static String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static String round3(double v) {
        return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP).toPlainString();
    }

    private String getCached(String key) {
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() > entry.expiresAt) {
            cache.remove(key);
            return null;
        }
        return entry.value;
    }

    private void putCached(String key, String value, Duration ttl) {
        cache.put(key, new CacheEntry(value, System.currentTimeMillis() + ttl.toMillis()));
    }

    private record CacheEntry(String value, long expiresAt) {
    }
}
