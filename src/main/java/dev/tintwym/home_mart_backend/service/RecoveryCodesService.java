package dev.tintwym.home_mart_backend.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.utility.CryptoSupport;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Shared 2FA recovery-code storage. Prefer JSON array; accept legacy newline-separated
 * values so page and API paths stay compatible.
 */
@Service
public class RecoveryCodesService {

    private final ObjectMapper objectMapper;

    public RecoveryCodesService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<String> read(User user) {
        String raw = user.getTwoFactorRecoveryCodes();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("[")) {
            try {
                return objectMapper.readValue(trimmed, new TypeReference<>() {
                });
            } catch (Exception ignored) {
                // fall through to newline parse
            }
        }
        List<String> codes = new ArrayList<>();
        for (String line : trimmed.split("\\R")) {
            String code = line.trim();
            if (!code.isEmpty()) {
                codes.add(code);
            }
        }
        return codes;
    }

    public String write(List<String> codes) {
        try {
            return objectMapper.writeValueAsString(codes == null ? List.of() : codes);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** Consumes a matching recovery code (case-insensitive). Returns true if consumed. */
    public boolean consume(User user, String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        List<String> codes = new ArrayList<>(read(user));
        String match = null;
        for (String existing : codes) {
            if (CryptoSupport.constantTimeEquals(existing.trim().toLowerCase(), code.trim().toLowerCase())) {
                match = existing;
                break;
            }
        }
        if (match == null) {
            return false;
        }
        codes.remove(match);
        user.setTwoFactorRecoveryCodes(write(codes));
        return true;
    }
}
