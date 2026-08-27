package dev.tintwym.home_mart_backend.controller.api;

import dev.tintwym.home_mart_backend.service.AuthCookieService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class LogoutController {

    private final AuthCookieService authCookieService;

    public LogoutController(AuthCookieService authCookieService) {
        this.authCookieService = authCookieService;
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletResponse response) {
        authCookieService.clearTokenCookie(response);
        authCookieService.clearTwoFactorPending(response);
        return ResponseEntity.ok(Map.of("message", "Logged out."));
    }
}
