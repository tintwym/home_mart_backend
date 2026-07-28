package dev.tintwym.home_mart_backend.service;

import dev.tintwym.home_mart_backend.config.JwtProperties;
import dev.tintwym.home_mart_backend.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    public static final String CLAIM_NAME_IDENTIFIER =
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/nameidentifier";
    public static final String CLAIM_NAME =
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/name";
    public static final String CLAIM_EMAIL =
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress";

    private final JwtProperties jwtProperties;

    public JwtService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    public String createToken(User user) {
        Instant now = Instant.now();
        Instant exp = now.plus(30, ChronoUnit.DAYS);

        return Jwts.builder()
                .issuer(jwtProperties.getIssuer())
                .audience().add(jwtProperties.getAudience()).and()
                .claim(CLAIM_NAME_IDENTIFIER, user.getId())
                .claim(CLAIM_NAME, user.getName())
                .claim(CLAIM_EMAIL, user.getEmail())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(getSigningKey())
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .requireIssuer(jwtProperties.getIssuer())
                .requireAudience(jwtProperties.getAudience())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String getUserId(Claims claims) {
        Object value = claims.get(CLAIM_NAME_IDENTIFIER);
        return value == null ? null : String.valueOf(value);
    }

    public String getName(Claims claims) {
        Object value = claims.get(CLAIM_NAME);
        return value == null ? null : String.valueOf(value);
    }

    public String getEmail(Claims claims) {
        Object value = claims.get(CLAIM_EMAIL);
        return value == null ? null : String.valueOf(value);
    }

    public SecretKey getSigningKey() {
        String key = getSigningKeyString();
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            // HMAC-SHA keys should be >= 256 bits; pad deterministically for short secrets.
            byte[] padded = new byte[32];
            System.arraycopy(bytes, 0, padded, 0, bytes.length);
            for (int i = bytes.length; i < 32; i++) {
                padded[i] = (byte) (bytes[i % bytes.length] ^ i);
            }
            bytes = padded;
        }
        return Keys.hmacShaKeyFor(bytes);
    }

    public String getSigningKeyString() {
        String env = System.getenv("JWT_KEY");
        if (env != null && !env.isBlank()) {
            return env;
        }
        String configured = jwtProperties.getKey();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "Set jwt.key in application.yml or the JWT_KEY environment variable.");
        }
        return configured;
    }
}
