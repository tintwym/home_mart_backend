package dev.tintwym.home_mart_backend.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** Shared in-memory typing indicators for web + API chat clients. */
@Service
public class ChatTypingService {

    private static final long TTL_MS = 5_000L;

    private final ConcurrentHashMap<String, TypingEntry> typing = new ConcurrentHashMap<>();

    public void setTyping(String conversationId, String userId, String userName) {
        prune();
        typing.put(
                key(conversationId, userId),
                new TypingEntry(userId, userName, System.currentTimeMillis() + TTL_MS));
    }

    public TypingSnapshot otherTyping(String conversationId, String currentUserId) {
        prune();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, TypingEntry> e : typing.entrySet()) {
            if (!e.getKey().startsWith(conversationId + ".")) {
                continue;
            }
            TypingEntry entry = e.getValue();
            if (entry.expiresAt() < now || currentUserId.equals(entry.userId())) {
                continue;
            }
            return new TypingSnapshot(true, entry.userName());
        }
        return new TypingSnapshot(false, null);
    }

    private void prune() {
        long now = System.currentTimeMillis();
        typing.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
    }

    private static String key(String conversationId, String userId) {
        return conversationId + "." + userId;
    }

    public record TypingSnapshot(boolean typing, String userName) {}

    private record TypingEntry(String userId, String userName, long expiresAt) {}
}
