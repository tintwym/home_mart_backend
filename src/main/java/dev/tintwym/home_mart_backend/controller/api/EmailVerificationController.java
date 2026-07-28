package dev.tintwym.home_mart_backend.controller.api;

import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import dev.tintwym.home_mart_backend.service.EmailVerificationService;
import dev.tintwym.home_mart_backend.utility.ApiResponses;
import dev.tintwym.home_mart_backend.utility.AuthSupport;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class EmailVerificationController {

    private final UserRepository userRepository;
    private final EmailVerificationService emailVerificationService;

    public EmailVerificationController(
            UserRepository userRepository, EmailVerificationService emailVerificationService) {
        this.userRepository = userRepository;
        this.emailVerificationService = emailVerificationService;
    }

    @PostMapping("/email/verification-notification")
    @Transactional(readOnly = true)
    public ResponseEntity<?> sendNotification() {
        User user = userRepository.findById(AuthSupport.currentUserId()).orElse(null);
        if (user != null && user.getEmailVerifiedAt() == null) {
            emailVerificationService.sendVerificationEmail(user);
        }
        return ResponseEntity.ok(Map.of("status", "verification-link-sent"));
    }

    @PostMapping("/verify-email/{id}/{hash}")
    @Transactional
    public ResponseEntity<?> verify(
            @PathVariable String id,
            @PathVariable String hash,
            @RequestParam long expires,
            @RequestParam String signature) {
        User user = userRepository.findById(id).orElse(null);
        if (user == null) {
            return ApiResponses.notFound("User not found.");
        }
        if (!emailVerificationService.isValid(id, user.getEmail(), hash, expires, signature)) {
            if (Instant.now().getEpochSecond() > expires) {
                return ApiResponses.forbidden("Verification link has expired.");
            }
            return ApiResponses.forbidden("Invalid verification signature.");
        }
        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(Instant.now());
            userRepository.save(user);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "verified");
        return ResponseEntity.ok(body);
    }
}
