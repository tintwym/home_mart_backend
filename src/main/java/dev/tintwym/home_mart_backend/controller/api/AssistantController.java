package dev.tintwym.home_mart_backend.controller.api;

import dev.tintwym.home_mart_backend.dto.ApiRequests.AssistantChatRequest;
import dev.tintwym.home_mart_backend.dto.ApiRequests.AssistantHistoryTurn;
import dev.tintwym.home_mart_backend.service.AssistantRateLimiter;
import dev.tintwym.home_mart_backend.service.AssistantService;
import dev.tintwym.home_mart_backend.service.AssistantService.AssistantException;
import dev.tintwym.home_mart_backend.service.AssistantService.HistoryTurn;
import dev.tintwym.home_mart_backend.utility.ApiResponses;
import dev.tintwym.home_mart_backend.utility.AuthSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AssistantController {

    private final AssistantService assistantService;
    private final AssistantRateLimiter rateLimiter;

    public AssistantController(AssistantService assistantService, AssistantRateLimiter rateLimiter) {
        this.assistantService = assistantService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/assistant/chat")
    public ResponseEntity<?> chat(
            @Valid @RequestBody AssistantChatRequest request, HttpServletRequest httpRequest) {
        if (!assistantService.isConfigured()) {
            return ApiResponses.serviceUnavailable(
                    "AI assistant is not configured. Set GEMINI_API_KEY on the server.");
        }

        String userId = AuthSupport.currentUserIdOrNull();
        String clientKey = rateLimitKey(httpRequest, userId);
        if (!rateLimiter.tryAcquire(clientKey)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("message", "Too many assistant requests. Please wait a few minutes."));
        }

        List<HistoryTurn> history = new ArrayList<>();
        if (request.history() != null) {
            for (AssistantHistoryTurn turn : request.history()) {
                if (turn == null) {
                    continue;
                }
                history.add(new HistoryTurn(turn.role(), turn.text()));
            }
        }

        try {
            String reply = assistantService.chat(request.message(), history, userId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("reply", reply);
            return ResponseEntity.ok(body);
        } catch (AssistantException e) {
            return switch (e.getKind()) {
                case NOT_CONFIGURED -> ApiResponses.serviceUnavailable(e.getMessage());
                case BAD_REQUEST -> ApiResponses.badRequest(e.getMessage());
                case RATE_LIMITED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .body(Map.of("message", e.getMessage()));
                case UPSTREAM -> ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of("message", e.getMessage()));
            };
        }
    }

    private static String rateLimitKey(HttpServletRequest request, String userId) {
        if (userId != null && !userId.isBlank()) {
            return "user:" + userId;
        }
        String ip = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            ip = forwarded.split(",")[0].trim();
        }
        return "ip:" + (ip == null || ip.isBlank() ? "unknown" : ip);
    }
}
