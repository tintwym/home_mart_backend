package dev.tintwym.home_mart_backend.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import dev.tintwym.home_mart_backend.config.GeminiProperties;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
    private static final int MAX_HISTORY = 12;

    private static final String SYSTEM_PROMPT =
            """
            You are Home Mart Assistant, a helpful guide for the Home Mart marketplace \
            (buy and sell furniture and home goods in Myanmar, Vietnam, Singapore, and more).

            Help with: browsing listings, favorites, cart, checkout (including MMQR / bank wallets \
            at a high level), selling/creating listings, messaging sellers, account settings, \
            and general shopping tips.

            Rules:
            - Reply in the same language the user writes in when possible.
            - Be concise and practical (a few short paragraphs or bullets).
            - Do not invent order numbers, payment confirmations, or account balances.
            - For account-specific or payment disputes, tell the user to check Orders/Settings \
              or message the seller in Inbox.
            - Do not ask for passwords, NRC, bank PINs, or full card numbers.
            - You are not a human agent; if they need a person, suggest contacting support via Settings.
            """;

    private final GeminiProperties geminiProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public AssistantService(GeminiProperties geminiProperties, ObjectMapper objectMapper) {
        this.geminiProperties = geminiProperties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create();
    }

    public boolean isConfigured() {
        return geminiProperties.isConfigured();
    }

    public String chat(String message, List<HistoryTurn> history, String userIdOrNull)
            throws AssistantException {
        if (!geminiProperties.isConfigured()) {
            throw new AssistantException(
                    AssistantException.Kind.NOT_CONFIGURED,
                    "AI assistant is not configured. Set GEMINI_API_KEY on the server.");
        }

        String trimmed = message == null ? "" : message.trim();
        if (trimmed.isEmpty()) {
            throw new AssistantException(AssistantException.Kind.BAD_REQUEST, "Message is required.");
        }
        if (trimmed.length() > 2000) {
            throw new AssistantException(
                    AssistantException.Kind.BAD_REQUEST, "Message is too long (max 2000 characters).");
        }

        try {
            ObjectNode body = objectMapper.createObjectNode();
            ObjectNode systemInstruction = body.putObject("systemInstruction");
            ArrayNode sysParts = systemInstruction.putArray("parts");
            String systemText = SYSTEM_PROMPT;
            if (userIdOrNull != null && !userIdOrNull.isBlank()) {
                systemText += "\nThe user is signed in (user id available to the app, not for display).";
            }
            sysParts.addObject().put("text", systemText);

            ArrayNode contents = body.putArray("contents");
            List<HistoryTurn> capped = capHistory(history);
            for (HistoryTurn turn : capped) {
                String role = "model".equalsIgnoreCase(turn.role()) ? "model" : "user";
                ObjectNode content = contents.addObject();
                content.put("role", role);
                content.putArray("parts").addObject().put("text", turn.text());
            }
            ObjectNode userContent = contents.addObject();
            userContent.put("role", "user");
            userContent.putArray("parts").addObject().put("text", trimmed);

            ObjectNode generationConfig = body.putObject("generationConfig");
            generationConfig.put("temperature", 0.7);
            generationConfig.put("maxOutputTokens", 1024);

            String model = geminiProperties.getModel() == null || geminiProperties.getModel().isBlank()
                    ? "gemini-2.0-flash"
                    : geminiProperties.getModel().trim();
            String url =
                    "https://generativelanguage.googleapis.com/v1beta/models/"
                            + model
                            + ":generateContent?key="
                            + geminiProperties.getApiKey().trim();

            String raw = restClient
                    .post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);

            return extractReply(raw);
        } catch (AssistantException e) {
            throw e;
        } catch (RestClientResponseException e) {
            log.warn("Gemini API error {}: {}", e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new AssistantException(
                    AssistantException.Kind.UPSTREAM,
                    "The AI service is temporarily unavailable. Please try again.");
        } catch (Exception e) {
            log.warn("Assistant chat failed: {}", e.getMessage());
            throw new AssistantException(
                    AssistantException.Kind.UPSTREAM,
                    "Could not reach the AI service. Please try again.");
        }
    }

    private String extractReply(String raw) throws Exception {
        if (raw == null || raw.isBlank()) {
            throw new AssistantException(AssistantException.Kind.UPSTREAM, "Empty AI response.");
        }
        JsonNode root = objectMapper.readTree(raw);
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            String block = text(root.path("promptFeedback").path("blockReason"));
            if (!block.isBlank()) {
                throw new AssistantException(
                        AssistantException.Kind.UPSTREAM,
                        "That message could not be answered. Try rephrasing.");
            }
            throw new AssistantException(AssistantException.Kind.UPSTREAM, "Empty AI response.");
        }
        JsonNode parts = candidates.get(0).path("content").path("parts");
        StringBuilder sb = new StringBuilder();
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                String text = text(part.path("text"));
                if (!text.isBlank()) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(text.trim());
                }
            }
        }
        if (sb.isEmpty()) {
            throw new AssistantException(AssistantException.Kind.UPSTREAM, "Empty AI response.");
        }
        return sb.toString();
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        return node.asString("");
    }

    private static List<HistoryTurn> capHistory(List<HistoryTurn> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<HistoryTurn> cleaned = new ArrayList<>();
        for (HistoryTurn turn : history) {
            if (turn == null || turn.text() == null || turn.text().isBlank()) {
                continue;
            }
            String text = turn.text().trim();
            if (text.length() > 2000) {
                text = text.substring(0, 2000);
            }
            cleaned.add(new HistoryTurn(turn.role(), text));
        }
        if (cleaned.size() <= MAX_HISTORY) {
            return cleaned;
        }
        return cleaned.subList(cleaned.size() - MAX_HISTORY, cleaned.size());
    }

    public record HistoryTurn(String role, String text) {
    }

    public static class AssistantException extends Exception {
        public enum Kind {
            NOT_CONFIGURED,
            BAD_REQUEST,
            UPSTREAM,
            RATE_LIMITED
        }

        private final Kind kind;

        public AssistantException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public Kind getKind() {
            return kind;
        }
    }
}
