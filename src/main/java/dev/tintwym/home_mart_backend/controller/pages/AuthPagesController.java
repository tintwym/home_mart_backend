package dev.tintwym.home_mart_backend.controller.pages;

import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import dev.tintwym.home_mart_backend.service.AuthCookieService;
import dev.tintwym.home_mart_backend.service.FirebaseAuthService;
import dev.tintwym.home_mart_backend.service.GeoRegionService;
import dev.tintwym.home_mart_backend.service.InertiaService;
import dev.tintwym.home_mart_backend.service.JwtService;
import dev.tintwym.home_mart_backend.utility.UlidService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthPagesController extends PageControllerSupport {

    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthCookieService authCookieService;
    private final GeoRegionService geoRegionService;
    private final FirebaseAuthService firebaseAuthService;

    public AuthPagesController(
            InertiaService inertia,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            AuthCookieService authCookieService,
            GeoRegionService geoRegionService,
            FirebaseAuthService firebaseAuthService) {
        super(inertia, userRepository);
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authCookieService = authCookieService;
        this.geoRegionService = geoRegionService;
        this.firebaseAuthService = firebaseAuthService;
    }

    @GetMapping("/login")
    public ResponseEntity<?> loginForm(HttpServletRequest request, HttpServletResponse response) {
        if (currentUserId().isPresent()) {
            return redirect(request, "/");
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("canResetPassword", true);
        props.put("canRegister", true);
        props.put("status", null);
        props.put("passkeyError", null);
        return render(request, response, "auth/login", props);
    }

    @PostMapping("/login")
    @Transactional(readOnly = true)
    public ResponseEntity<?> login(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam String email,
            @RequestParam String password) {
        Optional<User> userOpt = userRepository.findByEmail(email.trim().toLowerCase());
        if (userOpt.isEmpty() || !passwordEncoder.matches(password, userOpt.get().getPassword())) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("canResetPassword", true);
            props.put("canRegister", true);
            props.put("errors", errors("email", "These credentials do not match our records."));
            return render(request, response, "auth/login", props);
        }
        User user = userOpt.get();
        if (user.hasConfirmedTwoFactor()) {
            authCookieService.setTwoFactorPending(response, user.getId());
            return redirect(request, "/two-factor-challenge");
        }
        String token = jwtService.createToken(user);
        authCookieService.setTokenCookie(response, token);
        return redirectWithStatus(request, response, "/", "Welcome back.");
    }

    @GetMapping("/register")
    public ResponseEntity<?> registerForm(HttpServletRequest request, HttpServletResponse response) {
        if (currentUserId().isPresent()) {
            return redirect(request, "/");
        }
        return render(request, response, "auth/register", Map.of());
    }

    @PostMapping("/register")
    @Transactional
    public ResponseEntity<?> register(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam String name,
            @RequestParam String email,
            @RequestParam String password,
            @RequestParam(value = "password_confirmation", required = false) String passwordConfirmation,
            @RequestParam(value = "seller_type", required = false) String sellerType) {
        String normalizedEmail = email.trim().toLowerCase();
        Map<String, Object> fieldErrors = new LinkedHashMap<>();
        if (name == null || name.isBlank()) {
            fieldErrors.put("name", "Name is required.");
        }
        if (normalizedEmail.isBlank()) {
            fieldErrors.put("email", "Email is required.");
        } else if (userRepository.existsByEmail(normalizedEmail)) {
            fieldErrors.put("email", "Email has already been taken.");
        }
        if (password == null || password.length() < 8) {
            fieldErrors.put("password", "Password must be at least 8 characters.");
        } else if (passwordConfirmation != null && !password.equals(passwordConfirmation)) {
            fieldErrors.put("password", "Password confirmation does not match.");
        }
        if (!fieldErrors.isEmpty()) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("errors", fieldErrors);
            return render(request, response, "auth/register", props);
        }

        User user = new User();
        user.setId(UlidService.newUlid());
        user.setName(name.trim());
        user.setEmail(normalizedEmail);
        user.setPassword(passwordEncoder.encode(password));
        user.setAuthProvider("password");
        user.setSellerType(sellerType == null || sellerType.isBlank() ? "individual" : sellerType);
        user.setRegion(geoRegionService.detect(request));
        userRepository.save(user);

        String token = jwtService.createToken(user);
        authCookieService.setTokenCookie(response, token);
        return redirectWithStatus(request, response, "/", "Account created.");
    }

    @PostMapping("/auth/firebase")
    @Transactional
    public ResponseEntity<?> firebaseLogin(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam(value = "id_token", required = false) String idToken,
            @RequestParam(value = "idToken", required = false) String idTokenCamel) {
        String tokenValue = idToken != null && !idToken.isBlank() ? idToken : idTokenCamel;
        try {
            FirebaseAuthService.VerifiedIdentity identity = firebaseAuthService.verifyIdToken(tokenValue);
            User user = firebaseAuthService.findOrCreateUser(identity, geoRegionService.detect(request));
            String jwt = jwtService.createToken(user);
            authCookieService.setTokenCookie(response, jwt);
            return redirectWithStatus(request, response, "/", "Welcome back.");
        } catch (IllegalStateException e) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("canResetPassword", true);
            props.put("canRegister", true);
            props.put("status", null);
            props.put("passkeyError", null);
            props.put("errors", errors("email", e.getMessage()));
            return render(request, response, "auth/login", props);
        } catch (IllegalArgumentException e) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("canResetPassword", true);
            props.put("canRegister", true);
            props.put("status", null);
            props.put("passkeyError", null);
            props.put("errors", errors("email", e.getMessage() == null ? "Social sign-in failed." : e.getMessage()));
            return render(request, response, "auth/login", props);
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {
        authCookieService.clearTokenCookie(response);
        authCookieService.clearTwoFactorPending(response);
        return redirect(request, "/");
    }
}
