package dev.tintwym.home_mart_backend.controller.api;

import dev.tintwym.home_mart_backend.entity.Conversation;
import dev.tintwym.home_mart_backend.entity.ConversationRead;
import dev.tintwym.home_mart_backend.entity.Listing;
import dev.tintwym.home_mart_backend.entity.Message;
import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.repository.ConversationReadRepository;
import dev.tintwym.home_mart_backend.repository.ConversationRepository;
import dev.tintwym.home_mart_backend.repository.ListingRepository;
import dev.tintwym.home_mart_backend.repository.MessageRepository;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import dev.tintwym.home_mart_backend.mapper.ApiJson;
import dev.tintwym.home_mart_backend.utility.UlidService;
import dev.tintwym.home_mart_backend.utility.ApiResponses;
import dev.tintwym.home_mart_backend.utility.AuthSupport;
import dev.tintwym.home_mart_backend.dto.ApiRequests.MessageRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ConversationReadRepository conversationReadRepository;
    private final ListingRepository listingRepository;
    private final UserRepository userRepository;

    public ChatController(
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            ConversationReadRepository conversationReadRepository,
            ListingRepository listingRepository,
            UserRepository userRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.conversationReadRepository = conversationReadRepository;
        this.listingRepository = listingRepository;
        this.userRepository = userRepository;
    }

    @GetMapping("/conversations")
    @Transactional(readOnly = true)
    public ResponseEntity<?> conversations(@RequestParam(defaultValue = "30") int limit) {
        String userId = AuthSupport.currentUserId();
        int capped = Math.max(1, Math.min(100, limit));
        List<Conversation> conversations = conversationRepository.findForUser(userId).stream()
                .limit(capped)
                .toList();

        List<Map<String, Object>> data = new ArrayList<>();
        for (Conversation conv : conversations) {
            Listing listing = listingRepository
                    .findByIdWithRelations(conv.getListingId())
                    .orElse(null);
            List<Message> messages = messageRepository.findByConversationIdOrderByCreatedAtDesc(conv.getId());
            Message last = messages.isEmpty() ? null : messages.get(0);
            long unread = messageRepository.countByConversationIdAndUserIdNotAndReadAtIsNull(conv.getId(), userId);

            User other;
            if (userId.equals(conv.getBuyerId())) {
                other = listing == null ? null : listing.getUser();
            } else {
                other = userRepository.findById(conv.getBuyerId()).orElse(null);
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", conv.getId());
            row.put("title", other != null ? other.getName() : (listing != null ? listing.getTitle() : "Conversation"));
            if (listing != null) {
                Map<String, Object> listingMap = new LinkedHashMap<>();
                listingMap.put("id", listing.getId());
                listingMap.put("title", listing.getTitle());
                listingMap.put("image_path", listing.getImagePath());
                listingMap.put("price", listing.getPrice());
                listingMap.put("seller", listing.getUser() == null ? null : ApiJson.userSummaryJson(listing.getUser()));
                row.put("listing", listingMap);
            } else {
                row.put("listing", null);
            }
            row.put("last_message", last == null ? null : last.getBody());
            row.put("last_message_at", last == null ? null : ApiJson.formatInstant(last.getCreatedAt()));
            row.put("unread_count", unread);
            row.put("updated_at", ApiJson.formatInstant(conv.getUpdatedAt()));
            data.add(row);
        }
        return ResponseEntity.ok(Map.of("conversations", data));
    }

    @PostMapping("/listings/{listingId}/chat")
    @Transactional
    public ResponseEntity<?> startChat(@PathVariable String listingId) {
        String userId = AuthSupport.currentUserId();
        Listing listing = listingRepository.findById(listingId).orElse(null);
        if (listing == null) {
            return ApiResponses.notFound("Listing not found.");
        }
        if (userId.equals(listing.getUserId())) {
            return ApiResponses.unprocessable("You cannot chat about your own listing.");
        }
        try {
            Conversation conversation = conversationRepository
                    .findByListingIdAndBuyerId(listingId, userId)
                    .orElseGet(() -> {
                        Conversation created = new Conversation();
                        created.setId(UlidService.newUlid());
                        created.setListingId(listingId);
                        created.setBuyerId(userId);
                        return conversationRepository.save(created);
                    });
            return ResponseEntity.ok(Map.of("conversation_id", conversation.getId()));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            Conversation existing = conversationRepository
                    .findByListingIdAndBuyerId(listingId, userId)
                    .orElse(null);
            if (existing == null) {
                return ApiResponses.unprocessable("Could not start chat.");
            }
            return ResponseEntity.ok(Map.of("conversation_id", existing.getId()));
        }
    }

    @GetMapping("/conversations/{id}/messages")
    @Transactional
    public ResponseEntity<?> messages(
            @PathVariable String id, @RequestParam(required = false) String after) {
        String userId = AuthSupport.currentUserId();
        Conversation conversation = conversationRepository.findById(id).orElse(null);
        if (conversation == null) {
            return ApiResponses.notFound("Conversation not found.");
        }
        Listing listing = listingRepository.findById(conversation.getListingId()).orElse(null);
        if (listing == null || !isParticipant(userId, conversation, listing)) {
            return ApiResponses.notFound("Conversation not found.");
        }

        Instant afterInstant = null;
        if (after != null && !after.isBlank()) {
            try {
                afterInstant = Instant.parse(after);
            } catch (Exception ignored) {
                afterInstant = null;
            }
        }

        Instant now = Instant.now();
        List<Message> all = messageRepository.findByConversationIdOrderByCreatedAtAsc(id);
        for (Message msg : all) {
            if (!userId.equals(msg.getUserId()) && msg.getReadAt() == null) {
                msg.setReadAt(now);
                messageRepository.save(msg);
            }
        }
        upsertRead(id, userId, now);

        Instant finalAfter = afterInstant;
        List<Message> filtered;
        if (finalAfter == null) {
            // Initial load: newest 200 (not oldest).
            List<Message> newestFirst = messageRepository.findByConversationIdOrderByCreatedAtDesc(id);
            boolean hasMore = newestFirst.size() > 200;
            List<Message> page = hasMore ? newestFirst.subList(0, 200) : newestFirst;
            filtered = new ArrayList<>(page);
            filtered.sort(Comparator.comparing(Message::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
            List<Map<String, Object>> messages = new ArrayList<>();
            for (Message msg : filtered) {
                messages.add(messageJson(msg, userId));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("conversation", Map.of("id", conversation.getId()));
            body.put("messages", messages);
            body.put("has_more", hasMore);
            return ResponseEntity.ok(body);
        }

        filtered = all.stream()
                .filter(m -> m.getCreatedAt() != null && m.getCreatedAt().isAfter(finalAfter))
                .sorted(Comparator.comparing(Message::getCreatedAt))
                .limit(200)
                .toList();

        List<Map<String, Object>> messages = new ArrayList<>();
        for (Message msg : filtered) {
            messages.add(messageJson(msg, userId));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversation", Map.of("id", conversation.getId()));
        body.put("messages", messages);
        body.put("has_more", false);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/conversations/{id}/messages")
    @Transactional
    public ResponseEntity<?> send(@PathVariable String id, @RequestBody MessageRequest request) {
        String userId = AuthSupport.currentUserId();
        Conversation conversation = conversationRepository.findById(id).orElse(null);
        if (conversation == null) {
            return ApiResponses.notFound("Conversation not found.");
        }
        Listing listing = listingRepository.findById(conversation.getListingId()).orElse(null);
        if (listing == null || !isParticipant(userId, conversation, listing)) {
            return ApiResponses.notFound("Conversation not found.");
        }

        String bodyText = request.body();
        if (bodyText == null || bodyText.isBlank()) {
            return ApiResponses.unprocessable(
                    "Message body is required.", Map.of("body", List.of("Message body is required.")));
        }
        if (bodyText.length() > 2000) {
            return ApiResponses.unprocessable("Message is too long.");
        }

        Message msg = new Message();
        msg.setId(UlidService.newUlid());
        msg.setConversationId(id);
        msg.setUserId(userId);
        msg.setBody(bodyText.trim());
        messageRepository.save(msg);

        conversation.setUpdatedAt(Instant.now());
        conversationRepository.save(conversation);
        upsertRead(id, userId, Instant.now());

        Map<String, Object> payload = messageJson(msg, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("message", payload));
    }

    private void upsertRead(String conversationId, String userId, Instant at) {
        ConversationRead read = conversationReadRepository
                .findByConversationIdAndUserId(conversationId, userId)
                .orElseGet(() -> {
                    ConversationRead created = new ConversationRead();
                    created.setId(UlidService.newUlid());
                    created.setConversationId(conversationId);
                    created.setUserId(userId);
                    return created;
                });
        read.setLastReadAt(at);
        conversationReadRepository.save(read);
    }

    private static boolean isParticipant(String userId, Conversation conversation, Listing listing) {
        return userId.equals(conversation.getBuyerId()) || userId.equals(listing.getUserId());
    }

    private Map<String, Object> messageJson(Message msg, String currentUserId) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", msg.getId());
        map.put("text", msg.getBody());
        map.put("body", msg.getBody());
        map.put("sent_at", ApiJson.formatInstant(msg.getCreatedAt()));
        map.put("is_me", currentUserId.equals(msg.getUserId()));
        map.put("read_at", ApiJson.formatInstant(msg.getReadAt()));
        String status = msg.getReadAt() != null ? "seen" : "delivered";
        map.put("status", status);
        User sender = userRepository.findById(msg.getUserId()).orElse(null);
        map.put("sender", sender == null ? null : Map.of("id", sender.getId(), "name", sender.getName()));
        return map;
    }
}
