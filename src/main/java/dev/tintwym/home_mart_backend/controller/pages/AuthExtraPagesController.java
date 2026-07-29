package dev.tintwym.home_mart_backend.controller.pages;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.tintwym.home_mart_backend.entity.PasswordResetToken;
import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.entity.Message;
import dev.tintwym.home_mart_backend.repository.MessageRepository;
import dev.tintwym.home_mart_backend.repository.PasskeyRepository;
import dev.tintwym.home_mart_backend.repository.PasswordResetTokenRepository;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import dev.tintwym.home_mart_backend.mapper.ApiJson;
import dev.tintwym.home_mart_backend.service.AuthCookieService;
import dev.tintwym.home_mart_backend.utility.CryptoSupport;
import dev.tintwym.home_mart_backend.service.EmailService;
import dev.tintwym.home_mart_backend.service.EmailVerificationService;
import dev.tintwym.home_mart_backend.service.InertiaService;
import dev.tintwym.home_mart_backend.service.JwtService;
import dev.tintwym.home_mart_backend.service.PasskeyService;
import dev.tintwym.home_mart_backend.service.QrSvgService;
import dev.tintwym.home_mart_backend.service.RecoveryCodesService;
import dev.tintwym.home_mart_backend.service.TotpService;
import dev.tintwym.home_mart_backend.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
public class AuthExtraPagesController extends PageControllerSupport {

    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthCookieService authCookieService;
    private final TotpService totpService;
    private final QrSvgService qrSvgService;
    private final EmailService emailService;
    private final EmailVerificationService emailVerificationService;
    private final RecoveryCodesService recoveryCodesService;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasskeyRepository passkeyRepository;
    private final MessageRepository messageRepository;
    private final PasskeyService passkeyService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public AuthExtraPagesController(
            InertiaService inertia,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            AuthCookieService authCookieService,
            TotpService totpService,
            QrSvgService qrSvgService,
            EmailService emailService,
            EmailVerificationService emailVerificationService,
            RecoveryCodesService recoveryCodesService,
            PasswordResetTokenRepository passwordResetTokenRepository,
            PasskeyRepository passkeyRepository,
            MessageRepository messageRepository,
            PasskeyService passkeyService,
            AppProperties appProperties,
            ObjectMapper objectMapper) {
        super(inertia, userRepository);
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authCookieService = authCookieService;
        this.totpService = totpService;
        this.qrSvgService = qrSvgService;
        this.emailService = emailService;
        this.emailVerificationService = emailVerificationService;
        this.recoveryCodesService = recoveryCodesService;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passkeyRepository = passkeyRepository;
        this.messageRepository = messageRepository;
        this.passkeyService = passkeyService;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    // ── Forgot / reset password ──────────────────────────────────────────

    @GetMapping("/forgot-password")
    public ResponseEntity<?> forgotPasswordForm(HttpServletRequest request, HttpServletResponse response) {
        return render(request, response, "auth/forgot-password", Map.of());
    }

    @PostMapping("/forgot-password")
    @Transactional
    public ResponseEntity<?> forgotPassword(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam String email) {
        String normalized = email.trim().toLowerCase();
        Optional<User> userOpt = userRepository.findByEmail(normalized);
        if (userOpt.isPresent()) {
            String raw = CryptoSupport.randomToken(32);
            PasswordResetToken token = passwordResetTokenRepository.findByEmail(normalized).orElseGet(PasswordResetToken::new);
            token.setEmail(normalized);
            token.setToken(CryptoSupport.sha256Hex(raw));
            token.setCreatedAt(Instant.now());
            passwordResetTokenRepository.save(token);
            String link = appProperties.getUrl() + "/reset-password/" + raw + "?email=" + normalized;
            emailService.send(normalized, "Reset your Home Mart password",
                    "Click to reset your password: " + link);
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("status", "We have emailed your password reset link.");
        return render(request, response, "auth/forgot-password", props);
    }

    @GetMapping("/reset-password/{token}")
    public ResponseEntity<?> resetPasswordForm(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String token,
            @RequestParam(value = "email", required = false) String email) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("token", token);
        props.put("email", email == null ? "" : email);
        return render(request, response, "auth/reset-password", props);
    }

    @PostMapping("/reset-password")
    @Transactional
    public ResponseEntity<?> resetPassword(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam String token,
            @RequestParam String email,
            @RequestParam String password,
            @RequestParam(value = "password_confirmation", required = false) String passwordConfirmation) {
        String normalized = email.trim().toLowerCase();
        Map<String, Object> fieldErrors = new LinkedHashMap<>();
        if (password == null || password.length() < 8) {
            fieldErrors.put("password", "Password must be at least 8 characters.");
        } else if (passwordConfirmation != null && !password.equals(passwordConfirmation)) {
            fieldErrors.put("password", "Password confirmation does not match.");
        }
        Optional<PasswordResetToken> prt = passwordResetTokenRepository.findByEmail(normalized);
        if (prt.isEmpty()
                || !CryptoSupport.constantTimeEquals(prt.get().getToken(), CryptoSupport.sha256Hex(token))
                || prt.get().getCreatedAt() == null
                || prt.get().getCreatedAt().isBefore(Instant.now().minus(60, ChronoUnit.MINUTES))) {
            fieldErrors.put("email", "This password reset token is invalid.");
        }
        if (!fieldErrors.isEmpty()) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("token", token);
            props.put("email", email);
            props.put("errors", fieldErrors);
            return render(request, response, "auth/reset-password", props);
        }
        User user = userRepository.findByEmail(normalized).orElseThrow();
        user.setPassword(passwordEncoder.encode(password));
        userRepository.save(user);
        passwordResetTokenRepository.deleteByEmail(normalized);
        return redirectWithStatus(request, response, "/login", "Your password has been reset.");
    }

