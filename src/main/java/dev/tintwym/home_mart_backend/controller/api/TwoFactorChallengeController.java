package dev.tintwym.home_mart_backend.controller.api;

import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import dev.tintwym.home_mart_backend.mapper.ApiJson;
import dev.tintwym.home_mart_backend.service.JwtService;
import dev.tintwym.home_mart_backend.service.RecoveryCodesService;
import dev.tintwym.home_mart_backend.service.TotpService;
import dev.tintwym.home_mart_backend.utility.ApiResponses;
import dev.tintwym.home_mart_backend.dto.ApiRequests.TwoFactorChallengeRequest;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TwoFactorChallengeController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TotpService totpService;
    private final JwtService jwtService;
    private final RecoveryCodesService recoveryCodesService;

    public TwoFactorChallengeController(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            TotpService totpService,
            JwtService jwtService,
            RecoveryCodesService recoveryCodesService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.totpService = totpService;
        this.jwtService = jwtService;
        this.recoveryCodesService = recoveryCodesService;
    }

    @PostMapping("/two-factor-challenge")
    @Transactional
    public ResponseEntity<?> challenge(@Valid @RequestBody TwoFactorChallengeRequest request) {
        String email = request.email().trim().toLowerCase();
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            return ApiResponses.unprocessable("Invalid credentials.");
        }
        if (!user.hasConfirmedTwoFactor()) {
            return ApiResponses.unprocessable("Two factor authentication is not enabled.");
        }

        boolean ok = false;
        if (request.code() != null && !request.code().isBlank()) {
            ok = totpService.verifyCode(user.getTwoFactorSecret(), request.code());
        } else if (request.recoveryCode() != null && !request.recoveryCode().isBlank()) {
            ok = recoveryCodesService.consume(user, request.recoveryCode().trim());
            if (ok) {
                userRepository.save(user);
            }
        }

        if (!ok) {
            return ApiResponses.unprocessable(
                    "Invalid two factor code.",
                    Map.of("code", List.of("Invalid two factor code.")));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", ApiJson.apiUserJson(user));
        body.put("token", jwtService.createToken(user));
        return ResponseEntity.ok(body);
    }
}
