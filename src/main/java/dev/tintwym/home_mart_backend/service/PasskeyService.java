package dev.tintwym.home_mart_backend.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import dev.tintwym.home_mart_backend.config.AppProperties;
import dev.tintwym.home_mart_backend.entity.Passkey;
import dev.tintwym.home_mart_backend.entity.PasskeyChallenge;
import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.mapper.ApiJson;
import dev.tintwym.home_mart_backend.repository.PasskeyChallengeRepository;
import dev.tintwym.home_mart_backend.repository.PasskeyRepository;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.authenticator.Authenticator;
import com.webauthn4j.authenticator.AuthenticatorImpl;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.COSEKey;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import com.webauthn4j.util.Base64UrlUtil;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * WebAuthn passkey helper using webauthn4j for registration/assertion verification.
 *
 * <p>Options JSON is built manually for SimpleWebAuthn compatibility (base64url challenges /
 * credential ids). Completions are verified with {@link
 * WebAuthnManager#createNonStrictWebAuthnManager()}.
 */
@Service
@SuppressWarnings("deprecation") // Authenticator / AuthenticatorImpl still required by webauthn4j 0.28 verify APIs
public class PasskeyService {

    private static final long CHALLENGE_TTL_MS = 5 * 60 * 1000L;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final List<PublicKeyCredentialParameters> PUB_KEY_CRED_PARAMS = List.of(
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256),
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.RS256));

    private final PasskeyRepository passkeyRepository;
    private final PasskeyChallengeRepository passkeyChallengeRepository;
    private final UserRepository userRepository;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final WebAuthnManager webAuthnManager;
    private final ObjectConverter objectConverter;
    private final AttestedCredentialDataConverter attestedCredentialDataConverter;

    public PasskeyService(
            PasskeyRepository passkeyRepository,
            PasskeyChallengeRepository passkeyChallengeRepository,
            UserRepository userRepository,
            AppProperties appProperties,
            ObjectMapper objectMapper) {
        this.passkeyRepository = passkeyRepository;
        this.passkeyChallengeRepository = passkeyChallengeRepository;
        this.userRepository = userRepository;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.objectConverter = new ObjectConverter();
        this.webAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager(objectConverter);
        this.attestedCredentialDataConverter = new AttestedCredentialDataConverter(objectConverter);
    }

    @Transactional
    public Map<String, Object> createRegistrationOptions(User user) {
        purgeExpired();
        String challenge = randomChallenge();
        String state = UUID.randomUUID().toString();
        storeChallenge(state, "register", user.getId(), challenge);

        ObjectNode options = objectMapper.createObjectNode();
        options.put("challenge", challenge);
        options.putObject("rp").put("name", appProperties.getName()).put("id", rpId());
        options.putObject("user")
                .put(
                        "id",
                        Base64UrlUtil.encodeToString(user.getId().getBytes(StandardCharsets.UTF_8)))
                .put("name", user.getEmail())
                .put("displayName", user.getName());
        ArrayNode pubKeyCredParams = options.putArray("pubKeyCredParams");
        pubKeyCredParams.addObject().put("type", "public-key").put("alg", -7);
        pubKeyCredParams.addObject().put("type", "public-key").put("alg", -257);
        options.put("timeout", 60000);
        options.put("attestation", "none");
        options.putObject("authenticatorSelection")
                .put("residentKey", "preferred")
                .put("userVerification", "preferred");

        ArrayNode exclude = options.putArray("excludeCredentials");
        for (Passkey existing : passkeyRepository.findByAuthenticatableId(user.getId())) {
            exclude.addObject().put("type", "public-key").put("id", existing.getCredentialId());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("options", options);
        result.put("state", state);
        return result;
    }

    @Transactional
    public Passkey completeRegistration(User user, String state, String name, JsonNode credential)
            throws PasskeyException {
        ChallengeState challenge = consumeChallenge(state, "register");
        if (!user.getId().equals(challenge.userId())) {
            throw new PasskeyException("Invalid registration state.");
        }

        RegistrationData registrationData;
        try {
            ServerProperty serverProperty = serverProperty(challenge.challenge());
            RegistrationParameters parameters =
                    new RegistrationParameters(serverProperty, PUB_KEY_CRED_PARAMS, false, true);
            registrationData = webAuthnManager.verifyRegistrationResponseJSON(
                    objectMapper.writeValueAsString(credential), parameters);
        } catch (Exception e) {
            throw new PasskeyException(messageOrDefault(e, "Passkey registration verification failed."));
        }

        AttestedCredentialData attested =
                registrationData.getAttestationObject().getAuthenticatorData().getAttestedCredentialData();
        if (attested == null) {
            throw new PasskeyException("Registration response missing attested credential data.");
        }

        String credentialId = Base64UrlUtil.encodeToString(attested.getCredentialId());
        if (passkeyRepository.findByCredentialId(credentialId).isPresent()) {
            throw new PasskeyException("This passkey is already registered.");
        }

        long signCount = registrationData.getAttestationObject().getAuthenticatorData().getSignCount();
        byte[] publicKeyCbor = objectConverter.getCborConverter().writeValueAsBytes(attested.getCOSEKey());
        byte[] attestedBytes = attestedCredentialDataConverter.convert(attested);

        ObjectNode data = objectMapper.createObjectNode();
        data.put("public_key", Base64UrlUtil.encodeToString(publicKeyCbor));
        data.put("sign_count", signCount);
        data.put("user_handle", user.getId());
        data.put("attestation_type", "none");
        data.put("attested_credential_data", Base64UrlUtil.encodeToString(attestedBytes));
            if (attested.getAaguid() != null) {
                data.put("aaguid", attested.getAaguid().toString());
            }
            // Compatibility aliases used by earlier stub storage.
        data.put("publicKey", Base64UrlUtil.encodeToString(publicKeyCbor));
        data.put("signCount", signCount);
        data.put("userHandle", user.getId());

        Passkey passkey = new Passkey();
        passkey.setAuthenticatableId(user.getId());
        passkey.setName(name == null || name.isBlank() ? "Passkey" : name.trim());
        passkey.setCredentialId(credentialId);
        try {
            passkey.setData(objectMapper.writeValueAsString(data));
        } catch (Exception e) {
            throw new PasskeyException("Failed to store passkey data.");
        }
        return passkeyRepository.save(passkey);
    }

    @Transactional
    public Map<String, Object> createAuthenticationOptions(String email) {
        purgeExpired();
        String challenge = randomChallenge();
        String state = UUID.randomUUID().toString();
        String userId = null;
        ArrayNode allow = objectMapper.createArrayNode();

        if (email != null && !email.isBlank()) {
            Optional<User> user = userRepository.findByEmail(email.trim().toLowerCase());
            if (user.isPresent()) {
                userId = user.get().getId();
                for (Passkey pk : passkeyRepository.findByAuthenticatableId(userId)) {
                    allow.addObject().put("type", "public-key").put("id", pk.getCredentialId());
                }
            }
        }

        storeChallenge(state, "authenticate", userId, challenge);

        ObjectNode options = objectMapper.createObjectNode();
        options.put("challenge", challenge);
        options.put("timeout", 60000);
        options.put("rpId", rpId());
        options.put("userVerification", "preferred");
        options.set("allowCredentials", allow);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("options", options);
        result.put("state", state);
        return result;
    }

    @Transactional
    public User completeAuthentication(String state, JsonNode credential) throws PasskeyException {
        ChallengeState challenge = consumeChallenge(state, "authenticate");

        String credentialId = extractCredentialId(credential);
        if (credentialId == null || credentialId.isBlank()) {
            throw new PasskeyException("Credential id is required.");
        }

        Passkey passkey = passkeyRepository
                .findByCredentialId(credentialId)
                .orElseThrow(() -> new PasskeyException("Unknown passkey."));

        if (challenge.userId() != null && !challenge.userId().equals(passkey.getAuthenticatableId())) {
            throw new PasskeyException("Passkey does not match user.");
        }

        AuthenticationData authenticationData;
        try {
            Authenticator authenticator = loadAuthenticator(passkey);
            ServerProperty serverProperty = serverProperty(challenge.challenge());
            List<byte[]> allowCredentials = List.of(Base64UrlUtil.decode(passkey.getCredentialId()));
            AuthenticationParameters parameters =
                    new AuthenticationParameters(serverProperty, authenticator, allowCredentials, false, true);
            authenticationData = webAuthnManager.verifyAuthenticationResponseJSON(
                    objectMapper.writeValueAsString(credential), parameters);
        } catch (PasskeyException e) {
            throw e;
        } catch (Exception e) {
            throw new PasskeyException(messageOrDefault(e, "Passkey authentication verification failed."));
        }

        long signCount = authenticationData.getAuthenticatorData().getSignCount();
        passkey.setLastUsedAt(Instant.now());
        updateStoredSignCount(passkey, signCount);
        passkeyRepository.save(passkey);

        return userRepository
                .findById(passkey.getAuthenticatableId())
                .orElseThrow(() -> new PasskeyException("User not found for passkey."));
    }

    public List<Passkey> listForUser(String userId) {
        return passkeyRepository.findByAuthenticatableId(userId);
    }

    @Transactional
    public boolean deleteForUser(String userId, long id) {
        Optional<Passkey> existing =
                passkeyRepository.findById(id).filter(p -> userId.equals(p.getAuthenticatableId()));
        if (existing.isEmpty()) {
            return false;
        }
        passkeyRepository.delete(existing.get());
        return true;
    }

    public Map<String, Object> passkeyJson(Passkey passkey) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", passkey.getId());
        map.put("name", passkey.getName());
        map.put("last_used_at", ApiJson.formatInstant(passkey.getLastUsedAt()));
        map.put("created_at", ApiJson.formatInstant(passkey.getCreatedAt()));
        return map;
    }

    private ChallengeState consumeChallenge(String state, String type) throws PasskeyException {
        purgeExpired();
        if (state == null || state.isBlank()) {
            throw new PasskeyException("Invalid or expired passkey state.");
        }
        PasskeyChallenge stored = passkeyChallengeRepository.findById(state).orElse(null);
        if (stored == null
                || !type.equals(stored.getPurpose())
                || stored.getExpiresAt() == null
                || stored.getExpiresAt().isBefore(Instant.now())) {
            if (stored != null) {
                passkeyChallengeRepository.delete(stored);
            }
            throw new PasskeyException("Invalid or expired passkey state.");
        }
        passkeyChallengeRepository.delete(stored);
        return new ChallengeState(stored.getPurpose(), stored.getUserId(), stored.getChallenge(),
                stored.getExpiresAt().toEpochMilli());
    }

    private void storeChallenge(String state, String purpose, String userId, String challenge) {
        PasskeyChallenge row = new PasskeyChallenge();
        row.setState(state);
        row.setPurpose(purpose);
        row.setUserId(userId);
        row.setChallenge(challenge);
        row.setExpiresAt(Instant.now().plusMillis(CHALLENGE_TTL_MS));
        passkeyChallengeRepository.save(row);
    }

    private void purgeExpired() {
        passkeyChallengeRepository.deleteExpired(Instant.now());
    }

    private ServerProperty serverProperty(String challengeBase64Url) {
        return new ServerProperty(new Origin(origin()), rpId(), new DefaultChallenge(challengeBase64Url));
    }

    private String origin() {
        String frontend = appProperties.getFrontendUrl();
        if (frontend == null || frontend.isBlank()) {
            frontend = "http://localhost:3000";
        }
        try {
            URI uri = URI.create(frontend);
            String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return "http://localhost:3000";
            }
            int port = uri.getPort();
            if (port < 0) {
                return scheme + "://" + host;
            }
            return scheme + "://" + host + ":" + port;
        } catch (Exception e) {
            return "http://localhost:3000";
        }
    }

    private String rpId() {
        try {
            String host = URI.create(origin()).getHost();
            return host == null || host.isBlank() ? "localhost" : host;
        } catch (Exception e) {
            return "localhost";
        }
    }

    private Authenticator loadAuthenticator(Passkey passkey) throws PasskeyException {
        try {
            JsonNode data = objectMapper.readTree(passkey.getData());
            long signCount = data.path("sign_count").asLong(data.path("signCount").asLong(0));

            String attestedB64 = textOrEmpty(data, "attested_credential_data", "attestedCredentialData");
            AttestedCredentialData attested;
            if (!attestedB64.isBlank()) {
                attested = attestedCredentialDataConverter.convert(Base64UrlUtil.decode(attestedB64));
            } else {
                String publicKeyB64 = textOrEmpty(data, "public_key", "publicKey");
                if (publicKeyB64.isBlank()) {
                    throw new PasskeyException("Stored passkey is missing public key data.");
                }
                COSEKey coseKey =
                        objectConverter.getCborConverter().readValue(Base64UrlUtil.decode(publicKeyB64), COSEKey.class);
                attested = new AttestedCredentialData(
                        AAGUID.ZERO, Base64UrlUtil.decode(passkey.getCredentialId()), coseKey);
            }
            return new AuthenticatorImpl(attested, null, signCount);
        } catch (PasskeyException e) {
            throw e;
        } catch (Exception e) {
            throw new PasskeyException(messageOrDefault(e, "Failed to load stored passkey."));
        }
    }

    private void updateStoredSignCount(Passkey passkey, long signCount) {
        try {
            JsonNode data = objectMapper.readTree(passkey.getData());
            if (data instanceof ObjectNode obj) {
                obj.put("sign_count", signCount);
                obj.put("signCount", signCount);
                passkey.setData(objectMapper.writeValueAsString(obj));
            }
        } catch (Exception ignored) {
            // keep original data
        }
    }

    private static String randomChallenge() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64UrlUtil.encodeToString(bytes);
    }

    private static String extractCredentialId(JsonNode credential) {
        if (credential == null) {
            return null;
        }
        if (credential.hasNonNull("id")) {
            return credential.get("id").asString();
        }
        if (credential.hasNonNull("rawId")) {
            return credential.get("rawId").asString();
        }
        JsonNode response = credential.get("response");
        if (response != null && response.hasNonNull("id")) {
            return response.get("id").asString();
        }
        return null;
    }

    private static String textOrEmpty(JsonNode root, String... paths) {
        for (String path : paths) {
            JsonNode node = root;
            for (String part : path.split("\\.")) {
                if (node == null) {
                    break;
                }
                node = node.get(part);
            }
            if (node != null && !node.isNull()) {
                return node.asString("");
            }
        }
        return "";
    }

    private static String messageOrDefault(Exception e, String fallback) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    private record ChallengeState(String type, String userId, String challenge, long expiresAt) {
    }

    public static class PasskeyException extends Exception {
        public PasskeyException(String message) {
            super(message);
        }
    }
}
