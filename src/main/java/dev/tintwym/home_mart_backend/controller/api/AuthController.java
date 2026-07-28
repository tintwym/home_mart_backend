package dev.tintwym.home_mart_backend.controller.api;

import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import dev.tintwym.home_mart_backend.mapper.ApiJson;
import dev.tintwym.home_mart_backend.service.FirebaseAuthService;
import dev.tintwym.home_mart_backend.service.JwtService;
import dev.tintwym.home_mart_backend.utility.UlidService;
import dev.tintwym.home_mart_backend.utility.ApiResponses;
import dev.tintwym.home_mart_backend.utility.AuthSupport;
import dev.tintwym.home_mart_backend.dto.ApiRequests.FirebaseLoginRequest;
import dev.tintwym.home_mart_backend.dto.ApiRequests.LoginRequest;
import dev.tintwym.home_mart_backend.dto.ApiRequests.RegisterRequest;
import dev.tintwym.home_mart_backend.dto.ApiRequests.UpdatePasswordRequest;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final FirebaseAuthService firebaseAuthService;

    public AuthController(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            FirebaseAuthService firebaseAuthService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.firebaseAuthService = firebaseAuthService;
    }

    @PostMapping("/register")
    @Transactional
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        String email = request.email().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            return ApiResponses.unprocessable(
                    "The email has already been taken.",
                    Map.of("email", java.util.List.of("The email has already been taken.")));
        }

        User user = new User();
        user.setId(UlidService.newUlid());
        user.setName(request.name().trim());
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setAuthProvider("password");
        if (request.sellerType() != null
                && (request.sellerType().equals("individual") || request.sellerType().equals("business"))) {
            user.setSellerType(request.sellerType());
        }
        if (request.region() != null && !request.region().isBlank()) {
            user.setRegion(request.region().trim().toUpperCase());
        }
        userRepository.save(user);

        String token = jwtService.createToken(user);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", ApiJson.apiUserJson(user));
        body.put("token", token);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/login")
    @Transactional(readOnly = true)
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        String email = request.email().trim().toLowerCase();
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            return ApiResponses.unauthorized("Invalid credentials");
        }
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
    }

    @PostMapping("/auth/firebase")
    @Transactional
    public ResponseEntity<?> firebaseLogin(@Valid @RequestBody FirebaseLoginRequest request) {
        try {
            FirebaseAuthService.VerifiedIdentity identity =
                    firebaseAuthService.verifyIdToken(request.idToken());
            User user = firebaseAuthService.findOrCreateUser(identity, request.region());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("user", ApiJson.apiUserJson(user));
            body.put("token", jwtService.createToken(user));
            return ResponseEntity.ok(body);
        } catch (IllegalStateException e) {
            return ApiResponses.serviceUnavailable(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponses.unauthorized(e.getMessage() == null ? "Invalid Firebase token." : e.getMessage());
        }
    }

    @GetMapping("/user")
    @Transactional(readOnly = true)
    public ResponseEntity<?> user() {
        User user = userRepository.findById(AuthSupport.currentUserId()).orElse(null);
        if (user == null) {
            return ApiResponses.unauthorized("Unauthenticated.");
        }
        return ResponseEntity.ok(ApiJson.apiUserJson(user));
    }

    @PutMapping({"/password", "/user/password"})
    @Transactional
    public ResponseEntity<?> updatePassword(@Valid @RequestBody UpdatePasswordRequest request) {
        User user = userRepository.findById(AuthSupport.currentUserId()).orElse(null);
        if (user == null) {
            return ApiResponses.unauthorized("Unauthenticated.");
        }
        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            return ApiResponses.unprocessable(
                    "The current password is incorrect.",
                    Map.of("current_password", java.util.List.of("The current password is incorrect.")));
        }
        user.setPassword(passwordEncoder.encode(request.password()));
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("message", "Password updated."));
    }
}
