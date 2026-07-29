package dev.tintwym.home_mart_backend.service;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import dev.tintwym.home_mart_backend.config.FirebaseProperties;
import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import dev.tintwym.home_mart_backend.utility.UlidService;
import io.jsonwebtoken.Claims;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies Firebase ID tokens and upserts local {@link User} rows for Google/Apple sign-in.
 *
 * <p>Prefer Admin SDK when {@code FIREBASE_CREDENTIALS_JSON} (or ADC) is set; otherwise
 * verify tokens with Google's public JWKS using {@code FIREBASE_PROJECT_ID}.
 */
@Service
public class FirebaseAuthService {

    private static final Logger log = LoggerFactory.getLogger(FirebaseAuthService.class);

    private final FirebaseProperties firebaseProperties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final FirebaseIdTokenJwksVerifier jwksVerifier;
    private final SecureRandom secureRandom = new SecureRandom();

    private volatile boolean adminReady;
    private volatile boolean jwksReady;

    public FirebaseAuthService(
            FirebaseProperties firebaseProperties,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            FirebaseIdTokenJwksVerifier jwksVerifier) {
        this.firebaseProperties = firebaseProperties;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwksVerifier = jwksVerifier;
    }

    @PostConstruct
    public void init() {
        jwksReady = firebaseProperties.getProjectId() != null
                && !firebaseProperties.getProjectId().isBlank();

        boolean hasJson = firebaseProperties.isConfigured();
        boolean hasAdc = System.getenv("GOOGLE_APPLICATION_CREDENTIALS") != null
                && !System.getenv("GOOGLE_APPLICATION_CREDENTIALS").isBlank();
        if (!hasJson && !hasAdc) {
            adminReady = false;
            if (jwksReady) {
                log.info(
                        "Firebase Admin credentials missing; ID tokens will be verified via Google JWKS (projectId={})",
                        firebaseProperties.getProjectId());
            } else {
                log.info("Firebase not configured (no FIREBASE_PROJECT_ID / credentials); social login disabled");
            }
            return;
        }
        try {
            if (FirebaseApp.getApps().isEmpty()) {
                GoogleCredentials credentials;
                if (hasJson) {
                    credentials = GoogleCredentials.fromStream(
                            new ByteArrayInputStream(
                                    firebaseProperties.getCredentialsJson().getBytes(StandardCharsets.UTF_8)));
                } else {
                    credentials = GoogleCredentials.getApplicationDefault();
                }
                FirebaseOptions.Builder builder = FirebaseOptions.builder().setCredentials(credentials);
                if (jwksReady) {
                    builder.setProjectId(firebaseProperties.getProjectId().trim());
                }
                FirebaseApp.initializeApp(builder.build());
            }
            adminReady = true;
            log.info("Firebase Admin initialized");
        } catch (Exception e) {
            adminReady = false;
            log.error("Failed to initialize Firebase Admin: {}", e.getMessage());
            if (jwksReady) {
                log.info("Falling back to JWKS ID token verification");
            }
        }
    }

    public boolean isReady() {
        return adminReady || jwksReady;
    }

    public record VerifiedIdentity(
            String uid, String email, String name, boolean emailVerified, String provider) {
    }

    public VerifiedIdentity verifyIdToken(String idToken) {
        if (!isReady()) {
            throw new IllegalStateException(
                    "Firebase authentication is not configured. Set FIREBASE_PROJECT_ID (and optionally FIREBASE_CREDENTIALS_JSON).");
        }
        if (idToken == null || idToken.isBlank()) {
            throw new IllegalArgumentException("Missing Firebase ID token.");
        }

        if (adminReady) {
            try {
                return verifyWithAdmin(idToken.trim());
            } catch (FirebaseAuthException e) {
                throw new IllegalArgumentException("Invalid Firebase ID token: " + e.getMessage(), e);
            }
        }

        return verifyWithJwks(idToken.trim());
    }

