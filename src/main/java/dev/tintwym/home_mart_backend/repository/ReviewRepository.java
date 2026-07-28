package dev.tintwym.home_mart_backend.repository;

import dev.tintwym.home_mart_backend.entity.Review;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRepository extends JpaRepository<Review, String> {

    List<Review> findByListingId(String listingId);

    List<Review> findByListingIdOrderByCreatedAtDesc(String listingId);

    List<Review> findByListingIdAndParentIdIsNullOrderByCreatedAtDesc(String listingId);

    Optional<Review> findByListingIdAndUserIdAndParentIdIsNull(String listingId, String userId);

    List<Review> findByParentIdOrderByCreatedAtAsc(String parentId);
}
