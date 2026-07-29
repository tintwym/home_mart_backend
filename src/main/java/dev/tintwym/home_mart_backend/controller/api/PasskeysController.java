package dev.tintwym.home_mart_backend.controller.api;

import dev.tintwym.home_mart_backend.entity.Passkey;
import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import dev.tintwym.home_mart_backend.mapper.ApiJson;
import dev.tintwym.home_mart_backend.service.JwtService;
import dev.tintwym.home_mart_backend.service.PasskeyService;
import dev.tintwym.home_mart_backend.service.PasskeyService.PasskeyException;
import dev.tintwym.home_mart_backend.utility.ApiResponses;
import dev.tintwym.home_mart_backend.utility.AuthSupport;
import dev.tintwym.home_mart_backend.dto.ApiRequests.PasskeyAuthOptionsRequest;
import dev.tintwym.home_mart_backend.dto.ApiRequests.PasskeyAuthenticateRequest;
import dev.tintwym.home_mart_backend.dto.ApiRequests.PasskeyRegisterRequest;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PasskeysController {

    private final PasskeyService passkeyService;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    public PasskeysController(
            PasskeyService passkeyService, UserRepository userRepository, JwtService jwtService) {
        this.passkeyService = passkeyService;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    @GetMapping("/settings/passkeys/register-options")
    @Transactional(readOnly = true)
    public ResponseEntity<?> registerOptions() {
        User user = requireUser();
        return ResponseEntity.ok(passkeyService.createRegistrationOptions(user));
    }

    @PostMapping("/settings/passkeys")
    @Transactional
    public ResponseEntity<?> register(@Valid @RequestBody PasskeyRegisterRequest request) {
        User user = requireUser();
        try {
            Passkey passkey = passkeyService.completeRegistration(
                    user, request.state(), request.name(), request.credential());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("data", passkeyService.passkeyJson(passkey)));
        } catch (PasskeyException e) {
            return ApiResponses.unprocessable(e.getMessage());
        }
    }

    @GetMapping("/settings/passkeys")
    @Transactional(readOnly = true)
    public ResponseEntity<?> list() {
        List<Map<String, Object>> data = passkeyService.listForUser(AuthSupport.currentUserId()).stream()
                .map(passkeyService::passkeyJson)
                .toList();
        return ResponseEntity.ok(Map.of("data", data));
    }

    @DeleteMapping("/settings/passkeys/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable long id) {
        if (!passkeyService.deleteForUser(AuthSupport.currentUserId(), id)) {
            return ApiResponses.notFound("Passkey not found.");
        }
        return ResponseEntity.ok(Map.of("message", "Passkey deleted."));
    }

    @PostMapping("/passkeys/authentication-options")
    public ResponseEntity<?> authenticationOptions(@RequestBody(required = false) PasskeyAuthOptionsRequest request) {
        String email = request == null ? null : request.email();
        return ResponseEntity.ok(passkeyService.createAuthenticationOptions(email));
    }

    @PostMapping("/passkeys/authenticate")
    @Transactional
    public ResponseEntity<?> authenticate(@Valid @RequestBody PasskeyAuthenticateRequest request) {
        try {
            User user = passkeyService.completeAuthentication(request.state(), request.credential());
            if (user.hasConfirmedTwoFactor()) {
                return ApiResponses.unprocessable(
                        "Two factor authentication is required.",
                        Map.of(
                                "two_factor",
                                java.util.List.of("Two factor authentication is required."),
                                "two_factor_required",
                                true));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("user", ApiJson.apiUserJson(user));
            body.put("token", jwtService.createToken(user));
            return ResponseEntity.ok(body);
        } catch (PasskeyException e) {
            return ApiResponses.unprocessable(e.getMessage());
        }
    }

    private User requireUser() {
        return userRepository
                .findById(AuthSupport.currentUserId())
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Unauthenticated."));
    }
}
