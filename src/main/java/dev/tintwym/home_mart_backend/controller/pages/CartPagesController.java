package dev.tintwym.home_mart_backend.controller.pages;

import dev.tintwym.home_mart_backend.config.AppProperties;
import dev.tintwym.home_mart_backend.entity.CartItem;
import dev.tintwym.home_mart_backend.entity.Listing;
import dev.tintwym.home_mart_backend.entity.OrderEntity;
import dev.tintwym.home_mart_backend.entity.OrderItem;
import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.mapper.ApiJson;
import dev.tintwym.home_mart_backend.repository.CartItemRepository;
import dev.tintwym.home_mart_backend.repository.ListingRepository;
import dev.tintwym.home_mart_backend.repository.OrderItemRepository;
import dev.tintwym.home_mart_backend.repository.OrderRepository;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import dev.tintwym.home_mart_backend.service.GeoRegionService;
import dev.tintwym.home_mart_backend.service.InertiaService;
import dev.tintwym.home_mart_backend.service.ListingSoldService;
import dev.tintwym.home_mart_backend.service.OrderFulfillmentService;
import dev.tintwym.home_mart_backend.service.OrderStatuses;
import dev.tintwym.home_mart_backend.service.ShopConfig;
import dev.tintwym.home_mart_backend.service.StripeService;
import dev.tintwym.home_mart_backend.utility.UlidService;
import com.stripe.model.checkout.Session;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CartPagesController extends PageControllerSupport {

    private final CartItemRepository cartItemRepository;
    private final ListingRepository listingRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final StripeService stripeService;
    private final GeoRegionService geoRegionService;
    private final AppProperties appProperties;
    private final OrderFulfillmentService orderFulfillmentService;
    private final ListingSoldService listingSoldService;

    public CartPagesController(
            InertiaService inertia,
            UserRepository userRepository,
            CartItemRepository cartItemRepository,
            ListingRepository listingRepository,
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            StripeService stripeService,
            GeoRegionService geoRegionService,
            AppProperties appProperties,
            OrderFulfillmentService orderFulfillmentService,
            ListingSoldService listingSoldService) {
        super(inertia, userRepository);
        this.cartItemRepository = cartItemRepository;
        this.listingRepository = listingRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.stripeService = stripeService;
        this.geoRegionService = geoRegionService;
        this.appProperties = appProperties;
        this.orderFulfillmentService = orderFulfillmentService;
        this.listingSoldService = listingSoldService;
    }

    @GetMapping("/cart")
    @Transactional(readOnly = true)
    public ResponseEntity<?> index(HttpServletRequest request, HttpServletResponse response) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        return render(request, response, "cart/index", Map.of("items", cartItemMaps(requireUser().getId())));
    }

    @GetMapping(value = "/cart/json", produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional(readOnly = true)
    public ResponseEntity<?> cartJson() {
        if (currentUserId().isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(cartItemMaps(requireUser().getId()));
    }

    @PostMapping("/checkout")
    @Transactional
    public ResponseEntity<?> checkout(HttpServletRequest request, HttpServletResponse response) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        User user = requireUser();
        List<CartItem> cartItems = cartItemRepository.findByUserId(user.getId());
        if (cartItems.isEmpty()) {
            return redirectWithError(request, response, "/cart", "Your cart is empty.");
        }

        List<String> listingIds = cartItems.stream().map(CartItem::getListingId).toList();
        Map<String, Listing> listings = new LinkedHashMap<>();
        for (Listing l : listingRepository.findDetailedByIdIn(listingIds)) {
            listings.put(l.getId(), l);
        }

        List<CartItem> purchasable = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (CartItem item : cartItems) {
            Listing listing = listings.get(item.getListingId());
            if (listing == null) {
                continue;
            }
            if (user.getId().equals(listing.getUserId())) {
                continue;
            }
            if (listingSoldService.isSold(listing.getId())) {
                continue;
            }
            purchasable.add(item);
            String sellerRegion = listing.getUser() != null ? listing.getUser().getRegion() : null;
            total = total.add(ShopConfig.listingPriceUsd(listing.getPrice(), sellerRegion));
        }

        if (purchasable.isEmpty()) {
            return redirectWithError(request, response, "/cart",
                    "No purchasable items in cart (sold or own listings removed).");
        }

        orderFulfillmentService.releaseBuyerReservations(user.getId(), null);

        OrderEntity order = new OrderEntity();
        order.setId(UlidService.newUlid());
        order.setUserId(user.getId());
        order.setStatus(OrderStatuses.PENDING);
        order.setTotal(total);
        orderRepository.save(order);

        List<Listing> purchasableListings = new ArrayList<>();
        for (CartItem item : purchasable) {
            Listing listing = listings.get(item.getListingId());
            if (listing == null) {
                continue;
            }
            purchasableListings.add(listing);
            OrderItem oi = new OrderItem();
            oi.setId(UlidService.newUlid());
            oi.setOrderId(order.getId());
            oi.setListingId(listing.getId());
            oi.setQuantity(1);
            oi.setPrice(listing.getPrice());
            orderItemRepository.save(oi);
        }

        String flowRegion = resolveCheckoutRegion(purchasableListings, geoRegionService.detect(request));
        if ("MM".equals(flowRegion)) {
            orderFulfillmentService.markReserved(order);
            return render(request, response, "checkout/myanmar", checkoutPageProps(order, "MM"));
        }
        if ("VN".equals(flowRegion)) {
            orderFulfillmentService.markReserved(order);
            return render(request, response, "checkout/vietnam", checkoutPageProps(order, "VN"));
        }
        return checkoutStripe(request, response, user, order);
    }

    @PostMapping("/checkout/mm/arrange")
    @Transactional
    public ResponseEntity<?> arrangeMyanmar(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam("order_id") String orderId) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        return completeC2cArrange(request, response, orderId, "MM",
                "Meetup arranged (test). Message the seller to agree payment (MMQR) and pickup.");
    }

    @PostMapping("/checkout/vn/arrange")
    @Transactional
    public ResponseEntity<?> arrangeVietnam(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam("order_id") String orderId) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        return completeC2cArrange(request, response, orderId, "VN",
                "Đã hẹn giao dịch (thử nghiệm). Nhắn người bán để thỏa thuận thanh toán (VNQR) và gặp mặt.");
    }

    /** @deprecated Prefer /checkout/mm/arrange for C2C. Kept for older clients. */
    @PostMapping("/checkout/mm/pay")
    @Transactional
    public ResponseEntity<?> payMyanmar(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam("order_id") String orderId,
            @RequestParam(required = false) String method,
            @RequestParam(value = "identifier", required = false) String identifier,
            @RequestParam(value = "save_method", required = false) Boolean saveMethod) {
        return arrangeMyanmar(request, response, orderId);
    }

    /** @deprecated Prefer /checkout/vn/arrange for C2C. Kept for older clients. */
    @PostMapping("/checkout/vn/pay")
    @Transactional
    public ResponseEntity<?> payVietnam(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam("order_id") String orderId,
            @RequestParam(required = false) String method,
            @RequestParam(value = "identifier", required = false) String identifier,
            @RequestParam(value = "save_method", required = false) Boolean saveMethod) {
        return arrangeVietnam(request, response, orderId);
    }

    @GetMapping("/checkout/success")
    @Transactional
    public ResponseEntity<?> success(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam(value = "session_id", required = false) String sessionId) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        if (sessionId == null || sessionId.isBlank()) {
            return redirectWithError(request, response, "/cart", "Invalid checkout session.");
        }
        if (!stripeService.isConfigured()) {
            return redirectWithError(request, response, "/cart",
                    "Card payments are not configured. Payment could not be verified.");
        }
        try {
            Session session = stripeService.retrieveSession(sessionId);
            String outcome = orderFulfillmentService.fulfillStripePaidSession(session, requireUser().getId());
            return switch (outcome) {
                case "paid", "already_paid" -> redirectWithStatus(
                        request, response, "/settings/orders",
                        "Test payment successful. Thank you! (Stripe test mode)");
                case "conflict_refunded" -> redirectWithError(
                        request, response, "/cart",
                        "Item was already sold. Your test payment was refunded.");
                case "conflict_no_refund" -> redirectWithError(
                        request, response, "/cart",
                        "Item was already sold. Contact support about your test payment.");
                case "wrong_user", "not_found" -> redirectWithError(
                        request, response, "/cart", "Order not found.");
                default -> redirectWithError(request, response, "/cart", "Payment is not completed.");
            };
        } catch (Exception e) {
            return redirectWithError(request, response, "/cart", "Invalid checkout session.");
        }
    }

    private ResponseEntity<?> completeC2cArrange(
            HttpServletRequest request,
            HttpServletResponse response,
            String orderId,
            String requiredRegion,
            String statusMessage) {
        User user = requireUser();
        OrderEntity order = orderRepository.findById(orderId)
                .filter(o -> o.getUserId().equals(user.getId()))
                .filter(o -> OrderStatuses.PENDING.equals(o.getStatus())
                        || OrderStatuses.RESERVED.equals(o.getStatus()))
                .orElse(null);
        if (order == null) {
            return redirectWithError(request, response, "/cart", "Order not found.");
        }
        String orderRegion = resolveOrderCheckoutRegion(order);
        if (!requiredRegion.equals(orderRegion)) {
            return redirectWithError(request, response, "/cart", "Invalid region for this order.");
        }
        if (orderFulfillmentService.hasSoldConflictExcluding(order.getId())) {
            return redirectWithError(request, response, "/cart",
                    "One or more items were already reserved or sold.");
        }
        order.setPaymentGateway("c2c");
        order.setPaymentReference("meetup_test");
        order.setStatus(OrderStatuses.ARRANGED);
        orderRepository.save(order);
        cartItemRepository.deleteByUserId(user.getId());
        return redirectWithStatus(request, response, "/settings/orders", statusMessage);
    }

    private ResponseEntity<?> checkoutStripe(
            HttpServletRequest request,
            HttpServletResponse response,
            User user,
            OrderEntity order) {
        if (!stripeService.isConfigured()) {
            return redirectWithError(request, response, "/cart",
                    "Test card payments need STRIPE_KEY=pk_test_… and STRIPE_SECRET=sk_test_…");
        }
        try {
            String success = appProperties.getUrl() + "/checkout/success?session_id={CHECKOUT_SESSION_ID}";
            String cancel = appProperties.getUrl() + "/cart";
            Session session = stripeService.createOrderCheckoutSession(
                    user, order.getId(), order.getTotal(), "USD", success, cancel);
            order.setStripeSessionId(session.getId());
            order.setPaymentGateway("stripe_test");
            orderRepository.save(order);
            orderFulfillmentService.markReserved(order);
            userRepository.save(user);
            return inertia.externalRedirect(request, session.getUrl());
        } catch (Exception e) {
            return redirectWithError(request, response, "/cart",
                    "Test payment could not be started – check Stripe test keys.");
        }
    }

    /**
     * Prefer seller listing regions: all MM → C2C MM, all VN → C2C VN, otherwise Stripe.
     * Falls back to buyer geo when sellers have no region set.
     */
    static String resolveCheckoutRegion(List<Listing> listings, String buyerGeo) {
        Set<String> regions = new HashSet<>();
        for (Listing listing : listings) {
            String r = listing.getUser() != null ? listing.getUser().getRegion() : null;
            if (r != null && !r.isBlank()) {
                regions.add(r.trim().toUpperCase());
            }
        }
        if (regions.size() == 1) {
            String only = regions.iterator().next();
            if ("MM".equals(only) || "VN".equals(only)) {
                return only;
            }
            return "STRIPE";
        }
        if (regions.isEmpty()) {
            if ("MM".equals(buyerGeo) || "VN".equals(buyerGeo)) {
                return buyerGeo;
            }
        }
        return "STRIPE";
    }

    private String resolveOrderCheckoutRegion(OrderEntity order) {
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        List<String> ids = items.stream().map(OrderItem::getListingId).toList();
        List<Listing> listings = ids.isEmpty() ? List.of() : listingRepository.findDetailedByIdIn(ids);
        return resolveCheckoutRegion(listings, null);
    }

    private Map<String, Object> checkoutPageProps(OrderEntity order, String region) {
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        List<String> listingIds = items.stream().map(OrderItem::getListingId).toList();
        Map<String, Listing> listings = new LinkedHashMap<>();
        listingRepository.findDetailedByIdIn(listingIds).forEach(l -> listings.put(l.getId(), l));
        Set<String> sold = listingSoldService.soldAmong(listingIds);

        List<Map<String, Object>> itemMaps = new ArrayList<>();
        for (OrderItem oi : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", oi.getId());
            m.put("order_id", oi.getOrderId());
            m.put("listing_id", oi.getListingId());
            m.put("quantity", oi.getQuantity());
            m.put("price", oi.getPrice());
            Listing listing = listings.get(oi.getListingId());
            if (listing != null) {
                m.put("listing", ApiJson.listingSummaryJson(listing, sold.contains(listing.getId())));
            }
            itemMaps.add(m);
        }

        Map<String, Object> orderMap = new LinkedHashMap<>();
        orderMap.put("id", order.getId());
        orderMap.put("user_id", order.getUserId());
        orderMap.put("status", order.getStatus());
        orderMap.put("total", order.getTotal());
        orderMap.put("items", itemMaps);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("order", orderMap);
        props.put("region", region);
        props.put("flow", "c2c");
        props.put("test_mode", true);
        return props;
    }

    private List<Map<String, Object>> cartItemMaps(String userId) {
        List<CartItem> items = cartItemRepository.findByUserId(userId);
        items.sort(Comparator.comparing(CartItem::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        List<String> listingIds = items.stream().map(CartItem::getListingId).toList();
        Map<String, Listing> listings = new LinkedHashMap<>();
        if (!listingIds.isEmpty()) {
            listingRepository.findDetailedByIdIn(listingIds).forEach(l -> listings.put(l.getId(), l));
        }
        Set<String> sold = listingSoldService.soldAmong(listingIds);
        List<Map<String, Object>> result = new ArrayList<>();
        for (CartItem item : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", item.getId());
            m.put("user_id", item.getUserId());
            m.put("listing_id", item.getListingId());
            m.put("created_at", ApiJson.formatInstant(item.getCreatedAt()));
            Listing listing = listings.get(item.getListingId());
            if (listing != null) {
                m.put("listing", ApiJson.listingSummaryJson(listing, sold.contains(listing.getId())));
            }
            result.add(m);
        }
        return result;
    }
}
