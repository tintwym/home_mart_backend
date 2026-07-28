package dev.tintwym.home_mart_backend.controller.api;

import dev.tintwym.home_mart_backend.config.AppProperties;
import dev.tintwym.home_mart_backend.entity.Listing;
import dev.tintwym.home_mart_backend.entity.OrderEntity;
import dev.tintwym.home_mart_backend.entity.OrderItem;
import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.repository.CartItemRepository;
import dev.tintwym.home_mart_backend.repository.ListingRepository;
import dev.tintwym.home_mart_backend.repository.OrderItemRepository;
import dev.tintwym.home_mart_backend.repository.OrderRepository;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import dev.tintwym.home_mart_backend.service.ShopConfig;
import dev.tintwym.home_mart_backend.service.StripeService;
import dev.tintwym.home_mart_backend.utility.ApiResponses;
import dev.tintwym.home_mart_backend.utility.AuthSupport;
import dev.tintwym.home_mart_backend.dto.ApiRequests.StripeDefaultPaymentRequest;
import dev.tintwym.home_mart_backend.dto.ApiRequests.StripeOrderRequest;
import dev.tintwym.home_mart_backend.dto.ApiRequests.StripeSessionRequest;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentMethod;
import com.stripe.model.SetupIntent;
import com.stripe.model.checkout.Session;
import com.stripe.param.CustomerUpdateParams;
import com.stripe.param.PaymentMethodAttachParams;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class StripeController {

    private final StripeService stripeService;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CartItemRepository cartItemRepository;
    private final ListingRepository listingRepository;
    private final AppProperties appProperties;

    public StripeController(
            StripeService stripeService,
            UserRepository userRepository,
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            CartItemRepository cartItemRepository,
            ListingRepository listingRepository,
            AppProperties appProperties) {
        this.stripeService = stripeService;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.cartItemRepository = cartItemRepository;
        this.listingRepository = listingRepository;
        this.appProperties = appProperties;
    }

    @PostMapping("/checkout/stripe")
    @Transactional
    public ResponseEntity<?> checkoutStripe(@RequestBody StripeOrderRequest request) {
        User user = requireUser();
        OrderEntity order = orderRepository.findById(request.orderId()).orElse(null);
        if (order == null || !user.getId().equals(order.getUserId()) || !"pending".equals(order.getStatus())) {
            return ApiResponses.notFound("Order not found.");
        }
        for (OrderItem item : orderItemRepository.findByOrderId(order.getId())) {
            if (orderItemRepository.existsByListingIdAndOrder_StatusIn(
                    item.getListingId(), List.of("paid", "completed"))) {
                return ApiResponses.unprocessable("One or more listings have already been sold.");
            }
        }
        if (!stripeService.isConfigured()) {
            return ApiResponses.unprocessable("Stripe is not configured.");
        }
        try {
            String currency = ShopConfig.DEFAULT_CURRENCY.code();
            String success = frontendUrl() + "/checkout/success?session_id={CHECKOUT_SESSION_ID}";
            String cancel = frontendUrl() + "/cart";
            Session session = stripeService.createOrderCheckoutSession(
                    user, order.getId(), order.getTotal(), currency, success, cancel);
            userRepository.save(user);
            order.setStripeSessionId(session.getId());
            order.setPaymentGateway("stripe");
            orderRepository.save(order);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("url", session.getUrl());
            body.put("session_id", session.getId());
            body.put("publishable_key", stripeService.getPublishableKey());
            return ResponseEntity.ok(body);
        } catch (StripeException | IllegalStateException e) {
            return ApiResponses.unprocessable("Unable to create Stripe checkout: " + e.getMessage());
        }
    }

    @PostMapping("/checkout/success")
    @Transactional
    public ResponseEntity<?> checkoutSuccess(@RequestBody StripeSessionRequest request) {
        User user = requireUser();
        if (!stripeService.isConfigured()) {
            return ApiResponses.unprocessable("Stripe is not configured.");
        }
        try {
            Session session = stripeService.retrieveSession(request.sessionId());
            if (!"paid".equalsIgnoreCase(session.getPaymentStatus())
                    && !"complete".equalsIgnoreCase(session.getStatus())) {
                return ApiResponses.unprocessable("Payment is not completed.");
            }
            String orderId = session.getMetadata() == null ? null : session.getMetadata().get("order_id");
            OrderEntity order = orderId == null
                    ? orderRepository.findByStripeSessionId(session.getId()).orElse(null)
                    : orderRepository.findById(orderId).orElse(null);
            if (order == null || !user.getId().equals(order.getUserId())) {
                return ApiResponses.notFound("Order not found.");
            }
            if (!"paid".equals(order.getStatus()) && !"completed".equals(order.getStatus())) {
                order.setStatus("paid");
                order.setStripeSessionId(session.getId());
                order.setPaymentGateway("stripe");
                orderRepository.save(order);
                cartItemRepository.deleteByUserId(user.getId());
            }
            return ResponseEntity.ok(Map.of("message", "Payment successful.", "order_id", order.getId()));
        } catch (StripeException e) {
            return ApiResponses.unprocessable("Unable to verify Stripe session.");
        }
    }

    @PostMapping("/listings/{id}/promote/checkout")
    @Transactional
    public ResponseEntity<?> promoteCheckout(@PathVariable String id) {
        User user = requireUser();
        Listing listing = listingRepository.findById(id).orElse(null);
        if (listing == null) {
            return ApiResponses.notFound("Listing not found.");
        }
        if (!user.getId().equals(listing.getUserId())) {
            return ResponseEntity.status(403).body(Map.of("message", "Forbidden."));
        }

        if (!stripeService.isConfigured()) {
            return ApiResponses.unprocessable("Stripe is not configured.");
        }

        try {
            String currency = ShopConfig.DEFAULT_CURRENCY.code();
            String success = frontendUrl() + "/checkout/promote/success?session_id={CHECKOUT_SESSION_ID}";
            String cancel = frontendUrl() + "/listings/" + id;
            BigDecimal amount = ShopConfig.convertFromUsd(ShopConfig.TREND_PRICE, currency);
            Session session = stripeService.createPromoteCheckoutSession(
                    user, listing.getId(), "trend", amount, currency, success, cancel);
            userRepository.save(user);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("configured", true);
            body.put("url", session.getUrl());
            body.put("session_id", session.getId());
            body.put("publishable_key", stripeService.getPublishableKey());
            return ResponseEntity.ok(body);
        } catch (StripeException | IllegalStateException e) {
            return ApiResponses.unprocessable("Unable to create promote checkout: " + e.getMessage());
        }
    }

    @PostMapping("/checkout/promote/success")
    @Transactional
    public ResponseEntity<?> promoteSuccess(@RequestBody StripeSessionRequest request) {
        User user = requireUser();
        if (!stripeService.isConfigured()) {
            return ApiResponses.unprocessable("Stripe is not configured.");
        }
        try {
            Session session = stripeService.retrieveSession(request.sessionId());
            if (!"paid".equalsIgnoreCase(session.getPaymentStatus())
                    && !"complete".equalsIgnoreCase(session.getStatus())) {
                return ApiResponses.unprocessable("Payment is not completed.");
            }
            Map<String, String> metadata = session.getMetadata();
            if (metadata == null || !"promote".equals(metadata.get("type"))) {
                return ApiResponses.unprocessable("Invalid promote session.");
            }
            String listingId = metadata.get("listing_id");
            Listing listing = listingRepository.findById(listingId).orElse(null);
            if (listing == null || !user.getId().equals(listing.getUserId())) {
                return ApiResponses.notFound("Listing not found.");
            }
            listing.setTrendingUntil(Instant.now().plus(ShopConfig.TREND_DURATION_DAYS, ChronoUnit.DAYS));
            listingRepository.save(listing);
            return ResponseEntity.ok(Map.of(
                    "message",
                    "Listing promoted.",
                    "trending_until",
                    listing.getTrendingUntil().toString()));
        } catch (StripeException e) {
            return ApiResponses.unprocessable("Unable to verify promote session.");
        }
    }

    @PostMapping("/payment/setup-intent")
    @Transactional
    public ResponseEntity<?> setupIntent() {
        User user = requireUser();
        if (!stripeService.isConfigured()) {
            return ApiResponses.serviceUnavailable("Stripe is not configured.");
        }
        try {
            SetupIntent intent = stripeService.createSetupIntent(user);
            userRepository.save(user);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("client_secret", intent.getClientSecret());
            body.put("publishable_key", stripeService.getPublishableKey());
            return ResponseEntity.ok(body);
        } catch (StripeException | IllegalStateException e) {
            return ApiResponses.serviceUnavailable("Unable to create setup intent.");
        }
    }

    @GetMapping("/payment/methods")
    @Transactional
    public ResponseEntity<?> paymentMethods() {
        User user = requireUser();
        if (!stripeService.isConfigured() || user.getStripeCustomerId() == null) {
            return ResponseEntity.ok(Map.of("data", List.of()));
        }
        try {
            List<PaymentMethod> methods = stripeService.listPaymentMethods(user);
            List<Map<String, Object>> data = new ArrayList<>();
            boolean first = true;
            for (PaymentMethod pm : methods) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", pm.getId());
                row.put("brand", pm.getCard() == null ? null : pm.getCard().getBrand());
                row.put("last4", pm.getCard() == null ? null : pm.getCard().getLast4());
                row.put("exp_month", pm.getCard() == null ? null : pm.getCard().getExpMonth());
                row.put("exp_year", pm.getCard() == null ? null : pm.getCard().getExpYear());
                row.put("is_default", first);
                first = false;
                data.add(row);
            }
            return ResponseEntity.ok(Map.of("data", data));
        } catch (StripeException e) {
            return ApiResponses.serviceUnavailable("Unable to list payment methods.");
        }
    }

    @PostMapping("/payment/default")
    @Transactional
    public ResponseEntity<?> setDefault(@RequestBody StripeDefaultPaymentRequest request) {
        User user = requireUser();
        if (!stripeService.isConfigured() || user.getStripeCustomerId() == null) {
            return ApiResponses.serviceUnavailable("Stripe is not configured.");
        }
        String pmId = request.paymentMethodId();
        if (pmId == null || !pmId.startsWith("pm_")) {
            return ApiResponses.unprocessable("Invalid payment_method_id.");
        }
        try {
            PaymentMethod pm = PaymentMethod.retrieve(pmId);
            if (pm.getCustomer() == null) {
                pm.attach(PaymentMethodAttachParams.builder()
                        .setCustomer(user.getStripeCustomerId())
                        .build());
            } else if (!user.getStripeCustomerId().equals(pm.getCustomer())) {
                return ResponseEntity.status(403).body(Map.of("message", "Forbidden."));
            }
            com.stripe.model.Customer.retrieve(user.getStripeCustomerId())
                    .update(CustomerUpdateParams.builder()
                            .setInvoiceSettings(CustomerUpdateParams.InvoiceSettings.builder()
                                    .setDefaultPaymentMethod(pmId)
                                    .build())
                            .build());
            return ResponseEntity.ok(Map.of("message", "Default payment method updated."));
        } catch (StripeException e) {
            return ApiResponses.serviceUnavailable("Unable to update default payment method.");
        }
    }

    @DeleteMapping("/payment/{paymentMethodId}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> deletePaymentMethod(@PathVariable String paymentMethodId) {
        User user = requireUser();
        if (!stripeService.isConfigured()) {
            return ApiResponses.serviceUnavailable("Stripe is not configured.");
        }
        try {
            PaymentMethod pm = PaymentMethod.retrieve(paymentMethodId);
            if (pm.getCustomer() == null || !pm.getCustomer().equals(user.getStripeCustomerId())) {
                return ResponseEntity.status(403).body(Map.of("message", "Forbidden."));
            }
            pm.detach();
            return ResponseEntity.ok(Map.of("message", "Payment method removed."));
        } catch (StripeException e) {
            return ApiResponses.unprocessable("Unable to remove payment method.");
        }
    }

    private User requireUser() {
        return userRepository
                .findById(AuthSupport.currentUserId())
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthenticated."));
    }

    private String frontendUrl() {
        String url = appProperties.getFrontendUrl();
        if (url == null || url.isBlank()) {
            url = appProperties.getUrl();
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
