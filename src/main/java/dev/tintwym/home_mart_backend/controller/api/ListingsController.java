package dev.tintwym.home_mart_backend.controller.api;

import dev.tintwym.home_mart_backend.entity.Category;
import dev.tintwym.home_mart_backend.entity.Listing;
import dev.tintwym.home_mart_backend.entity.Review;
import dev.tintwym.home_mart_backend.entity.Subcategory;
import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.repository.CategoryRepository;
import dev.tintwym.home_mart_backend.repository.ListingRepository;
import dev.tintwym.home_mart_backend.repository.ReviewRepository;
import dev.tintwym.home_mart_backend.repository.SubcategoryRepository;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import dev.tintwym.home_mart_backend.mapper.ApiJson;
import dev.tintwym.home_mart_backend.utility.UlidService;
import dev.tintwym.home_mart_backend.utility.ApiResponses;
import dev.tintwym.home_mart_backend.utility.AuthSupport;
import dev.tintwym.home_mart_backend.dto.ApiRequests.ListingRequest;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/listings")
public class ListingsController {

    private static final Set<String> CONDITIONS = Set.of("new", "like_new", "good", "fair");

    private final ListingRepository listingRepository;
    private final SubcategoryRepository subcategoryRepository;
    private final CategoryRepository categoryRepository;
    private final ReviewRepository reviewRepository;
    private final UserRepository userRepository;

