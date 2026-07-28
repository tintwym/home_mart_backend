package dev.tintwym.home_mart_backend.service;

import dev.tintwym.home_mart_backend.config.AppProperties;
import dev.tintwym.home_mart_backend.filter.JwtAuthenticationFilter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class AuthCookieService {

    public static final String TOKEN_COOKIE = JwtAuthenticationFilter.COOKIE_NAME;
    public static final String TWO_FA_PENDING_COOKIE = "hm_2fa_pending";
    private static final int MAX_AGE_SECONDS = 30 * 24 * 60 * 60;

    private final AppProperties appProperties;
    private final JwtService jwtService;

    public AuthCookieService(AppProperties appProperties, JwtService jwtService) {
        this.appProperties = appProperties;
        this.jwtService = jwtService;
    }

    public void setTokenCookie(HttpServletResponse response, String token) {
        ResponseCookie cookie = ResponseCookie.from(TOKEN_COOKIE, token)
                .httpOnly(true)
                .secure(isSecure())
                .path("/")
                .maxAge(MAX_AGE_SECONDS)
                .sameSite("Lax")
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    public void clearTokenCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(TOKEN_COOKIE, "")
                .httpOnly(true)
                .secure(isSecure())
                .path("/")
                .maxAge(0)
                .sameSite("Lax")
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    public void setTwoFactorPending(HttpServletResponse response, String userId) {
        String signed = sign(userId);
        ResponseCookie cookie = ResponseCookie.from(TWO_FA_PENDING_COOKIE, signed)
                .httpOnly(true)
                .secure(isSecure())
                .path("/")
                .maxAge(600)
                .sameSite("Lax")
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    public void clearTwoFactorPending(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(TWO_FA_PENDING_COOKIE, "")
                .httpOnly(true)
                .secure(isSecure())
                .path("/")
                .maxAge(0)
                .sameSite("Lax")
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    public Optional<String> readTwoFactorPendingUserId(HttpServletRequest request) {
        String raw = cookieValue(request, TWO_FA_PENDING_COOKIE);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return verify(raw);
    }

    private String sign(String userId) {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(userId.getBytes(StandardCharsets.UTF_8));
        String sig = hmac(payload);
        return payload + "." + sig;
    }

    private Optional<String> verify(String signed) {
        int dot = signed.lastIndexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        String payload = signed.substring(0, dot);
        String sig = signed.substring(dot + 1);
        String expected = hmac(payload);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                sig.getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(payload);
            return Optional.of(new String(decoded, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private String hmac(String payload) {
        try {
            String key = signingKey();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign cookie", e);
        }
    }

    private String signingKey() {
        return jwtService.getSigningKeyString();
    }

    private boolean isSecure() {
        String url = appProperties.getUrl();
        return url != null && url.startsWith("https");
    }

    /** Preference cookies: shop_region, shop_currency, locale, currency_manual, locale_manual. */
    public void setPreferenceCookie(HttpServletResponse response, String name, String value, int maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from(name, value == null ? "" : value)
                .httpOnly(false)
                .secure(isSecure())
                .path("/")
                .maxAge(maxAgeSeconds)
                .sameSite("Lax")
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    public void setShopRegion(HttpServletResponse response, String region) {
        setPreferenceCookie(response, "shop_region", region, 365 * 24 * 60 * 60);
    }

    public void setShopCurrency(HttpServletResponse response, String currency) {
        setPreferenceCookie(response, "shop_currency", currency, 365 * 24 * 60 * 60);
        setPreferenceCookie(response, "currency_manual", "1", 365 * 24 * 60 * 60);
    }

    public void setLocale(HttpServletResponse response, String locale, boolean manual) {
        setPreferenceCookie(response, "locale", locale, 365 * 24 * 60 * 60);
        if (manual) {
            setPreferenceCookie(response, "locale_manual", "1", 365 * 24 * 60 * 60);
        }
    }

    public boolean isLocaleManual(HttpServletRequest request) {
        String v = cookieValue(request, "locale_manual");
        return "1".equals(v) || "true".equalsIgnoreCase(v);
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
}
