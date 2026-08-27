package dev.tintwym.home_mart_backend.controller.api;

import dev.tintwym.home_mart_backend.entity.Message;
import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.mapper.ApiJson;
import dev.tintwym.home_mart_backend.repository.MessageRepository;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import dev.tintwym.home_mart_backend.utility.ApiResponses;
import dev.tintwym.home_mart_backend.utility.AuthSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class NotificationsController {

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;

    public NotificationsController(MessageRepository messageRepository, UserRepository userRepository) {
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<?> index() {
        String userId = AuthSupport.currentUserIdOrNull();
        if (userId == null || userId.isBlank()) {
            return ApiResponses.unauthorized("Unauthenticated.");
        }
        List<Message> unread = messageRepository.findUnreadForUser(userId);
        List<Map<String, Object>> out = new ArrayList<>();
        int limit = Math.min(30, unread.size());
        for (int i = 0; i < limit; i++) {
            Message m = unread.get(i);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("conversation_id", m.getConversationId());
            data.put("from_user_name", m.getUser() == null ? "Someone" : m.getUser().getName());
            data.put("body", m.getBody());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.getId());
            row.put("type", "new_message");
            row.put("data", data);
            row.put("created_at", ApiJson.formatInstant(m.getCreatedAt()));
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    @PostMapping("/{id}/read")
    @Transactional
    public ResponseEntity<Void> markRead(@PathVariable String id) {
        User user = userRepository.findById(AuthSupport.currentUserId()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        Message message = messageRepository.findById(id).orElse(null);
        if (message != null
                && !user.getId().equals(message.getUserId())
                && message.getReadAt() == null) {
            message.setReadAt(Instant.now());
            messageRepository.save(message);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    @Transactional
    public ResponseEntity<Void> markAllRead() {
        User user = userRepository.findById(AuthSupport.currentUserId()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        for (Message message : messageRepository.findUnreadForUser(user.getId())) {
            message.setReadAt(Instant.now());
            messageRepository.save(message);
        }
        return ResponseEntity.noContent().build();
    }
}
