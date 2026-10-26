package dev.tintwym.home_mart_backend.service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Simple in-memory sliding-window limiter for public assistant chat.
 * Keyed by IP and optional user id (max 20 requests / 10 minutes per key).
 */
@Component
public class AssistantRateLimiter {

    private static final int MAX_REQUESTS = 20;
    private static final long WINDOW_MS = 10 * 60 * 1000L;

    private final Map<String, Deque<Long>> buckets = new ConcurrentHashMap<>();

    public boolean tryAcquire(String key) {
        if (key == null || key.isBlank()) {
            key = "anonymous";
        }
        long now = Instant.now().toEpochMilli();
        Deque<Long> times = buckets.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (times) {
            prune(times, now);
            if (times.size() >= MAX_REQUESTS) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }

    private static void prune(Deque<Long> times, long now) {
        long cutoff = now - WINDOW_MS;
        Iterator<Long> it = times.iterator();
        while (it.hasNext()) {
            if (it.next() < cutoff) {
                it.remove();
            } else {
                break;
            }
        }
    }
}
