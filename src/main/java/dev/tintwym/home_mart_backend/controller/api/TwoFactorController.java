package dev.tintwym.home_mart_backend.controller.api;

import dev.tintwym.home_mart_backend.config.AppProperties;
import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import dev.tintwym.home_mart_backend.service.RecoveryCodesService;
import dev.tintwym.home_mart_backend.service.TotpService;
import dev.tintwym.home_mart_backend.utility.ApiResponses;
import dev.tintwym.home_mart_backend.utility.AuthSupport;
import dev.tintwym.home_mart_backend.dto.ApiRequests.TwoFactorConfirmRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TwoFactorController {

    private final UserRepository userRepository;
    private final TotpService totpService;
    private final AppProperties appProperties;
    private final RecoveryCodesService recoveryCodesService;

    public TwoFactorController(
            UserRepository userRepository,
            TotpService totpService,
            AppProperties appProperties,
            RecoveryCodesService recoveryCodesService) {
        this.userRepository = userRepository;
        this.totpService = totpService;
        this.appProperties = appProperties;
        this.recoveryCodesService = recoveryCodesService;
    }

    @PostMapping("/user/two-factor-authentication")
    @Transactional
    public ResponseEntity<?> enable() {
        User user = requireUser();
        String secret = totpService.generateSecretBase32();
        List<String> codes = totpService.generateRecoveryCodes();
        user.setTwoFactorSecret(secret);
        user.setTwoFactorRecoveryCodes(recoveryCodesService.write(codes));
        user.setTwoFactorConfirmedAt(null);
        userRepository.save(user);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Two factor authentication enabled.");
        body.put("recovery_codes", codes);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/user/confirmed-two-factor-authentication")
    @Transactional
    public ResponseEntity<?> confirm(@Valid @RequestBody TwoFactorConfirmRequest request) {
        User user = requireUser();
        if (user.getTwoFactorSecret() == null || user.getTwoFactorSecret().isBlank()) {
            return ApiResponses.unprocessable("Two factor authentication is not enabled.");
        }
        if (!totpService.verifyCode(user.getTwoFactorSecret(), request.code())) {
            return ApiResponses.unprocessable(
                    "Invalid authentication code.",
                    Map.of("code", List.of("Invalid authentication code.")));
        }
        user.setTwoFactorConfirmedAt(Instant.now());
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("message", "Two factor authentication confirmed."));
    }

    @DeleteMapping("/user/two-factor-authentication")
    @Transactional
    public ResponseEntity<?> disable() {
        User user = requireUser();
        user.setTwoFactorSecret(null);
        user.setTwoFactorRecoveryCodes(null);
        user.setTwoFactorConfirmedAt(null);
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("message", "Two factor authentication disabled."));
    }

    @GetMapping("/user/two-factor-qr-code")
    @Transactional(readOnly = true)
    public ResponseEntity<?> qrCode() {
        User user = requireUser();
        if (user.getTwoFactorSecret() == null || user.getTwoFactorSecret().isBlank()) {
            return ApiResponses.badRequest("Two factor authentication is not enabled.");
        }
        String issuer = appProperties.getName() == null ? "Home Mart" : appProperties.getName();
        String url = totpService.buildOtpAuthUri(issuer, user.getEmail(), user.getTwoFactorSecret());
        return ResponseEntity.ok(Map.of("otpauth_url", url));
    }

    @GetMapping("/user/two-factor-secret-key")
    @Transactional(readOnly = true)
    public ResponseEntity<?> secretKey() {
        User user = requireUser();
        if (user.getTwoFactorSecret() == null || user.getTwoFactorSecret().isBlank()) {
            return ApiResponses.badRequest("Two factor authentication is not enabled.");
        }
        return ResponseEntity.ok(Map.of("secret_key", user.getTwoFactorSecret()));
    }

    @GetMapping("/user/two-factor-recovery-codes")
    @Transactional(readOnly = true)
    public ResponseEntity<?> recoveryCodes() {
        User user = requireUser();
        return ResponseEntity.ok(Map.of("recovery_codes", recoveryCodesService.read(user)));
    }

    @PostMapping("/user/two-factor-recovery-codes")
    @Transactional
    public ResponseEntity<?> regenerateRecoveryCodes() {
        User user = requireUser();
        if (user.getTwoFactorSecret() == null || user.getTwoFactorSecret().isBlank()) {
            return ApiResponses.badRequest("Two factor authentication is not enabled.");
        }
        List<String> codes = totpService.generateRecoveryCodes();
        user.setTwoFactorRecoveryCodes(recoveryCodesService.write(codes));
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("recovery_codes", codes));
    }

    private User requireUser() {
        return userRepository
                .findById(AuthSupport.currentUserId())
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthenticated."));
    }
}
