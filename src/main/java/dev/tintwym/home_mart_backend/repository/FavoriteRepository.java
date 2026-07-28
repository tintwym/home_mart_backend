package dev.tintwym.home_mart_backend.repository;

import dev.tintwym.home_mart_backend.entity.Favorite;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

    List<Favorite> findByUserId(String userId);

    Optional<Favorite> findByUserIdAndListingId(String userId, String listingId);

    boolean existsByUserIdAndListingId(String userId, String listingId);

    void deleteByUserIdAndListingId(String userId, String listingId);

    void deleteByUserId(String userId);
}
