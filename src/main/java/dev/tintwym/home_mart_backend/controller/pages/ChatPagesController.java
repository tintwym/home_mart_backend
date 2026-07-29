package dev.tintwym.home_mart_backend.controller.pages;

import dev.tintwym.home_mart_backend.entity.Conversation;
import dev.tintwym.home_mart_backend.entity.ConversationRead;
import dev.tintwym.home_mart_backend.entity.Favorite;
import dev.tintwym.home_mart_backend.entity.Listing;
import dev.tintwym.home_mart_backend.entity.Message;
import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.repository.ConversationReadRepository;
import dev.tintwym.home_mart_backend.repository.ConversationRepository;
import dev.tintwym.home_mart_backend.repository.FavoriteRepository;
import dev.tintwym.home_mart_backend.repository.ListingRepository;
import dev.tintwym.home_mart_backend.repository.MessageRepository;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import dev.tintwym.home_mart_backend.mapper.ApiJson;
import dev.tintwym.home_mart_backend.service.ChatTypingService;
import dev.tintwym.home_mart_backend.service.InertiaService;
import dev.tintwym.home_mart_backend.service.ListingSoldService;
import dev.tintwym.home_mart_backend.utility.UlidService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ChatPagesController extends PageControllerSupport {

    private final FavoriteRepository favoriteRepository;
    private final ListingRepository listingRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ConversationReadRepository conversationReadRepository;
    private final ChatTypingService chatTypingService;
    private final ListingSoldService listingSoldService;

    public ChatPagesController(
            InertiaService inertia,
            UserRepository userRepository,
            FavoriteRepository favoriteRepository,
            ListingRepository listingRepository,
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            ConversationReadRepository conversationReadRepository,
            ChatTypingService chatTypingService,
            ListingSoldService listingSoldService) {
        super(inertia, userRepository);
        this.favoriteRepository = favoriteRepository;
        this.listingRepository = listingRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.conversationReadRepository = conversationReadRepository;
        this.chatTypingService = chatTypingService;
        this.listingSoldService = listingSoldService;
    }

    @GetMapping("/favorites")
    @Transactional(readOnly = true)
    public ResponseEntity<?> favorites(HttpServletRequest request, HttpServletResponse response) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        List<Favorite> favs = favoriteRepository.findByUserId(requireUser().getId());
        favs.sort(Comparator.comparing(Favorite::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        List<String> ids = favs.stream().map(Favorite::getListingId).toList();
        List<Map<String, Object>> listings = ids.isEmpty()
                ? List.of()
                : listingSoldService.toSummaryJsonList(listingRepository.findDetailedByIdIn(ids));
        return render(request, response, "favorites/index", Map.of("listings", listings));
    }

    @GetMapping({"/chat", "/inbox"})
    @Transactional(readOnly = true)
    public ResponseEntity<?> chatIndex(HttpServletRequest request, HttpServletResponse response) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        return render(request, response, "chat/index",
                Map.of("conversations", conversationMaps(requireUser().getId(), 30)));
    }

    @GetMapping({"/chat/{id}", "/inbox/{id}"})
    @Transactional
    public ResponseEntity<?> chatShow(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String id) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        User user = requireUser();
        Conversation conversation = conversationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Listing listing = listingRepository.findById(conversation.getListingId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!user.getId().equals(conversation.getBuyerId()) && !user.getId().equals(listing.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        String otherUserId = user.getId().equals(conversation.getBuyerId())
                ? listing.getUserId()
                : conversation.getBuyerId();

        ConversationRead read = conversationReadRepository
                .findByConversationIdAndUserId(conversation.getId(), user.getId())
                .orElseGet(() -> {
                    ConversationRead cr = new ConversationRead();
                    cr.setId(UlidService.newUlid());
                    cr.setConversationId(conversation.getId());
                    cr.setUserId(user.getId());
                    return cr;
                });
        read.setLastReadAt(Instant.now());
        conversationReadRepository.save(read);

        for (Message msg : messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId())) {
            if (otherUserId.equals(msg.getUserId()) && msg.getReadAt() == null) {
                msg.setReadAt(Instant.now());
                messageRepository.save(msg);
            }
        }

        Instant otherReadAt = conversationReadRepository
                .findByConversationIdAndUserId(conversation.getId(), otherUserId)
                .map(ConversationRead::getLastReadAt)
                .orElse(null);

        List<Message> all = messageRepository.findByConversationIdOrderByCreatedAtDesc(conversation.getId());
        boolean hasMore = all.size() > 50;
        List<Message> page = hasMore ? all.subList(0, 50) : all;
        page = new ArrayList<>(page);
        page.sort(Comparator.comparing(Message::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));

        List<Map<String, Object>> messages = new ArrayList<>();
        for (Message msg : page) {
            messages.add(messageMap(msg, user.getId(), otherReadAt));
        }

        Listing detailed = listingRepository.findDetailedById(listing.getId()).orElse(listing);
        Map<String, Object> convMap = conversationMap(conversation, detailed, user.getId());

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("conversations", conversationMaps(user.getId(), 15));
        props.put("conversation", convMap);
        props.put("messages", messages);
        props.put("messages_has_more", hasMore);
        props.put("poll_after", messages.isEmpty()
                ? ApiJson.formatInstant(conversation.getCreatedAt() != null
                        ? conversation.getCreatedAt()
                        : Instant.EPOCH)
                : messages.get(messages.size() - 1).get("created_at"));
        return render(request, response, "chat/show", props);
    }

    @GetMapping(value = {"/chat/{id}/messages/older", "/inbox/{id}/messages/older"}, produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional(readOnly = true)
    public ResponseEntity<?> messagesOlder(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String id,
            @RequestParam String before) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthenticated."));
        }
        User user = requireUser();
        Conversation conversation = requireParticipant(id, user);
        Instant beforeInstant;
        try {
            beforeInstant = Instant.parse(before);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid before timestamp."));
        }
        Listing listing = listingRepository.findById(conversation.getListingId()).orElseThrow();
        String otherUserId = user.getId().equals(conversation.getBuyerId())
                ? listing.getUserId()
                : conversation.getBuyerId();
        Instant otherReadAt = conversationReadRepository
                .findByConversationIdAndUserId(conversation.getId(), otherUserId)
                .map(ConversationRead::getLastReadAt)
                .orElse(null);

        List<Message> older = messageRepository.findOlderThan(conversation.getId(), beforeInstant);
        boolean hasMore = older.size() > 50;
        List<Message> page = hasMore ? older.subList(0, 50) : older;
        page = new ArrayList<>(page);
        page.sort(Comparator.comparing(Message::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        List<Map<String, Object>> list = page.stream()
                .map(m -> messageMap(m, user.getId(), otherReadAt))
                .toList();
        return ResponseEntity.ok(Map.of("messages", list, "has_more", hasMore));
    }

    @PostMapping({"/chat/{id}/messages", "/inbox/{id}/messages"})
    @Transactional
    public ResponseEntity<?> sendMessage(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String id,
            @RequestParam String body) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        User user = requireUser();
        Conversation conversation = requireParticipant(id, user);
        if (body == null || body.isBlank() || body.length() > 2000) {
            return backWithError(request, response, "Message body is required.");
        }
        Message msg = new Message();
        msg.setId(UlidService.newUlid());
        msg.setConversationId(conversation.getId());
        msg.setUserId(user.getId());
        msg.setBody(body.trim());
        messageRepository.save(msg);
        conversation.setUpdatedAt(Instant.now());
        conversationRepository.save(conversation);
        return inertia.back(request, response, "/inbox/" + id);
    }

    @GetMapping(value = {"/chat/{id}/messages/since", "/inbox/{id}/messages/since"}, produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional(readOnly = true)
    public ResponseEntity<?> messagesSince(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String id,
            @RequestParam(value = "after", required = false) String after) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthenticated."));
        }
        User user = requireUser();
        Conversation conversation = requireParticipant(id, user);
        Instant afterInstant;
        if (after == null || after.isBlank()) {
            afterInstant = Instant.EPOCH;
        } else {
            try {
                afterInstant = Instant.parse(after);
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of("message", "Invalid after timestamp."));
            }
        }
        Listing listing = listingRepository.findById(conversation.getListingId()).orElseThrow();
        String otherUserId = user.getId().equals(conversation.getBuyerId())
                ? listing.getUserId()
                : conversation.getBuyerId();
        Instant otherReadAt = conversationReadRepository
                .findByConversationIdAndUserId(conversation.getId(), otherUserId)
                .map(ConversationRead::getLastReadAt)
                .orElse(null);

        List<Map<String, Object>> list = messageRepository
                .findNewerThan(conversation.getId(), afterInstant)
                .stream()
                .map(m -> messageMap(m, user.getId(), otherReadAt))
                .toList();
        return ResponseEntity.ok(Map.of("messages", list));
    }

    @PostMapping({"/chat/{id}/typing", "/inbox/{id}/typing"})
    public ResponseEntity<?> typing(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String id) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        User user = requireUser();
        requireParticipant(id, user);
        chatTypingService.setTyping(id, user.getId(), user.getName());
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = {"/chat/{id}/typing", "/inbox/{id}/typing"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> typingStatus(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String id) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthenticated."));
        }
        User user = requireUser();
        requireParticipant(id, user);
        ChatTypingService.TypingSnapshot snap = chatTypingService.otherTyping(id, user.getId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("typing", snap.typing());
        body.put("user_name", snap.userName());
        return ResponseEntity.ok(body);
    }

    private Conversation requireParticipant(String id, User user) {
        Conversation conversation = conversationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Listing listing = listingRepository.findById(conversation.getListingId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!user.getId().equals(conversation.getBuyerId()) && !user.getId().equals(listing.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return conversation;
    }

    private List<Map<String, Object>> conversationMaps(String userId, int limit) {
        List<Conversation> conversations = conversationRepository.findForUser(userId);
        if (conversations.size() > limit) {
            conversations = conversations.subList(0, limit);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Conversation c : conversations) {
            Listing listing = listingRepository.findDetailedById(c.getListingId()).orElse(null);
            if (listing == null) {
                continue;
            }
            Map<String, Object> m = conversationMap(c, listing, userId);
            long unread = messageRepository.countByConversationIdAndUserIdNotAndReadAtIsNull(c.getId(), userId);
            m.put("unread_count", unread);
            List<Message> latest = messageRepository.findByConversationIdOrderByCreatedAtDesc(c.getId());
            if (!latest.isEmpty()) {
                m.put("messages", List.of(messageMap(latest.get(0), userId, null)));
            } else {
                m.put("messages", List.of());
            }
            m.put("messages_count", latest.size());
            result.add(m);
        }
        return result;
    }

    private Map<String, Object> conversationMap(Conversation c, Listing listing, String currentUserId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("listing_id", c.getListingId());
        m.put("buyer_id", c.getBuyerId());
        m.put("created_at", ApiJson.formatInstant(c.getCreatedAt()));
        m.put("updated_at", ApiJson.formatInstant(c.getUpdatedAt()));
        m.put("listing", ApiJson.listingSummaryJson(listing));
        userRepository.findById(c.getBuyerId()).ifPresent(b -> m.put("buyer", ApiJson.userSummaryJson(b)));
        return m;
    }

    private Map<String, Object> messageMap(Message msg, String currentUserId, Instant otherReadAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", msg.getId());
        m.put("body", msg.getBody());
        m.put("created_at", ApiJson.formatInstant(msg.getCreatedAt()));
        m.put("user_id", msg.getUserId());
        m.put("read_at", ApiJson.formatInstant(msg.getReadAt()));
        userRepository.findById(msg.getUserId()).ifPresent(u -> {
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("id", u.getId());
            user.put("name", u.getName());
            m.put("user", user);
        });
        if (currentUserId.equals(msg.getUserId())) {
            String status = "sent";
            if (msg.getReadAt() != null) {
                status = "seen";
            } else if (otherReadAt != null && msg.getCreatedAt() != null && !otherReadAt.isBefore(msg.getCreatedAt())) {
                status = "delivered";
            }
            m.put("status", status);
        } else {
            m.put("status", null);
        }
        return m;
    }
}
