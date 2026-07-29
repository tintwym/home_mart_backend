package dev.tintwym.home_mart_backend.controller.api;

import dev.tintwym.home_mart_backend.dto.ApiRequests.LocalPayRequest;
import dev.tintwym.home_mart_backend.entity.CartItem;
import dev.tintwym.home_mart_backend.entity.Listing;
import dev.tintwym.home_mart_backend.entity.OrderEntity;
import dev.tintwym.home_mart_backend.entity.OrderItem;
import dev.tintwym.home_mart_backend.mapper.ApiJson;
import dev.tintwym.home_mart_backend.repository.CartItemRepository;
import dev.tintwym.home_mart_backend.repository.ListingRepository;
import dev.tintwym.home_mart_backend.repository.OrderItemRepository;
import dev.tintwym.home_mart_backend.repository.OrderRepository;
import dev.tintwym.home_mart_backend.service.GeoRegionService;
import dev.tintwym.home_mart_backend.service.ListingSoldService;
import dev.tintwym.home_mart_backend.service.OrderFulfillmentService;
import dev.tintwym.home_mart_backend.service.OrderStatuses;
import dev.tintwym.home_mart_backend.service.ShopConfig;
import dev.tintwym.home_mart_backend.utility.ApiResponses;
import dev.tintwym.home_mart_backend.utility.AuthSupport;
import dev.tintwym.home_mart_backend.utility.UlidService;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CheckoutController {

    private final CartItemRepository cartItemRepository;
    private final ListingRepository listingRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final GeoRegionService geoRegionService;
    private final ListingSoldService listingSoldService;
    private final OrderFulfillmentService orderFulfillmentService;

    public CheckoutController(
            CartItemRepository cartItemRepository,
            ListingRepository listingRepository,
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            GeoRegionService geoRegionService,
            ListingSoldService listingSoldService,
            OrderFulfillmentService orderFulfillmentService) {
        this.cartItemRepository = cartItemRepository;
        this.listingRepository = listingRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.geoRegionService = geoRegionService;
        this.listingSoldService = listingSoldService;
        this.orderFulfillmentService = orderFulfillmentService;
    }

    @PostMapping("/checkout")
    @Transactional
    public ResponseEntity<?> checkout(
            HttpServletRequest request,
            @RequestParam(required = false) String region) {
        String userId = AuthSupport.currentUserId();
        List<CartItem> cartItems = cartItemRepository.findByUserId(userId);
        if (cartItems.isEmpty()) {
            return ApiResponses.unprocessable("Your cart is empty.");
        }

        List<String> listingIds = cartItems.stream().map(CartItem::getListingId).toList();
        Map<String, Listing> listingsById = new LinkedHashMap<>();
        for (Listing l : listingRepository.findDetailedByIdIn(listingIds)) {
            listingsById.put(l.getId(), l);
        }

        BigDecimal total = BigDecimal.ZERO;
        List<OrderItem> pendingItems = new ArrayList<>();
        List<Listing> purchasableListings = new ArrayList<>();
        for (CartItem cartItem : cartItems) {
            Listing listing = listingsById.get(cartItem.getListingId());
            if (listing == null || userId.equals(listing.getUserId())) {
                continue;
            }
            if (listingSoldService.isSold(listing.getId())) {
                continue;
            }
            OrderItem item = new OrderItem();
            item.setId(UlidService.newUlid());
            item.setListingId(listing.getId());
            item.setQuantity(1);
            item.setPrice(listing.getPrice());
            pendingItems.add(item);
            purchasableListings.add(listing);
            String sellerRegion = listing.getUser() != null ? listing.getUser().getRegion() : null;
            total = total.add(ShopConfig.listingPriceUsd(listing.getPrice(), sellerRegion));
        }
        if (pendingItems.isEmpty()) {
            return ApiResponses.unprocessable("No purchasable items in cart.");
        }

        String detected = geoRegionService.detect(request);
        String flowRegion = resolveCheckoutRegion(purchasableListings, detected, region);
        String paymentFlow;
        List<String> availableMethods;
        if ("MM".equals(flowRegion)) {
            paymentFlow = "c2c_mm";
            availableMethods = List.of("meetup");
        } else if ("VN".equals(flowRegion)) {
            paymentFlow = "c2c_vn";
            availableMethods = List.of("meetup");
        } else {
            paymentFlow = "stripe";
            availableMethods = List.of("card_test");
        }

        orderFulfillmentService.releaseBuyerReservations(userId, null);

        OrderEntity order = new OrderEntity();
        order.setId(UlidService.newUlid());
        order.setUserId(userId);
        order.setStatus(OrderStatuses.PENDING);
        order.setTotal(total);
        orderRepository.save(order);

        for (OrderItem item : pendingItems) {
            item.setOrderId(order.getId());
            orderItemRepository.save(item);
        }

        if ("c2c_mm".equals(paymentFlow) || "c2c_vn".equals(paymentFlow) || "stripe".equals(paymentFlow)) {
            orderFulfillmentService.markReserved(order);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("order", orderJson(order));
        body.put("payment_flow", paymentFlow);
        body.put("available_methods", availableMethods);
        body.put("test_mode", true);
        body.put("flow_region", flowRegion);
        if ("c2c_mm".equals(paymentFlow) || "c2c_vn".equals(paymentFlow)) {
            body.put("flow", "c2c");
            body.put("default_method", "meetup");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/checkout/mm/pay")
    @Transactional
    public ResponseEntity<?> payMyanmar(@RequestBody LocalPayRequest request) {
        return arrangeC2c(request, "MM");
    }

    @PostMapping("/checkout/vn/pay")
    @Transactional
    public ResponseEntity<?> payVietnam(@RequestBody LocalPayRequest request) {
        return arrangeC2c(request, "VN");
    }

    @PostMapping("/checkout/mm/arrange")
    @Transactional
    public ResponseEntity<?> arrangeMyanmar(@RequestBody LocalPayRequest request) {
        return arrangeC2c(request, "MM");
    }

    @PostMapping("/checkout/vn/arrange")
    @Transactional
    public ResponseEntity<?> arrangeVietnam(@RequestBody LocalPayRequest request) {
        return arrangeC2c(request, "VN");
    }

    @PostMapping("/orders/{id}/complete")
    @Transactional
    public ResponseEntity<?> completeOrder(@PathVariable String id) {
        String userId = AuthSupport.currentUserId();
        OrderEntity order = orderRepository.findById(id).orElse(null);
        if (order == null || !userId.equals(order.getUserId())) {
            return ApiResponses.notFound("Order not found.");
        }
        if (!OrderStatuses.PAID.equals(order.getStatus())
                && !OrderStatuses.ARRANGED.equals(order.getStatus())) {
            return ApiResponses.unprocessable("Order cannot be completed in its current state.");
        }
        orderFulfillmentService.markCompleted(order);
        return ResponseEntity.ok(Map.of(
                "order", orderJson(order),
                "message", "Order marked completed."));
    }

    @GetMapping("/orders")
    @Transactional(readOnly = true)
    public ResponseEntity<?> orders() {
        String userId = AuthSupport.currentUserId();
        List<Map<String, Object>> data = orderRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(o -> OrderStatuses.BUYER_VISIBLE.contains(o.getStatus())
                        || OrderStatuses.PENDING.equals(o.getStatus()))
                .map(this::orderJson)
                .toList();
        return ResponseEntity.ok(Map.of("data", data));
    }

    private ResponseEntity<?> arrangeC2c(LocalPayRequest request, String requiredRegion) {
        String userId = AuthSupport.currentUserId();
        if (request.orderId() == null) {
            return ApiResponses.unprocessable("order_id is required.");
        }
        OrderEntity order = orderRepository.findById(request.orderId()).orElse(null);
        if (order == null
                || !userId.equals(order.getUserId())
                || !(OrderStatuses.PENDING.equals(order.getStatus())
                        || OrderStatuses.RESERVED.equals(order.getStatus()))) {
            return ApiResponses.notFound("Order not found.");
        }
        String orderRegion = resolveOrderRegion(order);
        if (!requiredRegion.equals(orderRegion)) {
            return ApiResponses.unprocessable("This order is not eligible for " + requiredRegion + " meetup checkout.");
        }
        if (orderFulfillmentService.hasSoldConflictExcluding(order.getId())) {
            return ApiResponses.unprocessable("One or more items were already reserved or sold.");
        }
        order.setPaymentGateway("c2c");
        order.setPaymentReference("meetup_test");
        order.setStatus(OrderStatuses.ARRANGED);
        orderRepository.save(order);
        cartItemRepository.deleteByUserId(userId);
        return ResponseEntity.ok(Map.of(
                "order", orderJson(order),
                "test_mode", true,
                "message", "Meetup arranged (test). Chat with the seller to pay and meet."));
    }

    private String resolveOrderRegion(OrderEntity order) {
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        List<String> ids = items.stream().map(OrderItem::getListingId).toList();
        List<Listing> listings = ids.isEmpty() ? List.of() : listingRepository.findDetailedByIdIn(ids);
        return resolveCheckoutRegion(listings, null, null);
    }

    static String resolveCheckoutRegion(
            List<Listing> listings, String buyerGeo, String requested) {
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
            String fallback = requested != null && !requested.isBlank()
                    ? requested.trim().toUpperCase()
                    : buyerGeo;
            if ("MM".equals(fallback) || "VN".equals(fallback)) {
                return fallback;
            }
        }
        return "STRIPE";
    }

    private Map<String, Object> orderJson(OrderEntity order) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", order.getId());
        map.put("status", order.getStatus());
        map.put("total", order.getTotal());
        map.put("payment_gateway", order.getPaymentGateway());
        map.put("payment_reference", order.getPaymentReference());
        map.put("stripe_session_id", order.getStripeSessionId());
        map.put("created_at", ApiJson.formatInstant(order.getCreatedAt()));
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        List<String> listingIds = items.stream().map(OrderItem::getListingId).toList();
        Set<String> sold = listingSoldService.soldAmong(listingIds);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (OrderItem item : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.getId());
            row.put("listing_id", item.getListingId());
            row.put("quantity", item.getQuantity());
            row.put("price", item.getPrice());
            listingRepository.findByIdWithRelations(item.getListingId())
                    .ifPresent(l -> row.put(
                            "listing",
                            ApiJson.listingSummaryJson(l, sold.contains(l.getId()))));
            rows.add(row);
        }
        map.put("items", rows);
        return map;
    }
}
