package dev.tintwym.home_mart_backend.controller.api;

import dev.tintwym.home_mart_backend.entity.PasswordResetToken;
import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.repository.PasswordResetTokenRepository;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import dev.tintwym.home_mart_backend.utility.CryptoSupport;
import dev.tintwym.home_mart_backend.service.EmailService;
import dev.tintwym.home_mart_backend.config.AppProperties;
import dev.tintwym.home_mart_backend.utility.ApiResponses;
import dev.tintwym.home_mart_backend.dto.ApiRequests.ForgotPasswordRequest;
import dev.tintwym.home_mart_backend.dto.ApiRequests.ResetPasswordRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PasswordResetController {

    private static final Pattern HAS_LETTER = Pattern.compile(".*[A-Za-z].*");
    private static final Pattern HAS_DIGIT = Pattern.compile(".*\\d.*");

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final AppProperties appProperties;

    public PasswordResetController(
            UserRepository userRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            PasswordEncoder passwordEncoder,
            EmailService emailService,
            AppProperties appProperties) {
        this.userRepository = userRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.appProperties = appProperties;
    }

    @PostMapping("/forgot-password")
    @Transactional
    public ResponseEntity<?> forgot(@Valid @RequestBody ForgotPasswordRequest request) {
        String email = request.email().trim().toLowerCase();
        userRepository.findByEmail(email).ifPresent(user -> {
            String rawToken = CryptoSupport.randomToken(32);
            String hashed = CryptoSupport.sha256Hex(rawToken);
            passwordResetTokenRepository.deleteByEmail(email);
            PasswordResetToken token = new PasswordResetToken();
            token.setEmail(email);
            token.setToken(hashed);
            token.setCreatedAt(Instant.now());
            passwordResetTokenRepository.save(token);

            String link = frontendUrl() + "/reset-password/" + rawToken + "?email="
                    + java.net.URLEncoder.encode(email, java.nio.charset.StandardCharsets.UTF_8);
            emailService.send(
                    email,
                    "Reset your password",
                    "Reset your password using this link (valid 60 minutes): " + link);
        });
        return ResponseEntity.ok(Map.of(
                "status",
                "We have emailed your password reset link."));
    }

    @PostMapping("/reset-password")
    @Transactional
    public ResponseEntity<?> reset(@Valid @RequestBody ResetPasswordRequest request) {
        String email = request.email().trim().toLowerCase();
        String password = request.password();
        if (password.length() < 8 || !HAS_LETTER.matcher(password).matches() || !HAS_DIGIT.matcher(password).matches()) {
            return ApiResponses.unprocessable(
                    "Password must be at least 8 characters and include a letter and a number.",
                    Map.of("password", List.of("Password must be at least 8 characters and include a letter and a number.")));
        }
        if (request.passwordConfirmation() != null && !password.equals(request.passwordConfirmation())) {
            return ApiResponses.unprocessable(
                    "Password confirmation does not match.",
                    Map.of("password", List.of("Password confirmation does not match.")));
        }

        PasswordResetToken stored = passwordResetTokenRepository.findByEmail(email).orElse(null);
        String hashed = CryptoSupport.sha256Hex(request.token());
        if (stored == null
                || stored.getCreatedAt() == null
                || stored.getCreatedAt().isBefore(Instant.now().minus(60, ChronoUnit.MINUTES))
                || !CryptoSupport.constantTimeEquals(stored.getToken(), hashed)) {
            return ApiResponses.unprocessable(
                    "This password reset token is invalid.",
                    Map.of("email", List.of("This password reset token is invalid.")));
        }

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            return ApiResponses.unprocessable("This password reset token is invalid.");
        }
        user.setPassword(passwordEncoder.encode(password));
        userRepository.save(user);
        passwordResetTokenRepository.deleteByEmail(email);
        return ResponseEntity.ok(Map.of("status", "Your password has been reset."));
    }

    private String frontendUrl() {
        // Reset pages are served by this API (Inertia) via Vite/Vercel proxy.
        String url = appProperties.getUrl();
        if (url == null || url.isBlank()) {
            url = appProperties.getFrontendUrl();
        }
        if (url == null || url.isBlank()) {
            url = "http://localhost:5199";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
