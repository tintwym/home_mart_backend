package dev.tintwym.home_mart_backend.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies Firebase ID tokens using Google's public certificates / JWKS when
 * the Admin SDK is not initialized (no service-account JSON).
 */
@Component
public class FirebaseIdTokenJwksVerifier {

    private static final Logger log = LoggerFactory.getLogger(FirebaseIdTokenJwksVerifier.class);
    private static final String X509_URL =
            "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com";
    private static final String JWK_URL =
            "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ConcurrentHashMap<String, PublicKey> keysByKid = new ConcurrentHashMap<>();
    private volatile Instant keysExpireAt = Instant.EPOCH;

    public FirebaseIdTokenJwksVerifier(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Claims verify(String idToken, String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalStateException("FIREBASE_PROJECT_ID is not set.");
        }
        try {
            refreshKeysIfNeeded();
            String kid = readKid(idToken);
            PublicKey key = keysByKid.get(kid);
            if (key == null) {
                keysExpireAt = Instant.EPOCH;
                refreshKeysIfNeeded();
                key = keysByKid.get(kid);
            }
            if (key == null) {
                throw new IllegalArgumentException("Unknown Firebase signing key (kid=" + kid + ").");
            }

            String issuer = "https://securetoken.google.com/" + projectId.trim();
            return Jwts.parser()
                    .verifyWith(key)
                    .requireAudience(projectId.trim())
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(idToken.trim())
                    .getPayload();
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Firebase ID token: " + e.getMessage(), e);
        }
    }

    private String readKid(String idToken) throws Exception {
        String[] parts = idToken.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Malformed Firebase ID token.");
        }
        byte[] headerJson = Base64.getUrlDecoder().decode(parts[0]);
        JsonNode header = objectMapper.readTree(new String(headerJson, StandardCharsets.UTF_8));
        JsonNode kid = header.get("kid");
        if (kid == null || kid.asString().isBlank()) {
            throw new IllegalArgumentException("Firebase ID token missing kid header.");
        }
        return kid.asString();
    }

    private synchronized void refreshKeysIfNeeded() throws Exception {
        if (Instant.now().isBefore(keysExpireAt) && !keysByKid.isEmpty()) {
            return;
        }
        try {
            loadX509Keys();
        } catch (Exception x509Error) {
            log.warn("Firebase X509 key fetch failed ({}), falling back to JWKS", x509Error.getMessage());
            loadJwkKeys();
        }
        keysExpireAt = Instant.now().plus(Duration.ofHours(1));
        log.info("Loaded {} Firebase public keys for ID token verification", keysByKid.size());
    }

    private void loadX509Keys() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(X509_URL))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        JsonNode map = objectMapper.readTree(response.body());
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        ConcurrentHashMap<String, PublicKey> next = new ConcurrentHashMap<>();
        map.properties().forEach(entry -> {
            try {
                String pem = entry.getValue().asString();
                X509Certificate cert = (X509Certificate) cf.generateCertificate(
                        new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
                next.put(entry.getKey(), cert.getPublicKey());
            } catch (Exception e) {
                log.warn("Skipping Firebase cert {}: {}", entry.getKey(), e.getMessage());
            }
        });
        if (next.isEmpty()) {
            throw new IllegalStateException("No Firebase X509 keys parsed");
        }
        keysByKid.clear();
        keysByKid.putAll(next);
    }

    private void loadJwkKeys() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(JWK_URL))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode keys = root.get("keys");
        if (keys == null || !keys.isArray()) {
            throw new IllegalStateException("JWKS response missing keys");
        }
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        ConcurrentHashMap<String, PublicKey> next = new ConcurrentHashMap<>();
        for (JsonNode jwk : keys) {
            String kid = text(jwk, "kid");
            String n = text(jwk, "n");
            String e = text(jwk, "e");
            if (kid == null || n == null || e == null) {
                continue;
            }
            RSAPublicKeySpec spec = new RSAPublicKeySpec(decodeBase64Url(n), decodeBase64Url(e));
            RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(spec);
            next.put(kid, publicKey);
        }
        if (next.isEmpty()) {
            throw new IllegalStateException("No Firebase JWKS keys parsed");
        }
        keysByKid.clear();
        keysByKid.putAll(next);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null) {
            return null;
        }
        String s = v.asString();
        return s == null || s.isBlank() ? null : s;
    }

    private static BigInteger decodeBase64Url(String value) {
        byte[] bytes = Base64.getUrlDecoder().decode(value);
        return new BigInteger(1, bytes);
    }
}
