package dev.tintwym.home_mart_backend.controller.pages;

import dev.tintwym.home_mart_backend.config.AppProperties;
import dev.tintwym.home_mart_backend.entity.CartItem;
import dev.tintwym.home_mart_backend.entity.Listing;
import dev.tintwym.home_mart_backend.entity.LocalPaymentMethod;
import dev.tintwym.home_mart_backend.entity.OrderEntity;
import dev.tintwym.home_mart_backend.entity.OrderItem;
import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.repository.CartItemRepository;
import dev.tintwym.home_mart_backend.repository.ListingRepository;
import dev.tintwym.home_mart_backend.repository.LocalPaymentMethodRepository;
import dev.tintwym.home_mart_backend.repository.OrderItemRepository;
import dev.tintwym.home_mart_backend.repository.OrderRepository;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import dev.tintwym.home_mart_backend.mapper.ApiJson;
import dev.tintwym.home_mart_backend.service.GeoRegionService;
import dev.tintwym.home_mart_backend.service.InertiaService;
import dev.tintwym.home_mart_backend.service.ShopConfig;
import dev.tintwym.home_mart_backend.service.StripeService;
import dev.tintwym.home_mart_backend.utility.UlidService;
import com.stripe.model.checkout.Session;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final LocalPaymentMethodRepository localPaymentMethodRepository;
    private final StripeService stripeService;
    private final GeoRegionService geoRegionService;
    private final AppProperties appProperties;

    public CartPagesController(
            InertiaService inertia,
            UserRepository userRepository,
            CartItemRepository cartItemRepository,
            ListingRepository listingRepository,
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            LocalPaymentMethodRepository localPaymentMethodRepository,
            StripeService stripeService,
            GeoRegionService geoRegionService,
            AppProperties appProperties) {
        super(inertia, userRepository);
        this.cartItemRepository = cartItemRepository;
        this.listingRepository = listingRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.localPaymentMethodRepository = localPaymentMethodRepository;
        this.stripeService = stripeService;
        this.geoRegionService = geoRegionService;
        this.appProperties = appProperties;
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
            if (orderItemRepository.existsByListingIdAndOrder_StatusIn(
                    listing.getId(), List.of("paid", "completed"))) {
                continue;
            }
            purchasable.add(item);
            total = total.add(listing.getPrice());
        }

        if (purchasable.isEmpty()) {
            return redirectWithError(request, response, "/cart",
                    "No purchasable items in cart (sold or own listings removed).");
        }

        OrderEntity order = new OrderEntity();
        order.setId(UlidService.newUlid());
        order.setUserId(user.getId());
        order.setStatus("pending");
        order.setTotal(total);
        orderRepository.save(order);

        for (CartItem item : purchasable) {
            Listing listing = listings.get(item.getListingId());
            if (listing == null) {
                continue;
            }
            OrderItem oi = new OrderItem();
            oi.setId(UlidService.newUlid());
            oi.setOrderId(order.getId());
            oi.setListingId(listing.getId());
            oi.setQuantity(1);
            oi.setPrice(listing.getPrice());
            orderItemRepository.save(oi);
        }

        String region = geoRegionService.detect(request);
        if ("MM".equals(region)) {
            return render(request, response, "checkout/myanmar", checkoutPageProps(order, user));
        }
        if ("VN".equals(region)) {
            return render(request, response, "checkout/vietnam", checkoutPageProps(order, user));
        }
        return checkoutStripe(request, response, user, order, purchasable, listings);
    }

    @PostMapping("/checkout/mm/pay")
    @Transactional
    public ResponseEntity<?> payMyanmar(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam("order_id") String orderId,
            @RequestParam String method,
            @RequestParam(value = "identifier", required = false) String identifier,
            @RequestParam(value = "save_method", required = false) Boolean saveMethod) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        if (!"MM".equals(geoRegionService.detect(request))) {
            return redirectWithError(request, response, "/cart", "Invalid request.");
        }
        return completeLocalPay(request, response, orderId, method, identifier, saveMethod,
                ShopConfig.MYANMAR_PAYMENT_METHODS, "mm_local", "Payment submitted. Thank you!");
    }

    @PostMapping("/checkout/vn/pay")
    @Transactional
    public ResponseEntity<?> payVietnam(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam("order_id") String orderId,
            @RequestParam String method,
            @RequestParam(value = "identifier", required = false) String identifier,
            @RequestParam(value = "save_method", required = false) Boolean saveMethod) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        if (!"VN".equals(geoRegionService.detect(request))) {
            return redirectWithError(request, response, "/cart", "Yêu cầu không hợp lệ.");
        }
        return completeLocalPay(request, response, orderId, method, identifier, saveMethod,
                ShopConfig.VIETNAM_PAYMENT_METHODS, "vn_local", "Thanh toán thành công. Cảm ơn bạn!");
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
            return redirectWithStatus(request, response, "/settings/orders", "Order complete.");
        }
        try {
            Session session = stripeService.retrieveSession(sessionId);
            String orderId = session.getMetadata() == null ? null : session.getMetadata().get("order_id");
            if (orderId != null) {
                orderRepository.findById(orderId).ifPresent(order -> {
                    if (order.getUserId().equals(requireUser().getId())) {
                        order.setStatus("paid");
                        orderRepository.save(order);
                    }
                });
            }
            return redirectWithStatus(request, response, "/settings/orders", "Payment successful. Thank you!");
        } catch (Exception e) {
            return redirectWithError(request, response, "/cart", "Invalid checkout session.");
        }
    }

    private ResponseEntity<?> completeLocalPay(
            HttpServletRequest request,
            HttpServletResponse response,
            String orderId,
            String method,
            String identifier,
            Boolean saveMethod,
            List<String> allowedMethods,
            String gateway,
            String statusMessage) {
        User user = requireUser();
        OrderEntity order = orderRepository.findById(orderId)
                .filter(o -> o.getUserId().equals(user.getId()) && "pending".equals(o.getStatus()))
                .orElse(null);
        if (order == null) {
            return redirectWithError(request, response, "/cart", "Order not found.");
        }
        String idTrim = identifier == null ? "" : identifier.trim();
        if (Boolean.TRUE.equals(saveMethod) && !idTrim.isEmpty() && allowedMethods.contains(method)) {
            boolean exists = localPaymentMethodRepository.findByUserId(user.getId()).stream()
                    .anyMatch(pm -> method.equals(pm.getType()) && idTrim.equals(pm.getIdentifier()));
            if (!exists) {
                LocalPaymentMethod pm = new LocalPaymentMethod();
                pm.setId(UlidService.newUlid());
                pm.setUserId(user.getId());
                pm.setType(method);
                pm.setIdentifier(idTrim);
                pm.setIsDefault(localPaymentMethodRepository.findByUserId(user.getId()).isEmpty());
                localPaymentMethodRepository.save(pm);
            }
        }
        order.setPaymentGateway(gateway);
        order.setPaymentReference(method + (idTrim.isEmpty() ? "" : ":" + idTrim));
        order.setStatus("paid");
        orderRepository.save(order);
        cartItemRepository.deleteByUserId(user.getId());
        return redirectWithStatus(request, response, "/settings/orders", statusMessage);
    }

    private ResponseEntity<?> checkoutStripe(
            HttpServletRequest request,
            HttpServletResponse response,
            User user,
            OrderEntity order,
            List<CartItem> cartItems,
            Map<String, Listing> listings) {
        if (!stripeService.isConfigured()) {
            return redirectWithError(request, response, "/cart",
                    "Card payments are not configured. Set STRIPE_KEY and STRIPE_SECRET.");
        }
        try {
            BigDecimal amount = order.getTotal();
            String success = appProperties.getUrl() + "/checkout/success?session_id={CHECKOUT_SESSION_ID}";
            String cancel = appProperties.getUrl() + "/cart";
            Session session = stripeService.createOrderCheckoutSession(
                    user, order.getId(), amount, "USD", success, cancel);
            order.setStripeSessionId(session.getId());
            order.setPaymentGateway("stripe");
            orderRepository.save(order);
            userRepository.save(user);
            // Keep cart until payment succeeds (/checkout/success).
            return inertia.externalRedirect(request, session.getUrl());
        } catch (Exception e) {
            return redirectWithError(request, response, "/cart",
                    "Payment could not be started – check your Stripe configuration.");
        }
    }

    private Map<String, Object> checkoutPageProps(OrderEntity order, User user) {
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        List<String> listingIds = items.stream().map(OrderItem::getListingId).toList();
        Map<String, Listing> listings = new LinkedHashMap<>();
        listingRepository.findDetailedByIdIn(listingIds).forEach(l -> listings.put(l.getId(), l));

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
                m.put("listing", ApiJson.listingSummaryJson(listing));
            }
            itemMaps.add(m);
        }

        Map<String, Object> orderMap = new LinkedHashMap<>();
        orderMap.put("id", order.getId());
        orderMap.put("user_id", order.getUserId());
        orderMap.put("status", order.getStatus());
        orderMap.put("total", order.getTotal());
        orderMap.put("items", itemMaps);

        List<Map<String, Object>> saved = localPaymentMethodRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .sorted(Comparator.comparing((LocalPaymentMethod pm) -> !Boolean.TRUE.equals(pm.getIsDefault())))
                .map(this::localPmJson)
                .toList();

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("order", orderMap);
        props.put("savedMethods", saved);
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
        List<Map<String, Object>> result = new ArrayList<>();
        for (CartItem item : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", item.getId());
            m.put("user_id", item.getUserId());
            m.put("listing_id", item.getListingId());
            m.put("created_at", ApiJson.formatInstant(item.getCreatedAt()));
            Listing listing = listings.get(item.getListingId());
            if (listing != null) {
                m.put("listing", ApiJson.listingSummaryJson(listing));
            }
            result.add(m);
        }
        return result;
    }

    private Map<String, Object> localPmJson(LocalPaymentMethod pm) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", pm.getId());
        m.put("type", pm.getType());
        m.put("type_label", ShopConfig.localPaymentTypeLabel(pm.getType()));
        m.put("identifier", pm.getIdentifier());
        m.put("is_default", Boolean.TRUE.equals(pm.getIsDefault()));
        return m;
    }
}
