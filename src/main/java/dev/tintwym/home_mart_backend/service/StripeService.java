package dev.tintwym.home_mart_backend.service;

import dev.tintwym.home_mart_backend.config.AppProperties;
import dev.tintwym.home_mart_backend.config.StripeProperties;
import dev.tintwym.home_mart_backend.entity.User;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.PaymentMethod;
import com.stripe.model.PaymentMethodCollection;
import com.stripe.model.Refund;
import com.stripe.model.SetupIntent;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentMethodListParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.SetupIntentCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class StripeService {

    private static final Logger log = LoggerFactory.getLogger(StripeService.class);

    private final StripeProperties stripeProperties;
    private final AppProperties appProperties;

    public StripeService(StripeProperties stripeProperties, AppProperties appProperties) {
        this.stripeProperties = stripeProperties;
        this.appProperties = appProperties;
    }

    @PostConstruct
    public void init() {
        if (stripeProperties.isConfigured()) {
            Stripe.apiKey = stripeProperties.getSecret();
            log.info("Stripe configured in TEST mode (live keys refused).");
        } else if (stripeProperties.getSecret() != null && !stripeProperties.getSecret().isBlank()) {
            log.warn("Stripe secret set but rejected — only sk_test_/pk_test_ keys are allowed (test purchases).");
        }
    }

    public boolean isConfigured() {
        return stripeProperties.isConfigured();
    }

    public boolean isTestMode() {
        return stripeProperties.isTestModeOnly() && isConfigured();
    }

    public String getPublishableKey() {
        return stripeProperties.getKey();
    }

    public Customer ensureCustomer(User user) throws StripeException {
        requireConfigured();
        if (user.getStripeCustomerId() != null && !user.getStripeCustomerId().isBlank()) {
            return Customer.retrieve(user.getStripeCustomerId());
        }
        CustomerCreateParams params = CustomerCreateParams.builder()
                .setEmail(user.getEmail())
                .setName(user.getName())
                .putMetadata("user_id", user.getId())
                .build();
        Customer customer = Customer.create(params);
        user.setStripeCustomerId(customer.getId());
        return customer;
    }

    public Session createOrderCheckoutSession(
            User user,
            String orderId,
            BigDecimal amount,
            String currencyCode,
            String successUrl,
            String cancelUrl) throws StripeException {
        requireConfigured();
        Customer customer = ensureCustomer(user);
        long unitAmount = ShopConfig.toStripeAmount(amount, currencyCode);

        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setCustomer(customer.getId())
                .setSuccessUrl(successUrl)
                .setCancelUrl(cancelUrl)
                .putMetadata("order_id", orderId)
                .putMetadata("user_id", user.getId())
                .putMetadata("type", "order")
                .putMetadata("test_mode", "true")
                .putMetadata("purchase_mode", "test")
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(currencyCode.toLowerCase())
                                .setUnitAmount(unitAmount)
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName("Home Mart Order (TEST)")
                                        .setDescription("Test purchase — Stripe test mode only")
                                        .build())
                                .build())
                        .build())
                .build();
        return Session.create(params);
    }

    public Session createPromoteCheckoutSession(
            User user,
            String listingId,
            String promoteType,
            BigDecimal usdAmount,
            String currencyCode,
            String successUrl,
            String cancelUrl) throws StripeException {
        requireConfigured();
        Customer customer = ensureCustomer(user);
        long unitAmount = ShopConfig.toStripeAmount(usdAmount, currencyCode);

        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setCustomer(customer.getId())
                .setSuccessUrl(successUrl)
                .setCancelUrl(cancelUrl)
                .putMetadata("listing_id", listingId)
                .putMetadata("user_id", user.getId())
                .putMetadata("type", "promote")
                .putMetadata("promote_type", promoteType)
                .putMetadata("test_mode", "true")
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(currencyCode.toLowerCase())
                                .setUnitAmount(unitAmount)
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName("Promote listing (" + promoteType + ") — TEST")
                                        .build())
                                .build())
                        .build())
                .build();
        return Session.create(params);
    }

    public SetupIntent createSetupIntent(User user) throws StripeException {
        requireConfigured();
        Customer customer = ensureCustomer(user);
        SetupIntentCreateParams params = SetupIntentCreateParams.builder()
                .setCustomer(customer.getId())
                .addPaymentMethodType("card")
                .build();
        return SetupIntent.create(params);
    }

    public List<PaymentMethod> listPaymentMethods(User user) throws StripeException {
        requireConfigured();
        if (user.getStripeCustomerId() == null || user.getStripeCustomerId().isBlank()) {
            return List.of();
        }
        PaymentMethodListParams params = PaymentMethodListParams.builder()
                .setCustomer(user.getStripeCustomerId())
                .setType(PaymentMethodListParams.Type.CARD)
                .build();
        PaymentMethodCollection collection = PaymentMethod.list(params);
        return collection.getData();
    }

    public Session retrieveSession(String sessionId) throws StripeException {
        requireConfigured();
        return Session.retrieve(sessionId);
    }

    public Event constructWebhookEvent(String payload, String sigHeader)
            throws SignatureVerificationException {
        if (!stripeProperties.hasWebhookSecret()) {
            throw new IllegalStateException("Stripe webhook secret is not configured.");
        }
        return Webhook.constructEvent(payload, sigHeader, stripeProperties.getWebhookSecret());
    }

    /** Best-effort refund for sold-conflict after a test Checkout payment. */
    public boolean refundCheckoutSession(Session session) {
        if (session == null) {
            return false;
        }
        try {
            requireConfigured();
            String paymentIntentId = session.getPaymentIntent();
            if (paymentIntentId == null || paymentIntentId.isBlank()) {
                // Expand once if needed
                Session full = Session.retrieve(session.getId());
                paymentIntentId = full.getPaymentIntent();
            }
            if (paymentIntentId == null || paymentIntentId.isBlank()) {
                return false;
            }
            Refund.create(RefundCreateParams.builder()
                    .setPaymentIntent(paymentIntentId)
                    .setReason(RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER)
                    .putMetadata("reason", "listing_already_sold_conflict")
                    .putMetadata("test_mode", "true")
                    .build());
            return true;
        } catch (Exception e) {
            log.warn("Stripe refund failed for session {}: {}", session.getId(), e.getMessage());
            return false;
        }
    }

    public Map<String, Object> publicConfig() {
        Map<String, Object> map = new HashMap<>();
        map.put("key", stripeProperties.getKey());
        map.put("configured", isConfigured());
        map.put("test_mode", isTestMode());
        map.put("app_url", appProperties.getUrl());
        return map;
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "Stripe is not configured for TEST purchases. Set STRIPE_KEY=pk_test_… and STRIPE_SECRET=sk_test_…");
        }
        Stripe.apiKey = stripeProperties.getSecret();
    }
}
