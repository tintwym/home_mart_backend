package dev.tintwym.home_mart_backend.service;

import dev.tintwym.home_mart_backend.config.AppProperties;
import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.utility.CryptoSupport;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Shared email-verification link format used by both Inertia pages and /api.
 * Link: {frontend}/email/verify/{id}/{sha256(email)}?expires=&signature=
 * signature = HMAC-SHA256(signingKey, "{id}.{hash}.{expires}")
 */
@Service
public class EmailVerificationService {

    private final AppProperties appProperties;
    private final JwtService jwtService;
    private final EmailService emailService;

    public EmailVerificationService(
            AppProperties appProperties, JwtService jwtService, EmailService emailService) {
        this.appProperties = appProperties;
        this.jwtService = jwtService;
        this.emailService = emailService;
    }

    public String signingKey() {
        return jwtService.getSigningKeyString();
    }

    public String emailHash(String email) {
        return CryptoSupport.sha256Hex(email);
    }

    public String sign(String userId, String hash, long expires) {
        return CryptoSupport.hmacSha256Hex(signingKey(), userId + "." + hash + "." + expires);
    }

    public boolean isValid(String userId, String email, String hash, long expires, String signature) {
        if (Instant.now().getEpochSecond() > expires) {
            return false;
        }
        String expectedHash = emailHash(email);
        if (!CryptoSupport.constantTimeEquals(expectedHash, hash)) {
            return false;
        }
        String expectedSig = sign(userId, hash, expires);
        return CryptoSupport.constantTimeEquals(expectedSig, signature);
    }

    public String buildLink(User user) {
        long expires = Instant.now().getEpochSecond() + 3600;
        String hash = emailHash(user.getEmail());
        String signature = sign(user.getId(), hash, expires);
        String base = frontendBase();
        return base
                + "/email/verify/"
                + user.getId()
                + "/"
                + hash
                + "?expires="
                + expires
                + "&signature="
                + URLEncoder.encode(signature, StandardCharsets.UTF_8);
    }

    public void sendVerificationEmail(User user) {
        String link = buildLink(user);
        emailService.send(
                user.getEmail(),
                "Verify your email",
                "Click to verify your email: " + link,
                "<p>Click to verify your email:</p><p><a href=\"" + link + "\">Verify Email</a></p>");
    }

    private String frontendBase() {
        // Prefer app URL (Inertia pages live on the API host via Vite proxy / Vercel rewrite).
        if (appProperties.getUrl() != null && !appProperties.getUrl().isBlank()) {
            return trimSlash(appProperties.getUrl());
        }
        if (appProperties.getFrontendUrl() != null && !appProperties.getFrontendUrl().isBlank()) {
            return trimSlash(appProperties.getFrontendUrl());
        }
        return "http://localhost:3000";
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
