package dev.tintwym.home_mart_backend.controller.pages;

import dev.tintwym.home_mart_backend.config.AppProperties;
import dev.tintwym.home_mart_backend.entity.CartItem;
import dev.tintwym.home_mart_backend.entity.Conversation;
import dev.tintwym.home_mart_backend.entity.Favorite;
import dev.tintwym.home_mart_backend.entity.Listing;
import dev.tintwym.home_mart_backend.entity.Review;
import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.repository.CartItemRepository;
import dev.tintwym.home_mart_backend.repository.ConversationRepository;
import dev.tintwym.home_mart_backend.repository.FavoriteRepository;
import dev.tintwym.home_mart_backend.repository.ListingRepository;
import dev.tintwym.home_mart_backend.repository.OrderItemRepository;
import dev.tintwym.home_mart_backend.repository.ReviewRepository;
import dev.tintwym.home_mart_backend.repository.SubcategoryRepository;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import dev.tintwym.home_mart_backend.mapper.ApiJson;
import dev.tintwym.home_mart_backend.service.InertiaService;
import dev.tintwym.home_mart_backend.service.ShopConfig;
import dev.tintwym.home_mart_backend.service.StripeService;
import dev.tintwym.home_mart_backend.utility.UlidService;
import dev.tintwym.home_mart_backend.service.UploadService;
import com.stripe.model.checkout.Session;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ListingPagesController extends PageControllerSupport {

    private final ListingRepository listingRepository;
    private final SubcategoryRepository subcategoryRepository;
    private final ReviewRepository reviewRepository;
    private final FavoriteRepository favoriteRepository;
    private final CartItemRepository cartItemRepository;
    private final ConversationRepository conversationRepository;
    private final OrderItemRepository orderItemRepository;
    private final UploadService uploadService;
    private final StripeService stripeService;
    private final AppProperties appProperties;

    public ListingPagesController(
            InertiaService inertia,
            UserRepository userRepository,
            ListingRepository listingRepository,
            SubcategoryRepository subcategoryRepository,
            ReviewRepository reviewRepository,
            FavoriteRepository favoriteRepository,
            CartItemRepository cartItemRepository,
            ConversationRepository conversationRepository,
            OrderItemRepository orderItemRepository,
            UploadService uploadService,
            StripeService stripeService,
            AppProperties appProperties) {
        super(inertia, userRepository);
        this.listingRepository = listingRepository;
        this.subcategoryRepository = subcategoryRepository;
        this.reviewRepository = reviewRepository;
        this.favoriteRepository = favoriteRepository;
        this.cartItemRepository = cartItemRepository;
        this.conversationRepository = conversationRepository;
        this.orderItemRepository = orderItemRepository;
        this.uploadService = uploadService;
        this.stripeService = stripeService;
        this.appProperties = appProperties;
    }

    @GetMapping("/listings/create")
    @Transactional(readOnly = true)
    public ResponseEntity<?> createForm(HttpServletRequest request, HttpServletResponse response) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        User user = requireUser();
        long count = listingRepository.countByUserId(user.getId());
        int maxSlots = ShopConfig.maxListingSlots(user.getSellerType(), user.getExtraListingSlots());
        ShopConfig.Currency currency = currencyFor(request);

        List<Map<String, Object>> subs = subcategoryRepository.findAll().stream().map(s -> {
            Map<String, Object> m = ApiJson.subcategoryJson(s);
            if (s.getCategory() != null) {
                Map<String, Object> cat = new LinkedHashMap<>();
                cat.put("id", s.getCategory().getId());
                cat.put("name", s.getCategory().getName());
                cat.put("slug", s.getCategory().getSlug());
                m.put("category", cat);
            }
            return m;
        }).toList();

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("subcategories", subs);
        props.put("listingCount", count);
        props.put("maxListingSlots", maxSlots);
        props.put("canCreate", count < maxSlots);
        props.put("slotPrice", ShopConfig.SLOT_PRICE);
        props.put("slotPriceLabel", currency.symbol() + ShopConfig.SLOT_PRICE + " per slot");
        return render(request, response, "listings/create", props);
    }

    @PostMapping("/listings")
    @Transactional
    public ResponseEntity<?> store(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam("subcategory_id") String subcategoryId,
            @RequestParam String title,
            @RequestParam String description,
            @RequestParam String condition,
            @RequestParam BigDecimal price,
            @RequestParam(value = "meetup_location", required = false) String meetupLocation,
            @RequestParam(value = "image", required = false) MultipartFile image) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        User user = requireUser();
        long count = listingRepository.countByUserId(user.getId());
        int maxSlots = ShopConfig.maxListingSlots(user.getSellerType(), user.getExtraListingSlots());
        if (count >= maxSlots) {
            return redirectWithError(request, response, "/listings/create", "Listing slot limit reached.");
        }
        if (image == null || image.isEmpty()) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("errors", errors("image", "An image is required."));
            return render(request, response, "listings/create", props);
        }
        String imagePath;
        try {
            imagePath = uploadService.uploadListingImage(image);
        } catch (Exception e) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("errors", errors("image", e.getMessage()));
            return render(request, response, "listings/create", props);
        }

        Listing listing = new Listing();
        listing.setId(UlidService.newUlid());
        listing.setUserId(user.getId());
        listing.setSubcategoryId(subcategoryId);
        listing.setTitle(title.trim());
        listing.setDescription(description);
        listing.setCondition(condition);
        listing.setPrice(price);
        listing.setImagePath(imagePath);
        listing.setMeetupLocation(blankToNull(meetupLocation));
        listingRepository.save(listing);
        return redirectWithStatus(request, response, "/", "Listing created.");
    }

    @GetMapping("/listings/{id}")
    @Transactional
    public ResponseEntity<?> show(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String id) {
        Listing listing = listingRepository.findDetailedById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        listing.setViewsCount((listing.getViewsCount() == null ? 0 : listing.getViewsCount()) + 1);
        listingRepository.save(listing);

        List<Review> reviews = reviewRepository.findByListingIdOrderByCreatedAtDesc(id);
        List<Map<String, Object>> reviewJson = new ArrayList<>();
        for (Review r : reviews) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("listing_id", r.getListingId());
            m.put("user_id", r.getUserId());
            m.put("rating", r.getRating());
            m.put("comment", r.getComment());
            m.put("parent_id", r.getParentId());
            m.put("created_at", ApiJson.formatInstant(r.getCreatedAt()));
            userRepository.findById(r.getUserId()).ifPresent(u -> m.put("user", ApiJson.userSummaryJson(u)));
            reviewJson.add(m);
        }
        double avg = reviews.stream()
                .filter(r -> r.getParentId() == null)
                .mapToInt(r -> r.getRating() == null ? 0 : r.getRating())
                .average()
                .orElse(0);
        long reviewCount = reviews.stream().filter(r -> r.getParentId() == null).count();

        List<Listing> related = listingRepository.findBySubcategoryIdOrderByCreatedAtDesc(listing.getSubcategoryId())
                .stream()
                .filter(l -> !l.getId().equals(id))
                .limit(6)
                .toList();
        List<String> relatedIds = related.stream().map(Listing::getId).toList();
        List<Map<String, Object>> relatedJson = listingRepository.findDetailedByIdIn(relatedIds).stream()
                .map(ApiJson::listingSummaryJson)
                .toList();

        ShopConfig.Currency currency = currencyFor(request);
        Map<String, Object> listingMap = ApiJson.listingSummaryJson(listing);
        listingMap.put("reviews", reviewJson);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("listing", listingMap);
        props.put("averageRating", Math.round(avg * 10.0) / 10.0);
        props.put("reviewCount", reviewCount);
        props.put("trendPriceLabel", currency.symbol() + ShopConfig.TREND_PRICE + " for " + ShopConfig.TREND_DURATION_DAYS + " days");
        props.put("trendDurationDays", ShopConfig.TREND_DURATION_DAYS);
        props.put("relatedListings", relatedJson);
        return render(request, response, "listings/show", props);
    }

    @GetMapping("/listings/{id}/edit")
    @Transactional(readOnly = true)
    public ResponseEntity<?> editForm(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String id) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        Listing listing = listingRepository.findDetailedById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!listing.getUserId().equals(requireUser().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        List<Map<String, Object>> subs = subcategoryRepository.findAll().stream()
                .map(ApiJson::subcategoryJson)
                .toList();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("listing", ApiJson.listingSummaryJson(listing));
        props.put("subcategories", subs);
        return render(request, response, "listings/edit", props);
    }

    @PostMapping("/listings/{id}")
    @PutMapping("/listings/{id}")
    @Transactional
    public ResponseEntity<?> update(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String id,
            @RequestParam("subcategory_id") String subcategoryId,
            @RequestParam String title,
            @RequestParam String description,
            @RequestParam String condition,
            @RequestParam BigDecimal price,
            @RequestParam(value = "meetup_location", required = false) String meetupLocation,
            @RequestParam(value = "image", required = false) MultipartFile image,
            @RequestParam(value = "_method", required = false) String method) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        Listing listing = listingRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!listing.getUserId().equals(requireUser().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (image != null && !image.isEmpty()) {
            try {
                listing.setImagePath(uploadService.uploadListingImage(image));
            } catch (Exception e) {
                return backWithError(request, response, e.getMessage());
            }
        }
        listing.setSubcategoryId(subcategoryId);
        listing.setTitle(title.trim());
        listing.setDescription(description);
        listing.setCondition(condition);
        listing.setPrice(price);
        listing.setMeetupLocation(blankToNull(meetupLocation));
        listingRepository.save(listing);
        return redirectWithStatus(request, response, "/", "Listing updated.");
    }

    @DeleteMapping("/listings/{id}")
    @PostMapping("/listings/{id}/delete")
    @Transactional
    public ResponseEntity<?> destroy(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String id,
            @RequestParam(value = "_method", required = false) String method) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        Listing listing = listingRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!listing.getUserId().equals(requireUser().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        listingRepository.delete(listing);
        return redirectWithStatus(request, response, "/", "Listing deleted.");
    }

    @PostMapping("/listings/{id}/reviews")
    @Transactional
    public ResponseEntity<?> storeReview(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String id,
            @RequestParam Integer rating,
            @RequestParam(value = "comment", required = false) String comment) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        listingRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        User user = requireUser();
        Review review = reviewRepository.findByListingIdAndUserIdAndParentIdIsNull(id, user.getId())
                .orElseGet(() -> {
                    Review r = new Review();
                    r.setId(UlidService.newUlid());
                    r.setListingId(id);
                    r.setUserId(user.getId());
                    return r;
                });
        review.setRating(rating.shortValue());
        review.setComment(comment);
        reviewRepository.save(review);
        return backWithStatus(request, response, "Review saved.");
    }

    @PostMapping("/listings/{id}/favorite")
    @Transactional
    public ResponseEntity<?> toggleFavorite(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String id) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        listingRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        User user = requireUser();
        var existing = favoriteRepository.findByUserIdAndListingId(user.getId(), id);
        if (existing.isPresent()) {
            favoriteRepository.delete(existing.get());
            return backWithStatus(request, response, "Removed from favorites.");
        }
        Favorite fav = new Favorite();
        fav.setUserId(user.getId());
        fav.setListingId(id);
        favoriteRepository.save(fav);
        return backWithStatus(request, response, "Added to favorites.");
    }

    @PostMapping("/listings/{id}/cart")
    @Transactional
    public ResponseEntity<?> addToCart(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String id,
            @RequestParam(value = "intent", required = false) String intent) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        Listing listing = listingRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        User user = requireUser();
        if (listing.getUserId().equals(user.getId())) {
            return backWithError(request, response, "You cannot add your own listing to cart.");
        }
        if (orderItemRepository.existsByListingIdAndOrder_StatusIn(id, List.of("paid", "completed"))) {
            return backWithError(request, response, "This listing has already been sold.");
        }
        if (!cartItemRepository.existsByUserIdAndListingId(user.getId(), id)) {
            CartItem item = new CartItem();
            item.setId(UlidService.newUlid());
            item.setUserId(user.getId());
            item.setListingId(id);
            cartItemRepository.save(item);
        }
        if ("buy".equals(intent)) {
            return redirectWithStatus(request, response, "/cart", "Added to cart.");
        }
        return backWithStatus(request, response, "Added to cart.");
    }

    @DeleteMapping("/listings/{id}/cart")
    @Transactional
    public ResponseEntity<?> removeFromCart(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String id) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        cartItemRepository.deleteByUserIdAndListingId(requireUser().getId(), id);
        return backWithStatus(request, response, "Removed from cart.");
    }

    @PostMapping("/listings/{id}/chat")
    @Transactional
    public ResponseEntity<?> startChat(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String id) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        Listing listing = listingRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        User user = requireUser();
        if (listing.getUserId().equals(user.getId())) {
            return backWithError(request, response, "You cannot chat with yourself.");
        }
        Conversation conversation = conversationRepository.findByListingIdAndBuyerId(id, user.getId())
                .orElseGet(() -> {
                    Conversation c = new Conversation();
                    c.setId(UlidService.newUlid());
                    c.setListingId(id);
                    c.setBuyerId(user.getId());
                    return conversationRepository.save(c);
                });
        return redirect(request, "/chat/" + conversation.getId());
    }

    @PostMapping("/listings/{id}/promote")
    @Transactional
    public ResponseEntity<?> promote(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String id) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        Listing listing = listingRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        User user = requireUser();
        if (!listing.getUserId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (!stripeService.isConfigured()) {
            return redirectWithError(request, response, "/listings/" + id, "Payment is not configured. Please try again later.");
        }
        try {
            String success = appPropertiesUrl() + "/checkout/promote/success?session_id={CHECKOUT_SESSION_ID}";
            String cancel = appPropertiesUrl() + "/listings/" + id;
            Session session = stripeService.createPromoteCheckoutSession(
                    user, id, "trend", ShopConfig.TREND_PRICE, "USD", success, cancel);
            userRepository.save(user); // persist stripe customer id if created
            return inertia.externalRedirect(request, session.getUrl());
        } catch (Exception e) {
            return redirectWithError(request, response, "/listings/" + id, "Could not start checkout. Please try again.");
        }
    }

    @GetMapping("/checkout/promote/success")
    @Transactional
    public ResponseEntity<?> promoteSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam(value = "session_id", required = false) String sessionId) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        if (sessionId == null || sessionId.isBlank()) {
            return redirectWithError(request, response, "/", "Invalid checkout session.");
        }
        try {
            Session session = stripeService.retrieveSession(sessionId);
            if (!"paid".equals(session.getPaymentStatus())) {
                return redirectWithError(request, response, "/", "Payment was not completed.");
            }
            String listingId = session.getMetadata() == null ? null : session.getMetadata().get("listing_id");
            String type = session.getMetadata() == null ? null : session.getMetadata().get("type");
            if (listingId == null || !"promote".equals(type)) {
                return redirectWithError(request, response, "/", "Invalid session.");
            }
            Listing listing = listingRepository.findById(listingId).orElse(null);
            if (listing == null || !listing.getUserId().equals(requireUser().getId())) {
                return redirectWithError(request, response, "/", "Listing not found or access denied.");
            }
            listing.setTrendingUntil(Instant.now().plus(ShopConfig.TREND_DURATION_DAYS, ChronoUnit.DAYS));
            listingRepository.save(listing);
            return redirectWithStatus(request, response, "/listings/" + listingId,
                    "Listing promoted for " + ShopConfig.TREND_DURATION_DAYS + " days. It will appear higher in search.");
        } catch (Exception e) {
            return redirectWithError(request, response, "/", "Invalid checkout session.");
        }
    }

    private String appPropertiesUrl() {
        return appProperties.getUrl();
    }
}
