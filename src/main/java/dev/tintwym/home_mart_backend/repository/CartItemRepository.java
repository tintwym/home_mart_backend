package dev.tintwym.home_mart_backend.repository;

import dev.tintwym.home_mart_backend.entity.CartItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartItemRepository extends JpaRepository<CartItem, String> {

    List<CartItem> findByUserId(String userId);

    Optional<CartItem> findByUserIdAndListingId(String userId, String listingId);

    boolean existsByUserIdAndListingId(String userId, String listingId);

    void deleteByUserIdAndListingId(String userId, String listingId);

    void deleteByUserId(String userId);

    long countByUserId(String userId);
}
