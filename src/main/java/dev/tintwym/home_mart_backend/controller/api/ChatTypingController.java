package dev.tintwym.home_mart_backend.controller.api;

import dev.tintwym.home_mart_backend.entity.Conversation;
import dev.tintwym.home_mart_backend.entity.Listing;
import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.repository.ConversationRepository;
import dev.tintwym.home_mart_backend.repository.ListingRepository;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import dev.tintwym.home_mart_backend.utility.ApiResponses;
import dev.tintwym.home_mart_backend.utility.AuthSupport;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ChatTypingController {

    private static final long TTL_MS = 5_000L;
    private static final ConcurrentHashMap<String, TypingEntry> TYPING = new ConcurrentHashMap<>();

    private final ConversationRepository conversationRepository;
    private final ListingRepository listingRepository;
    private final UserRepository userRepository;

    public ChatTypingController(
            ConversationRepository conversationRepository,
            ListingRepository listingRepository,
            UserRepository userRepository) {
        this.conversationRepository = conversationRepository;
        this.listingRepository = listingRepository;
        this.userRepository = userRepository;
    }

    @PostMapping("/conversations/{id}/typing")
    @Transactional(readOnly = true)
    public ResponseEntity<?> setTyping(@PathVariable String id) {
        String userId = AuthSupport.currentUserId();
        if (!isParticipant(id, userId)) {
            return ApiResponses.notFound("Conversation not found.");
        }
        User user = userRepository.findById(userId).orElse(null);
        TYPING.put(
                id,
                new TypingEntry(userId, user == null ? null : user.getName(), System.currentTimeMillis() + TTL_MS));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/conversations/{id}/typing")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getTyping(@PathVariable String id) {
        String userId = AuthSupport.currentUserId();
        if (!isParticipant(id, userId)) {
            return ApiResponses.notFound("Conversation not found.");
        }
        TypingEntry entry = TYPING.get(id);
        long now = System.currentTimeMillis();
        if (entry == null || entry.expiresAt() < now || userId.equals(entry.userId())) {
            return ResponseEntity.ok(Map.of("typing", false, "user_name", ""));
        }
        String name = entry.userName() == null ? "" : entry.userName();
        return ResponseEntity.ok(Map.of("typing", true, "user_name", name));
    }

    private boolean isParticipant(String conversationId, String userId) {
        Conversation conversation = conversationRepository.findById(conversationId).orElse(null);
        if (conversation == null) {
            return false;
        }
        Listing listing = listingRepository.findById(conversation.getListingId()).orElse(null);
        if (listing == null) {
            return false;
        }
        return userId.equals(conversation.getBuyerId()) || userId.equals(listing.getUserId());
    }

    private record TypingEntry(String userId, String userName, long expiresAt) {
    }
}
