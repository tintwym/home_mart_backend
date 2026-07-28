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
import dev.tintwym.home_mart_backend.service.InertiaService;
import dev.tintwym.home_mart_backend.utility.UlidService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    private static final ConcurrentHashMap<String, TypingEntry> TYPING = new ConcurrentHashMap<>();

    private final FavoriteRepository favoriteRepository;
    private final ListingRepository listingRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ConversationReadRepository conversationReadRepository;

    public ChatPagesController(
            InertiaService inertia,
            UserRepository userRepository,
            FavoriteRepository favoriteRepository,
            ListingRepository listingRepository,
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            ConversationReadRepository conversationReadRepository) {
        super(inertia, userRepository);
        this.favoriteRepository = favoriteRepository;
        this.listingRepository = listingRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.conversationReadRepository = conversationReadRepository;
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
                : listingRepository.findDetailedByIdIn(ids).stream().map(ApiJson::listingSummaryJson).toList();
        return render(request, response, "favorites/index", Map.of("listings", listings));
    }

    @GetMapping("/chat")
    @Transactional(readOnly = true)
    public ResponseEntity<?> chatIndex(HttpServletRequest request, HttpServletResponse response) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        return render(request, response, "chat/index",
                Map.of("conversations", conversationMaps(requireUser().getId(), 30)));
    }

    @GetMapping("/chat/{id}")
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
        return render(request, response, "chat/show", props);
    }

    @PostMapping("/chat/{id}/messages")
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
        return inertia.back(request, response, "/chat/" + id);
    }

    @GetMapping(value = "/chat/{id}/messages/since", produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional(readOnly = true)
    public ResponseEntity<?> messagesSince(
            @PathVariable String id,
            @RequestParam(value = "after", required = false) String after) {
        User user = requireUser();
        Conversation conversation = requireParticipant(id, user);
        if (after == null || after.isBlank()) {
            return ResponseEntity.ok(Map.of("messages", List.of()));
        }
        Instant afterInstant = Instant.parse(after);
        Listing listing = listingRepository.findById(conversation.getListingId()).orElseThrow();
        String otherUserId = user.getId().equals(conversation.getBuyerId())
                ? listing.getUserId()
                : conversation.getBuyerId();
        Instant otherReadAt = conversationReadRepository
                .findByConversationIdAndUserId(conversation.getId(), otherUserId)
                .map(ConversationRead::getLastReadAt)
                .orElse(null);

        List<Map<String, Object>> list = messageRepository
                .findByConversationIdOrderByCreatedAtAsc(conversation.getId())
                .stream()
                .filter(m -> m.getCreatedAt() != null && m.getCreatedAt().isAfter(afterInstant))
                .map(m -> messageMap(m, user.getId(), otherReadAt))
                .toList();
        return ResponseEntity.ok(Map.of("messages", list));
    }

    @PostMapping("/chat/{id}/typing")
    public ResponseEntity<Void> typing(@PathVariable String id) {
        User user = requireUser();
        requireParticipant(id, user);
        TYPING.put(id + "." + user.getId(), new TypingEntry(user.getName(), System.currentTimeMillis() + 5000));
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/chat/{id}/typing", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> typingStatus(@PathVariable String id) {
        User user = requireUser();
        Conversation conversation = requireParticipant(id, user);
        Listing listing = listingRepository.findById(conversation.getListingId()).orElseThrow();
        String otherUserId = user.getId().equals(conversation.getBuyerId())
                ? listing.getUserId()
                : conversation.getBuyerId();
        TypingEntry entry = TYPING.get(id + "." + otherUserId);
        boolean typing = entry != null && entry.expiresAt() > System.currentTimeMillis();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("typing", typing);
        body.put("user_name", typing ? entry.name() : null);
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

    private record TypingEntry(String name, long expiresAt) {
    }
}
