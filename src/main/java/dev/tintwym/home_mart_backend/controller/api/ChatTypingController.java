package dev.tintwym.home_mart_backend.controller.api;

import dev.tintwym.home_mart_backend.entity.Conversation;
import dev.tintwym.home_mart_backend.entity.Listing;
import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.repository.ConversationRepository;
import dev.tintwym.home_mart_backend.repository.ListingRepository;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import dev.tintwym.home_mart_backend.service.ChatTypingService;
import dev.tintwym.home_mart_backend.utility.ApiResponses;
import dev.tintwym.home_mart_backend.utility.AuthSupport;
import java.util.LinkedHashMap;
import java.util.Map;
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

    private final ConversationRepository conversationRepository;
    private final ListingRepository listingRepository;
    private final UserRepository userRepository;
    private final ChatTypingService chatTypingService;

    public ChatTypingController(
            ConversationRepository conversationRepository,
            ListingRepository listingRepository,
            UserRepository userRepository,
            ChatTypingService chatTypingService) {
        this.conversationRepository = conversationRepository;
        this.listingRepository = listingRepository;
        this.userRepository = userRepository;
        this.chatTypingService = chatTypingService;
    }

    @PostMapping("/conversations/{id}/typing")
    @Transactional(readOnly = true)
    public ResponseEntity<?> setTyping(@PathVariable String id) {
        String userId = AuthSupport.currentUserId();
        if (!isParticipant(id, userId)) {
            return ApiResponses.notFound("Conversation not found.");
        }
        User user = userRepository.findById(userId).orElse(null);
        chatTypingService.setTyping(id, userId, user == null ? null : user.getName());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/conversations/{id}/typing")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getTyping(@PathVariable String id) {
        String userId = AuthSupport.currentUserId();
        if (!isParticipant(id, userId)) {
            return ApiResponses.notFound("Conversation not found.");
        }
        ChatTypingService.TypingSnapshot snap = chatTypingService.otherTyping(id, userId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("typing", snap.typing());
        body.put("user_name", snap.userName() == null ? "" : snap.userName());
        return ResponseEntity.ok(body);
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
}