    public ListingsController(
            ListingRepository listingRepository,
            SubcategoryRepository subcategoryRepository,
            CategoryRepository categoryRepository,
            ReviewRepository reviewRepository,
            UserRepository userRepository) {
        this.listingRepository = listingRepository;
        this.subcategoryRepository = subcategoryRepository;
        this.categoryRepository = categoryRepository;
        this.reviewRepository = reviewRepository;
        this.userRepository = userRepository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<?> index(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "per_page", defaultValue = "20") int perPage) {
        int size = Math.max(1, Math.min(50, perPage));
        int pageNum = Math.max(1, page);

        String subcategoryId = null;
        String categoryId = null;
        if (category != null && !category.isBlank()) {
            Subcategory sub = subcategoryRepository.findBySlug(category).orElse(null);
            if (sub != null) {
                subcategoryId = sub.getId();
            } else {
                Category parent = categoryRepository.findBySlug(category).orElse(null);
                if (parent != null) {
                    categoryId = parent.getId();
                }
            }
        }

        List<Listing> all;
        if (categoryId != null) {
            all = listingRepository.searchByCategory(q, categoryId, subcategoryId, null);
        } else {
            all = listingRepository.search(q, subcategoryId, null);
        }

        int total = all.size();
        int lastPage = Math.max(1, (int) Math.ceil(total / (double) size));
        int from = Math.min((pageNum - 1) * size, total);
        int to = Math.min(from + size, total);
        List<Listing> pageItems = all.subList(from, to);

        List<Map<String, Object>> data = new ArrayList<>();
        for (Listing listing : pageItems) {
            hydrate(listing);
            data.add(ApiJson.listingSummaryJson(listing));
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("current_page", pageNum);
        meta.put("last_page", lastPage);
        meta.put("per_page", size);
        meta.put("total", total);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("meta", meta);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{id}")
    @Transactional
    public ResponseEntity<?> show(@PathVariable String id) {
        Listing listing = listingRepository.findByIdWithRelations(id).orElse(null);
        if (listing == null) {
            return ApiResponses.notFound("Listing not found.");
        }
        int views = listing.getViewsCount() == null ? 0 : listing.getViewsCount();
        listing.setViewsCount(views + 1);
        listingRepository.save(listing);

        List<Review> reviews = reviewRepository.findByListingIdAndParentIdIsNullOrderByCreatedAtDesc(id);
        List<Map<String, Object>> reviewJson = new ArrayList<>();
        double sum = 0;
        for (Review review : reviews) {
            sum += review.getRating() == null ? 0 : review.getRating();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", review.getId());
            row.put("rating", review.getRating());
            row.put("comment", review.getComment());
            row.put("created_at", ApiJson.formatInstant(review.getCreatedAt()));
            User reviewer = userRepository.findById(review.getUserId()).orElse(null);
            row.put("user", reviewer == null ? null : Map.of("id", reviewer.getId(), "name", reviewer.getName()));
            reviewJson.add(row);
        }

        List<Map<String, Object>> related = listingRepository
                .search(null, listing.getSubcategoryId(), null)
                .stream()
                .filter(l -> !l.getId().equals(listing.getId()))
                .limit(6)
                .map(l -> {
                    hydrate(l);
                    return ApiJson.listingSummaryJson(l);
                })
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", listing.getId());
        data.put("title", listing.getTitle());
        data.put("description", listing.getDescription());
        data.put("condition", listing.getCondition());
        data.put("price", listing.getPrice());
        data.put("meetup_location", listing.getMeetupLocation());
        data.put("image_url", ApiJson.imageUrl(listing.getImagePath()));
        data.put("image_path", listing.getImagePath());
        data.put("trending_until", ApiJson.formatInstant(listing.getTrendingUntil()));
        data.put("is_trending", listing.isTrending());
        data.put("views_count", listing.getViewsCount());
        data.put("category", listing.getSubcategory() == null ? null : ApiJson.subcategoryJson(listing.getSubcategory()));
        data.put("seller", listing.getUser() == null ? null : ApiJson.userSummaryJson(listing.getUser()));
        data.put("average_rating", reviews.isEmpty() ? 0 : Math.round((sum / reviews.size()) * 10.0) / 10.0);
        data.put("review_count", reviews.size());
        data.put("reviews", reviewJson);
        data.put("related_listings", related);
        return ResponseEntity.ok(Map.of("data", data));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> store(@Valid @RequestBody ListingRequest request) {
        String userId = AuthSupport.currentUserId();
        if (!CONDITIONS.contains(request.condition())) {
            return ApiResponses.unprocessable("Invalid condition.");
        }
        if (!subcategoryRepository.existsById(request.subcategoryId())) {
            return ApiResponses.unprocessable(
                    "Subcategory not found.",
                    Map.of("subcategory_id", List.of("Subcategory not found.")));
        }

        Listing listing = new Listing();
        listing.setId(UlidService.newUlid());
        listing.setUserId(userId);
        applyRequest(listing, request);
        listingRepository.save(listing);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", Map.of("id", listing.getId()));
        body.put("message", "Listing created.");
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable String id, @Valid @RequestBody ListingRequest request) {
        String userId = AuthSupport.currentUserId();
        Listing listing = listingRepository.findById(id).orElse(null);
        if (listing == null) {
            return ApiResponses.notFound("Listing not found.");
        }
        if (!userId.equals(listing.getUserId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Forbidden."));
        }
        if (!CONDITIONS.contains(request.condition())) {
            return ApiResponses.unprocessable("Invalid condition.");
        }
        if (!subcategoryRepository.existsById(request.subcategoryId())) {
            return ApiResponses.unprocessable("Subcategory not found.");
        }
        applyRequest(listing, request);
        listingRepository.save(listing);
        return ResponseEntity.ok(Map.of("message", "Listing updated."));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> destroy(@PathVariable String id) {
        String userId = AuthSupport.currentUserId();
        Listing listing = listingRepository.findById(id).orElse(null);
        if (listing == null) {
            return ApiResponses.notFound("Listing not found.");
        }
        if (!userId.equals(listing.getUserId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Forbidden."));
        }
        listingRepository.delete(listing);
        return ResponseEntity.ok(Map.of("message", "Listing deleted."));
    }

    private void applyRequest(Listing listing, ListingRequest request) {
        listing.setSubcategoryId(request.subcategoryId());
        listing.setTitle(request.title().trim());
        listing.setDescription(request.description().trim());
        listing.setCondition(request.condition());
        listing.setPrice(request.price());
        if (request.imageUrl() != null && !request.imageUrl().isBlank()) {
            listing.setImagePath(request.imageUrl().trim());
        }
        listing.setMeetupLocation(
                request.meetupLocation() == null || request.meetupLocation().isBlank()
                        ? null
                        : request.meetupLocation().trim());
    }

    private void hydrate(Listing listing) {
        if (listing.getUser() == null && listing.getUserId() != null) {
            userRepository.findById(listing.getUserId()).ifPresent(listing::setUser);
        }
        if (listing.getSubcategory() == null && listing.getSubcategoryId() != null) {
            subcategoryRepository.findById(listing.getSubcategoryId()).ifPresent(listing::setSubcategory);
        }
    }
}
