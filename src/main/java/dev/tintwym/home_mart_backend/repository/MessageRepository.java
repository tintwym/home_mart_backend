package dev.tintwym.home_mart_backend.repository;

import dev.tintwym.home_mart_backend.entity.Message;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageRepository extends JpaRepository<Message, String> {

    List<Message> findByConversationIdOrderByCreatedAtAsc(String conversationId);

    List<Message> findByConversationIdOrderByCreatedAtDesc(String conversationId);

    long countByConversationIdAndUserIdNotAndReadAtIsNull(String conversationId, String userId);

    @Query("""
            SELECT COUNT(m) FROM Message m
            JOIN m.conversation c
            JOIN c.listing l
            WHERE m.userId <> :userId
              AND m.readAt IS NULL
              AND (c.buyerId = :userId OR l.userId = :userId)
            """)
    long countUnreadForUser(@Param("userId") String userId);

    @Query("""
            SELECT m FROM Message m
            JOIN FETCH m.conversation c
            JOIN FETCH c.listing l
            JOIN FETCH m.user
            WHERE m.userId <> :userId
              AND m.readAt IS NULL
              AND (c.buyerId = :userId OR l.userId = :userId)
            ORDER BY m.createdAt DESC
            """)
    List<Message> findUnreadForUser(@Param("userId") String userId);
}
