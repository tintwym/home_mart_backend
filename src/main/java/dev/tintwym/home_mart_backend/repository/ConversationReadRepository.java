package dev.tintwym.home_mart_backend.repository;

import dev.tintwym.home_mart_backend.entity.ConversationRead;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationReadRepository extends JpaRepository<ConversationRead, String> {

    Optional<ConversationRead> findByConversationIdAndUserId(String conversationId, String userId);

    List<ConversationRead> findByUserId(String userId);

    List<ConversationRead> findByConversationId(String conversationId);
}