    // ── Email verification ───────────────────────────────────────────────

    @GetMapping("/email/verify")
    public ResponseEntity<?> verifyEmailPrompt(HttpServletRequest request, HttpServletResponse response) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        User user = requireUser();
        if (user.getEmailVerifiedAt() != null) {
            return redirect(request, "/");
        }
        return render(request, response, "auth/verify-email", Map.of());
    }

    @PostMapping("/email/verification-notification")
    public ResponseEntity<?> resendVerification(HttpServletRequest request, HttpServletResponse response) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        User user = requireUser();
        if (user.getEmailVerifiedAt() == null) {
            emailVerificationService.sendVerificationEmail(user);
        }
        return redirectWithStatus(request, response, "/email/verify", "verification-link-sent");
    }

    @GetMapping("/email/verify/{id}/{hash}")
    @Transactional
    public ResponseEntity<?> verifyEmail(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String id,
            @PathVariable String hash,
            @RequestParam(required = false) Long expires,
            @RequestParam(required = false) String signature) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (expires == null || signature == null || signature.isBlank()
                || !emailVerificationService.isValid(id, user.getEmail(), hash, expires, signature)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid verification link");
        }
        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(Instant.now());
            userRepository.save(user);
        }
        return redirectWithStatus(request, response, "/", "Email verified.");
    }

    // ── Two-factor challenge (login) ─────────────────────────────────────

    @GetMapping("/two-factor-challenge")
    public ResponseEntity<?> twoFactorChallengeForm(HttpServletRequest request, HttpServletResponse response) {
        if (authCookieService.readTwoFactorPendingUserId(request).isEmpty()) {
            return redirect(request, "/login");
        }
        return render(request, response, "auth/two-factor-challenge", Map.of());
    }

    @PostMapping("/two-factor-challenge")
    @Transactional
    public ResponseEntity<?> twoFactorChallenge(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "recovery_code", required = false) String recoveryCode) {
        Optional<String> pending = authCookieService.readTwoFactorPendingUserId(request);
        if (pending.isEmpty()) {
            return redirect(request, "/login");
        }
        User user = userRepository.findById(pending.get()).orElse(null);
        if (user == null || !user.hasConfirmedTwoFactor()) {
            authCookieService.clearTwoFactorPending(response);
            return redirect(request, "/login");
        }
        boolean ok = false;
        if (code != null && !code.isBlank()) {
            ok = totpService.verifyCode(user.getTwoFactorSecret(), code);
        } else if (recoveryCode != null && !recoveryCode.isBlank()) {
            ok = consumeRecoveryCode(user, recoveryCode.trim());
        }
        if (!ok) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("errors", errors("code", "The provided two factor authentication code was invalid."));
            return render(request, response, "auth/two-factor-challenge", props);
        }
        authCookieService.clearTwoFactorPending(response);
        authCookieService.setTokenCookie(response, jwtService.createToken(user));
        return redirect(request, "/");
    }

    // ── Confirm password (page) ──────────────────────────────────────────

    @GetMapping("/user/confirm-password")
    public ResponseEntity<?> confirmPasswordForm(HttpServletRequest request, HttpServletResponse response) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        return render(request, response, "auth/confirm-password", Map.of());
    }

    @PostMapping("/user/confirm-password")
    public ResponseEntity<?> confirmPassword(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam String password) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        User user = requireUser();
        if (!passwordEncoder.matches(password, user.getPassword())) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("errors", errors("password", "The provided password was incorrect."));
            return render(request, response, "auth/confirm-password", props);
        }
        return redirect(request, "/settings/two-factor");
    }

    // ── Two-factor management (Fortify routes) ───────────────────────────

    @PostMapping("/user/two-factor-authentication")
    @Transactional
    public ResponseEntity<?> enableTwoFactor(HttpServletRequest request, HttpServletResponse response) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        User user = requireUser();
        user.setTwoFactorSecret(totpService.generateSecretBase32());
        user.setTwoFactorRecoveryCodes(recoveryCodesService.write(totpService.generateRecoveryCodes()));
        user.setTwoFactorConfirmedAt(null);
        userRepository.save(user);
        return inertia.back(request, response, "/settings/two-factor");
    }

    @DeleteMapping("/user/two-factor-authentication")
    @Transactional
    public ResponseEntity<?> disableTwoFactor(HttpServletRequest request, HttpServletResponse response) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        User user = requireUser();
        user.setTwoFactorSecret(null);
        user.setTwoFactorRecoveryCodes(null);
        user.setTwoFactorConfirmedAt(null);
        userRepository.save(user);
        return redirectWithStatus(request, response, "/settings/two-factor", "Two-factor authentication disabled.");
    }

    @PostMapping("/user/confirmed-two-factor-authentication")
    @Transactional
    public ResponseEntity<?> confirmTwoFactor(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam String code) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        User user = requireUser();
        if (user.getTwoFactorSecret() == null || !totpService.verifyCode(user.getTwoFactorSecret(), code)) {
            return inertia.backWithFlash(request, response, null, "Invalid authentication code.", "/settings/two-factor");
        }
        user.setTwoFactorConfirmedAt(Instant.now());
        userRepository.save(user);
        return redirectWithStatus(request, response, "/settings/two-factor", "Two-factor authentication enabled.");
    }

    @GetMapping("/user/two-factor-qr-code")
    public ResponseEntity<?> twoFactorQrCode() {
        User user = requireUser();
        if (user.getTwoFactorSecret() == null) {
            return ResponseEntity.ok(Map.of("svg", ""));
        }
        String uri = totpService.buildOtpAuthUri(appProperties.getName(), user.getEmail(), user.getTwoFactorSecret());
        return ResponseEntity.ok(Map.of("svg", qrSvgService.toSvg(uri)));
    }

    @GetMapping("/user/two-factor-secret-key")
    public ResponseEntity<?> twoFactorSecretKey() {
        User user = requireUser();
        return ResponseEntity.ok(Map.of("secretKey", user.getTwoFactorSecret() == null ? "" : user.getTwoFactorSecret()));
    }

    @GetMapping("/user/two-factor-recovery-codes")
    public ResponseEntity<?> recoveryCodes() {
        User user = requireUser();
        return ResponseEntity.ok(recoveryCodesService.read(user));
    }

    @PostMapping("/user/two-factor-recovery-codes")
    @Transactional
    public ResponseEntity<?> regenerateRecoveryCodes(HttpServletRequest request, HttpServletResponse response) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        User user = requireUser();
        user.setTwoFactorRecoveryCodes(recoveryCodesService.write(totpService.generateRecoveryCodes()));
        userRepository.save(user);
        return inertia.back(request, response, "/settings/two-factor");
    }

    // ── Passkeys (public auth + settings) ────────────────────────────────

    @GetMapping("/passkeys/authentication-options")
    public ResponseEntity<?> passkeyAuthOptions(@RequestParam(value = "email", required = false) String email) {
        Map<String, Object> result = passkeyService.createAuthenticationOptions(email);
        Object options = result.get("options");
        if (options instanceof JsonNode node) {
            @SuppressWarnings("unchecked")
            Map<String, Object> out = objectMapper.convertValue(node, Map.class);
            out.put("state", result.get("state"));
            return ResponseEntity.ok(out);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/passkeys/authenticate")
    @Transactional
    public ResponseEntity<?> passkeyAuthenticate(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam("start_authentication_response") String credentialJson,
            @RequestParam(value = "state", required = false) String state) {
        try {
            JsonNode credential = objectMapper.readTree(credentialJson);
            User user = passkeyService.completeAuthentication(state, credential);
            if (user.hasConfirmedTwoFactor()) {
                authCookieService.setTwoFactorPending(response, user.getId());
                return redirect(request, "/two-factor-challenge");
            }
            authCookieService.setTokenCookie(response, jwtService.createToken(user));
            return redirect(request, "/");
        } catch (Exception e) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("canResetPassword", true);
            props.put("canRegister", true);
            props.put("passkeyError", e.getMessage() == null ? "Passkey authentication failed." : e.getMessage());
            return render(request, response, "auth/login", props);
        }
    }

    @GetMapping("/settings/passkeys/register-options")
    public ResponseEntity<?> passkeyRegisterOptions() {
        User user = requireUser();
        Map<String, Object> result = passkeyService.createRegistrationOptions(user);
        // Frontend often expects the options object directly or wrapped.
        Object options = result.get("options");
        if (options instanceof JsonNode node) {
            @SuppressWarnings("unchecked")
            Map<String, Object> out = objectMapper.convertValue(node, Map.class);
            out.put("state", result.get("state"));
            return ResponseEntity.ok(out);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/settings/passkeys")
    @Transactional
    public ResponseEntity<?> storePasskey(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam String passkey,
            @RequestParam String options,
            @RequestParam(value = "name", required = false) String name) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        try {
            JsonNode credential = objectMapper.readTree(passkey);
            JsonNode opts = objectMapper.readTree(options);
            String state = opts.has("state") ? opts.get("state").asString(null) : null;
            if (state == null || state.isBlank()) {
                state = request.getParameter("state");
            }
            passkeyService.completeRegistration(requireUser(), state, name, credential);
            return backWithStatus(request, response, "Passkey added.");
        } catch (Exception e) {
            return backWithError(request, response, "Could not save passkey.");
        }
    }

    @DeleteMapping("/settings/passkeys/{passkeyId}")
    @Transactional
    public ResponseEntity<?> destroyPasskey(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable Long passkeyId) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        passkeyRepository.deleteByAuthenticatableIdAndId(requireUser().getId(), passkeyId);
        return backWithStatus(request, response, "Passkey removed.");
    }

    // ── Notifications (derived from unread chat messages) ────────────────

    @GetMapping(value = "/notifications", produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional(readOnly = true)
    public ResponseEntity<?> notifications() {
        if (currentUserId().isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(List.of());
        }
        String userId = currentUserId().get();
        List<Message> unread = messageRepository.findUnreadForUser(userId);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        int limit = Math.min(30, unread.size());
        for (int i = 0; i < limit; i++) {
            Message m = unread.get(i);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("conversation_id", m.getConversationId());
            data.put("from_user_name", m.getUser() == null ? "Someone" : m.getUser().getName());
            data.put("body", m.getBody());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.getId());
            row.put("type", "new_message");
            row.put("data", data);
            row.put("created_at", ApiJson.formatInstant(m.getCreatedAt()));
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    @PostMapping("/notifications/{id}/read")
    @Transactional
    public ResponseEntity<Void> markNotificationRead(@PathVariable String id) {
        User user = requireUser();
        Message message = messageRepository.findById(id).orElse(null);
        if (message != null
                && !user.getId().equals(message.getUserId())
                && message.getReadAt() == null) {
            message.setReadAt(Instant.now());
            messageRepository.save(message);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/notifications/read-all")
    @Transactional
    public ResponseEntity<Void> markAllNotificationsRead() {
        User user = requireUser();
        for (Message message : messageRepository.findUnreadForUser(user.getId())) {
            message.setReadAt(Instant.now());
            messageRepository.save(message);
        }
        return ResponseEntity.noContent().build();
    }

    private boolean consumeRecoveryCode(User user, String code) {
        if (!recoveryCodesService.consume(user, code)) {
            return false;
        }
        userRepository.save(user);
        return true;
    }
}
