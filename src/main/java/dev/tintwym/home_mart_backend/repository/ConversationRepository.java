package dev.tintwym.home_mart_backend.repository;

import dev.tintwym.home_mart_backend.entity.Conversation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationRepository extends JpaRepository<Conversation, String> {

    List<Conversation> findByBuyerId(String buyerId);

    Optional<Conversation> findByListingIdAndBuyerId(String listingId, String buyerId);

    List<Conversation> findByListingId(String listingId);

    @Query("""
            SELECT c FROM Conversation c
            JOIN c.listing l
            WHERE c.buyerId = :userId OR l.userId = :userId
            ORDER BY c.updatedAt DESC
            """)
    List<Conversation> findForUser(@Param("userId") String userId);
}
