package dev.tintwym.home_mart_backend.controller.api;

import dev.tintwym.home_mart_backend.entity.CartItem;
import dev.tintwym.home_mart_backend.entity.Listing;
import dev.tintwym.home_mart_backend.repository.CartItemRepository;
import dev.tintwym.home_mart_backend.repository.ListingRepository;
import dev.tintwym.home_mart_backend.repository.OrderItemRepository;
import dev.tintwym.home_mart_backend.mapper.ApiJson;
import dev.tintwym.home_mart_backend.utility.UlidService;
import dev.tintwym.home_mart_backend.utility.ApiResponses;
import dev.tintwym.home_mart_backend.utility.AuthSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CartController {

    private final CartItemRepository cartItemRepository;
    private final ListingRepository listingRepository;
    private final OrderItemRepository orderItemRepository;

    public CartController(
            CartItemRepository cartItemRepository,
            ListingRepository listingRepository,
            OrderItemRepository orderItemRepository) {
        this.cartItemRepository = cartItemRepository;
        this.listingRepository = listingRepository;
        this.orderItemRepository = orderItemRepository;
    }

    @GetMapping("/cart")
    @Transactional(readOnly = true)
    public ResponseEntity<?> index() {
        String userId = AuthSupport.currentUserId();
        List<Map<String, Object>> items = new ArrayList<>();
        for (CartItem item : cartItemRepository.findByUserId(userId)) {
            Listing listing = listingRepository
                    .findByIdWithRelations(item.getListingId())
                    .orElse(item.getListing());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.getId());
            row.put("listing_id", item.getListingId());
            row.put("listing", ApiJson.listingSummaryJson(listing));
            row.put("created_at", ApiJson.formatInstant(item.getCreatedAt()));
            items.add(row);
        }
        return ResponseEntity.ok(Map.of("items", items));
    }

    @PostMapping("/listings/{id}/cart")
    @Transactional
    public ResponseEntity<?> add(@PathVariable("id") String listingId) {
        String userId = AuthSupport.currentUserId();
        Listing listing = listingRepository.findById(listingId).orElse(null);
        if (listing == null) {
            return ApiResponses.notFound("Listing not found.");
        }
        if (userId.equals(listing.getUserId())) {
            return ApiResponses.unprocessable("You cannot add your own listing to the cart.");
        }
        if (orderItemRepository.existsByListingIdAndOrder_StatusIn(listingId, List.of("paid", "completed"))) {
            return ApiResponses.unprocessable("This listing has already been sold.");
        }
        if (!cartItemRepository.existsByUserIdAndListingId(userId, listingId)) {
            CartItem item = new CartItem();
            item.setId(UlidService.newUlid());
            item.setUserId(userId);
            item.setListingId(listingId);
            cartItemRepository.save(item);
        }
        return ResponseEntity.ok(Map.of("message", "Added to cart."));
    }

    @DeleteMapping("/listings/{id}/cart")
    @Transactional
    public ResponseEntity<?> remove(@PathVariable("id") String listingId) {
        cartItemRepository.deleteByUserIdAndListingId(AuthSupport.currentUserId(), listingId);
        return ResponseEntity.ok(Map.of("message", "Removed from cart."));
    }
}