    private VerifiedIdentity verifyWithAdmin(String idToken) throws FirebaseAuthException {
        FirebaseToken token = FirebaseAuth.getInstance().verifyIdToken(idToken);
        String email = token.getEmail();
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Firebase account has no email address.");
        }
        String name = token.getName();
        if (name == null || name.isBlank()) {
            name = email.contains("@") ? email.substring(0, email.indexOf('@')) : "User";
        }
        return new VerifiedIdentity(
                token.getUid(),
                email.trim().toLowerCase(),
                name.trim(),
                token.isEmailVerified(),
                resolveProviderFromAdmin(token));
    }

    private VerifiedIdentity verifyWithJwks(String idToken) {
        Claims claims = jwksVerifier.verify(idToken, firebaseProperties.getProjectId());
        String uid = claims.getSubject();
        if (uid == null || uid.isBlank()) {
            throw new IllegalArgumentException("Firebase ID token missing subject.");
        }
        String email = stringClaim(claims, "email");
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Firebase account has no email address.");
        }
        String name = stringClaim(claims, "name");
        if (name == null || name.isBlank()) {
            name = email.contains("@") ? email.substring(0, email.indexOf('@')) : "User";
        }
        boolean emailVerified = Boolean.TRUE.equals(claims.get("email_verified", Boolean.class));
        return new VerifiedIdentity(
                uid,
                email.trim().toLowerCase(),
                name.trim(),
                emailVerified,
                resolveProviderFromClaims(claims));
    }

    private static String stringClaim(Claims claims, String name) {
        Object value = claims.get(name);
        return value == null ? null : String.valueOf(value);
    }

    private static String resolveProviderFromAdmin(FirebaseToken token) {
        Object firebaseClaim = token.getClaims().get("firebase");
        if (firebaseClaim instanceof Map<?, ?> map) {
            Object identities = map.get("sign_in_provider");
            if (identities instanceof String s) {
                return normalizeProvider(s);
            }
        }
        return "google";
    }

    private static String resolveProviderFromClaims(Claims claims) {
        Object firebaseClaim = claims.get("firebase");
        if (firebaseClaim instanceof Map<?, ?> map) {
            Object identities = map.get("sign_in_provider");
            if (identities instanceof String s) {
                return normalizeProvider(s);
            }
        }
        return "google";
    }

    private static String normalizeProvider(String s) {
        if (s.contains("apple")) {
            return "apple";
        }
        if (s.contains("google")) {
            return "google";
        }
        return s;
    }

    @Transactional
    public User findOrCreateUser(VerifiedIdentity identity, String region) {
        Optional<User> byUid = userRepository.findByFirebaseUid(identity.uid());
        if (byUid.isPresent()) {
            User user = byUid.get();
            touchVerified(user, identity);
            return userRepository.save(user);
        }

        Optional<User> byEmail = userRepository.findByEmail(identity.email());
        if (byEmail.isPresent()) {
            User user = byEmail.get();
            user.setFirebaseUid(identity.uid());
            if (user.getAuthProvider() == null || user.getAuthProvider().isBlank()) {
                user.setAuthProvider(identity.provider());
            }
            touchVerified(user, identity);
            return userRepository.save(user);
        }

        User user = new User();
        user.setId(UlidService.newUlid());
        user.setName(identity.name());
        user.setEmail(identity.email());
        user.setFirebaseUid(identity.uid());
        user.setAuthProvider(identity.provider());
        user.setPassword(passwordEncoder.encode(randomUnusablePassword()));
        user.setSellerType("individual");
        if (region != null && !region.isBlank()) {
            String normalized = region.trim().toUpperCase();
            if (ShopConfig.REGIONS.contains(normalized)) {
                user.setRegion(normalized);
            }
        }
        touchVerified(user, identity);
        return userRepository.save(user);
    }

    private static void touchVerified(User user, VerifiedIdentity identity) {
        if (identity.emailVerified() && user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(Instant.now());
        }
        if (user.getName() == null || user.getName().isBlank()) {
            user.setName(identity.name());
        }
    }

    private String randomUnusablePassword() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
