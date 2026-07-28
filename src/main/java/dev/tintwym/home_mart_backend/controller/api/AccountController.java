package dev.tintwym.home_mart_backend.controller.api;

import dev.tintwym.home_mart_backend.entity.Listing;
import dev.tintwym.home_mart_backend.entity.LocalPaymentMethod;
import dev.tintwym.home_mart_backend.entity.OrderEntity;
import dev.tintwym.home_mart_backend.entity.OrderItem;
import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.repository.CartItemRepository;
import dev.tintwym.home_mart_backend.repository.FavoriteRepository;
import dev.tintwym.home_mart_backend.repository.ListingRepository;
import dev.tintwym.home_mart_backend.repository.LocalPaymentMethodRepository;
import dev.tintwym.home_mart_backend.repository.OrderItemRepository;
import dev.tintwym.home_mart_backend.repository.OrderRepository;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import dev.tintwym.home_mart_backend.mapper.ApiJson;
import dev.tintwym.home_mart_backend.service.ShopConfig;
import dev.tintwym.home_mart_backend.utility.UlidService;
import dev.tintwym.home_mart_backend.utility.ApiResponses;
import dev.tintwym.home_mart_backend.utility.AuthSupport;
import dev.tintwym.home_mart_backend.dto.ApiRequests.LocalPaymentDefaultRequest;
import dev.tintwym.home_mart_backend.dto.ApiRequests.LocalPaymentRequest;
import dev.tintwym.home_mart_backend.dto.ApiRequests.ProfileUpdateRequest;
import java.math.BigDecimal;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AccountController {

    private final UserRepository userRepository;
    private final CartItemRepository cartItemRepository;
    private final FavoriteRepository favoriteRepository;
    private final ListingRepository listingRepository;
    private final LocalPaymentMethodRepository localPaymentMethodRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;

    public AccountController(
            UserRepository userRepository,
            CartItemRepository cartItemRepository,
            FavoriteRepository favoriteRepository,
            ListingRepository listingRepository,
            LocalPaymentMethodRepository localPaymentMethodRepository,
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository) {
        this.userRepository = userRepository;
        this.cartItemRepository = cartItemRepository;
        this.favoriteRepository = favoriteRepository;
        this.listingRepository = listingRepository;
        this.localPaymentMethodRepository = localPaymentMethodRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
    }

    @RequestMapping(value = "/profile", method = {RequestMethod.PATCH, RequestMethod.PUT})
    @Transactional
    public ResponseEntity<?> updateProfile(@RequestBody ProfileUpdateRequest request) {
        User user = requireUser();
        if (request.name() != null && !request.name().isBlank()) {
            user.setName(request.name().trim());
        }
        if (request.email() != null && !request.email().isBlank()) {
            String email = request.email().trim().toLowerCase();
            if (!email.equalsIgnoreCase(user.getEmail()) && userRepository.existsByEmail(email)) {
                return ApiResponses.unprocessable(
                        "The email has already been taken.",
                        Map.of("email", List.of("The email has already been taken.")));
            }
            if (!email.equalsIgnoreCase(user.getEmail())) {
                user.setEmail(email);
                user.setEmailVerifiedAt(null);
            }
        }
        if (request.phone() != null) {
            user.setPhone(blankToNull(request.phone()));
        }
        if (request.address() != null) {
            user.setAddress(blankToNull(request.address()));
        }
        if (request.region() != null && !request.region().isBlank()) {
            String region = request.region().trim().toUpperCase();
            if (!ShopConfig.REGIONS.contains(region)) {
                return ApiResponses.unprocessable("Invalid region.");
            }
            user.setRegion(region);
        }
        if (request.sellerType() != null && !request.sellerType().isBlank()) {
            String type = request.sellerType().trim();
            if (!type.equals("individual") && !type.equals("business")) {
                return ApiResponses.unprocessable("Invalid seller_type.");
            }
            user.setSellerType(type);
        }
        userRepository.save(user);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", ApiJson.apiUserJson(user));
        body.put("status", "profile-updated");
        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/profile")
    @Transactional
    public ResponseEntity<?> deleteProfile() {
        String userId = AuthSupport.currentUserId();
        cartItemRepository.deleteByUserId(userId);
        favoriteRepository.deleteByUserId(userId);
        for (Listing listing : listingRepository.findByUserId(userId)) {
            listingRepository.delete(listing);
        }
        localPaymentMethodRepository.deleteByUserId(userId);
        userRepository.deleteById(userId);
        return ResponseEntity.ok(Map.of("message", "Account deleted."));
    }

    @GetMapping("/upgrades")
    @Transactional(readOnly = true)
    public ResponseEntity<?> upgrades() {
        User user = requireUser();
        long listingCount = listingRepository.countByUserId(user.getId());
        int extra = user.getExtraListingSlots() == null ? 0 : user.getExtraListingSlots();
        int maxSlots = ShopConfig.maxListingSlots(user.getSellerType(), extra);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("listing_count", listingCount);
        body.put("extra_listing_slots", extra);
        body.put("max_slots", maxSlots);
        body.put("slot_price", ShopConfig.SLOT_PRICE);
        body.put("trend_price", ShopConfig.TREND_PRICE);
        body.put("trend_duration_days", ShopConfig.TREND_DURATION_DAYS);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/upgrades/slots")
    @Transactional
    public ResponseEntity<?> purchaseSlots() {
        // Slot billing is not wired to Stripe yet — refuse silent free upgrades.
        return ApiResponses.unprocessable(
                "Listing slot purchases require payment configuration. Contact support or configure Stripe checkout for slots.");
    }

    @GetMapping("/orders/purchased")
    @Transactional(readOnly = true)
    public ResponseEntity<?> purchasedOrders() {
        String userId = AuthSupport.currentUserId();
        List<OrderEntity> orders =
                orderRepository.findByUserIdAndStatusIn(userId, List.of("paid", "completed"));
        List<Map<String, Object>> data = new ArrayList<>();
        for (OrderEntity order : orders) {
            data.add(orderJson(order));
        }
        return ResponseEntity.ok(Map.of("data", data));
    }

    @GetMapping("/payment/local")
    @Transactional(readOnly = true)
    public ResponseEntity<?> listLocalPayments() {
        List<LocalPaymentMethod> methods =
                localPaymentMethodRepository.findByUserIdOrderByCreatedAtDesc(AuthSupport.currentUserId());
        List<Map<String, Object>> data = methods.stream().map(this::localJson).toList();
        return ResponseEntity.ok(Map.of("data", data));
    }

    @PostMapping("/payment/local")
    @Transactional
    public ResponseEntity<?> createLocalPayment(@RequestBody LocalPaymentRequest request) {
        if (request.type() == null || request.type().isBlank()) {
            return ApiResponses.unprocessable(
                    "Type is required.", Map.of("type", List.of("Type is required.")));
        }
        String userId = AuthSupport.currentUserId();
        boolean makeDefault = Boolean.TRUE.equals(request.makeDefault())
                || Boolean.TRUE.equals(request.isDefault());
        if (makeDefault) {
            clearDefaults(userId);
        }
        LocalPaymentMethod method = new LocalPaymentMethod();
        method.setId(UlidService.newUlid());
        method.setUserId(userId);
        method.setType(request.type().trim());
        method.setIdentifier(request.identifier() == null ? "" : request.identifier().trim());
        method.setIsDefault(makeDefault);
        localPaymentMethodRepository.save(method);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("data", localJson(method)));
    }

    @PostMapping("/payment/local/default")
    @Transactional
    public ResponseEntity<?> setDefaultLocal(@RequestBody LocalPaymentDefaultRequest request) {
        String userId = AuthSupport.currentUserId();
        LocalPaymentMethod method = localPaymentMethodRepository
                .findById(request.localPaymentMethodId())
                .filter(m -> userId.equals(m.getUserId()))
                .orElse(null);
        if (method == null) {
            return ApiResponses.notFound("Payment method not found.");
        }
        clearDefaults(userId);
        method.setIsDefault(true);
        localPaymentMethodRepository.save(method);
        return ResponseEntity.ok(Map.of("data", localJson(method)));
    }

    @DeleteMapping("/payment/local/{id}")
    @Transactional
    public ResponseEntity<?> deleteLocal(@PathVariable String id) {
        String userId = AuthSupport.currentUserId();
        LocalPaymentMethod method = localPaymentMethodRepository
                .findById(id)
                .filter(m -> userId.equals(m.getUserId()))
                .orElse(null);
        if (method == null) {
            return ApiResponses.notFound("Payment method not found.");
        }
        localPaymentMethodRepository.delete(method);
        return ResponseEntity.ok(Map.of("message", "Deleted."));
    }

    private void clearDefaults(String userId) {
        for (LocalPaymentMethod m : localPaymentMethodRepository.findByUserId(userId)) {
            if (Boolean.TRUE.equals(m.getIsDefault())) {
                m.setIsDefault(false);
                localPaymentMethodRepository.save(m);
            }
        }
    }

    private User requireUser() {
        return userRepository
                .findById(AuthSupport.currentUserId())
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Unauthenticated."));
    }

    private Map<String, Object> localJson(LocalPaymentMethod method) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", method.getId());
        map.put("type", method.getType());
        map.put("identifier", method.getIdentifier());
        map.put("is_default", Boolean.TRUE.equals(method.getIsDefault()));
        map.put("created_at", ApiJson.formatInstant(method.getCreatedAt()));
        return map;
    }

    private Map<String, Object> orderJson(OrderEntity order) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", order.getId());
        map.put("status", order.getStatus());
        map.put("total", order.getTotal() == null ? BigDecimal.ZERO : order.getTotal());
        map.put("payment_gateway", order.getPaymentGateway());
        map.put("payment_reference", order.getPaymentReference());
        map.put("stripe_session_id", order.getStripeSessionId());
        map.put("created_at", ApiJson.formatInstant(order.getCreatedAt()));
        List<Map<String, Object>> items = new ArrayList<>();
        for (OrderItem item : orderItemRepository.findByOrderId(order.getId())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.getId());
            row.put("listing_id", item.getListingId());
            row.put("quantity", item.getQuantity());
            row.put("price", item.getPrice());
            if (item.getListing() != null) {
                row.put("listing", ApiJson.listingSummaryJson(item.getListing()));
            } else {
                listingRepository.findByIdWithRelations(item.getListingId())
                        .ifPresent(l -> row.put("listing", ApiJson.listingSummaryJson(l)));
            }
            items.add(row);
        }
        map.put("items", items);
        return map;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
