package dev.tintwym.home_mart_backend.controller.api;

import dev.tintwym.home_mart_backend.entity.Listing;
import dev.tintwym.home_mart_backend.entity.Review;
import dev.tintwym.home_mart_backend.repository.ListingRepository;
import dev.tintwym.home_mart_backend.repository.ReviewRepository;
import dev.tintwym.home_mart_backend.mapper.ApiJson;
import dev.tintwym.home_mart_backend.utility.UlidService;
import dev.tintwym.home_mart_backend.utility.ApiResponses;
import dev.tintwym.home_mart_backend.utility.AuthSupport;
import dev.tintwym.home_mart_backend.dto.ApiRequests.ReviewRequest;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ReviewsController {

    private final ReviewRepository reviewRepository;
    private final ListingRepository listingRepository;

    public ReviewsController(ReviewRepository reviewRepository, ListingRepository listingRepository) {
        this.reviewRepository = reviewRepository;
        this.listingRepository = listingRepository;
    }

    @PostMapping("/listings/{listingId}/reviews")
    @Transactional
    public ResponseEntity<?> store(@PathVariable String listingId, @Valid @RequestBody ReviewRequest request) {
        Listing listing = listingRepository.findById(listingId).orElse(null);
        if (listing == null) {
            return ApiResponses.notFound("Listing not found.");
        }
        String userId = AuthSupport.currentUserId();
        Optional<Review> existing =
                reviewRepository.findByListingIdAndUserIdAndParentIdIsNull(listingId, userId);
        boolean isNew = existing.isEmpty();
        Review review = existing.orElseGet(() -> {
            Review created = new Review();
            created.setId(UlidService.newUlid());
            created.setListingId(listingId);
            created.setUserId(userId);
            return created;
        });
        review.setRating(request.rating().shortValue());
        review.setComment(request.comment());
        reviewRepository.save(review);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", review.getId());
        data.put("rating", review.getRating());
        data.put("comment", review.getComment());
        data.put("created_at", ApiJson.formatInstant(review.getCreatedAt()));
        return ResponseEntity.status(isNew ? HttpStatus.CREATED : HttpStatus.OK).body(Map.of("data", data));
    }
}
