package dev.tintwym.home_mart_backend.service;

import dev.tintwym.home_mart_backend.config.AppProperties;
import dev.tintwym.home_mart_backend.config.StripeProperties;
import dev.tintwym.home_mart_backend.entity.User;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.PaymentMethod;
import com.stripe.model.PaymentMethodCollection;
import com.stripe.model.SetupIntent;
import com.stripe.model.checkout.Session;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentMethodListParams;
import com.stripe.param.SetupIntentCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class StripeService {

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
        }
    }

    public boolean isConfigured() {
        return stripeProperties.isConfigured();
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
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(currencyCode.toLowerCase())
                                .setUnitAmount(unitAmount)
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName("Home Mart Order")
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
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(currencyCode.toLowerCase())
                                .setUnitAmount(unitAmount)
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName("Promote listing (" + promoteType + ")")
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

    public Map<String, Object> publicConfig() {
        Map<String, Object> map = new HashMap<>();
        map.put("key", stripeProperties.getKey());
        map.put("configured", isConfigured());
        map.put("app_url", appProperties.getUrl());
        return map;
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException("Stripe is not configured. Set stripe.secret / STRIPE_SECRET.");
        }
        Stripe.apiKey = stripeProperties.getSecret();
    }
}
