package dev.tintwym.home_mart_backend.controller.api;

import dev.tintwym.home_mart_backend.entity.CartItem;
import dev.tintwym.home_mart_backend.entity.Listing;
import dev.tintwym.home_mart_backend.entity.LocalPaymentMethod;
import dev.tintwym.home_mart_backend.entity.OrderEntity;
import dev.tintwym.home_mart_backend.entity.OrderItem;
import dev.tintwym.home_mart_backend.repository.CartItemRepository;
import dev.tintwym.home_mart_backend.repository.ListingRepository;
import dev.tintwym.home_mart_backend.repository.LocalPaymentMethodRepository;
import dev.tintwym.home_mart_backend.repository.OrderItemRepository;
import dev.tintwym.home_mart_backend.repository.OrderRepository;
import dev.tintwym.home_mart_backend.mapper.ApiJson;
import dev.tintwym.home_mart_backend.service.ShopConfig;
import dev.tintwym.home_mart_backend.utility.UlidService;
import dev.tintwym.home_mart_backend.utility.ApiResponses;
import dev.tintwym.home_mart_backend.utility.AuthSupport;
import dev.tintwym.home_mart_backend.dto.ApiRequests.LocalPayRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final LocalPaymentMethodRepository localPaymentMethodRepository;

    public CheckoutController(
            CartItemRepository cartItemRepository,
            ListingRepository listingRepository,
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            LocalPaymentMethodRepository localPaymentMethodRepository) {
        this.cartItemRepository = cartItemRepository;
        this.listingRepository = listingRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.localPaymentMethodRepository = localPaymentMethodRepository;
    }

    @PostMapping("/checkout")
    @Transactional
    public ResponseEntity<?> checkout(@RequestParam(required = false) String region) {
        String userId = AuthSupport.currentUserId();
        List<CartItem> cartItems = cartItemRepository.findByUserId(userId);
        if (cartItems.isEmpty()) {
            return ApiResponses.unprocessable("Your cart is empty.");
        }

        BigDecimal total = BigDecimal.ZERO;
        List<OrderItem> pendingItems = new ArrayList<>();
        for (CartItem cartItem : cartItems) {
            Listing listing = listingRepository.findById(cartItem.getListingId()).orElse(null);
            if (listing == null || userId.equals(listing.getUserId())) {
                continue;
            }
            if (orderItemRepository.existsByListingIdAndOrder_StatusIn(
                    listing.getId(), List.of("paid", "completed"))) {
                continue;
            }
            OrderItem item = new OrderItem();
            item.setId(UlidService.newUlid());
            item.setListingId(listing.getId());
            item.setQuantity(1);
            item.setPrice(listing.getPrice());
            pendingItems.add(item);
            total = total.add(listing.getPrice());
        }
        if (pendingItems.isEmpty()) {
            return ApiResponses.unprocessable("No purchasable items in cart.");
        }

        String flowRegion = region == null ? null : region.trim().toUpperCase();
        String paymentFlow;
        List<String> availableMethods;
        if ("MM".equals(flowRegion)) {
            paymentFlow = "mm_local";
            availableMethods = ShopConfig.MYANMAR_PAYMENT_METHODS;
        } else if ("VN".equals(flowRegion)) {
            paymentFlow = "vn_local";
            availableMethods = ShopConfig.VIETNAM_PAYMENT_METHODS;
        } else {
            paymentFlow = "stripe";
            availableMethods = List.of("card");
        }

        OrderEntity order = new OrderEntity();
        order.setId(UlidService.newUlid());
        order.setUserId(userId);
        order.setStatus("pending");
        order.setTotal(total);
        orderRepository.save(order);

        for (OrderItem item : pendingItems) {
            item.setOrderId(order.getId());
            orderItemRepository.save(item);
        }

        List<Map<String, Object>> savedMethods = localPaymentMethodRepository
                .findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::localJson)
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("order", orderJson(order));
        body.put("payment_flow", paymentFlow);
        body.put("available_methods", availableMethods);
        body.put("saved_methods", savedMethods);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/checkout/mm/pay")
    @Transactional
    public ResponseEntity<?> payMyanmar(@RequestBody LocalPayRequest request) {
        return payLocal(request, ShopConfig.MYANMAR_PAYMENT_METHODS, "mm_local");
    }

    @PostMapping("/checkout/vn/pay")
    @Transactional
    public ResponseEntity<?> payVietnam(@RequestBody LocalPayRequest request) {
        return payLocal(request, ShopConfig.VIETNAM_PAYMENT_METHODS, "vn_local");
    }

    @GetMapping("/orders")
    @Transactional(readOnly = true)
    public ResponseEntity<?> orders() {
        String userId = AuthSupport.currentUserId();
        List<Map<String, Object>> data = orderRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::orderJson)
                .toList();
        return ResponseEntity.ok(Map.of("data", data));
    }

    private ResponseEntity<?> payLocal(LocalPayRequest request, List<String> allowlist, String gateway) {
        String userId = AuthSupport.currentUserId();
        if (request.orderId() == null || request.method() == null) {
            return ApiResponses.unprocessable("order_id and method are required.");
        }
        String method = request.method().trim().toLowerCase();
        if (!allowlist.contains(method)) {
            return ApiResponses.unprocessable("Unsupported payment method.");
        }
        OrderEntity order = orderRepository.findById(request.orderId()).orElse(null);
        if (order == null || !userId.equals(order.getUserId()) || !"pending".equals(order.getStatus())) {
            return ApiResponses.notFound("Order not found.");
        }

        if (Boolean.TRUE.equals(request.saveMethod())
                && request.identifier() != null
                && !request.identifier().isBlank()) {
            LocalPaymentMethod saved = new LocalPaymentMethod();
            saved.setId(UlidService.newUlid());
            saved.setUserId(userId);
            saved.setType(method);
            saved.setIdentifier(request.identifier().trim());
            saved.setIsDefault(false);
            localPaymentMethodRepository.save(saved);
        }

        order.setPaymentGateway(gateway);
        order.setPaymentReference(request.identifier());
        order.setStatus("paid");
        orderRepository.save(order);
        cartItemRepository.deleteByUserId(userId);

        return ResponseEntity.ok(Map.of("order", orderJson(order), "message", "Payment recorded."));
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
        List<Map<String, Object>> items = new ArrayList<>();
        for (OrderItem item : orderItemRepository.findByOrderId(order.getId())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.getId());
            row.put("listing_id", item.getListingId());
            row.put("quantity", item.getQuantity());
            row.put("price", item.getPrice());
            listingRepository.findByIdWithRelations(item.getListingId())
                    .ifPresent(l -> row.put("listing", ApiJson.listingSummaryJson(l)));
            items.add(row);
        }
        map.put("items", items);
        return map;
    }

    private Map<String, Object> localJson(LocalPaymentMethod method) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", method.getId());
        map.put("type", method.getType());
        map.put("identifier", method.getIdentifier());
        map.put("is_default", Boolean.TRUE.equals(method.getIsDefault()));
        return map;
    }
}
