package dev.tintwym.home_mart_backend.controller.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sanctum-compatible CSRF cookie endpoint (no-op for JWT/stateless auth).
 */
@RestController
public class SanctumController {

    @GetMapping("/sanctum/csrf-cookie")
    public ResponseEntity<Void> csrfCookie() {
        return ResponseEntity.noContent().build();
    }
}
