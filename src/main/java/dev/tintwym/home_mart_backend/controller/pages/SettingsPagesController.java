package dev.tintwym.home_mart_backend.controller.pages;

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
import dev.tintwym.home_mart_backend.repository.PasskeyRepository;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import dev.tintwym.home_mart_backend.mapper.ApiJson;
import dev.tintwym.home_mart_backend.service.AuthCookieService;
import dev.tintwym.home_mart_backend.service.GeoRegionService;
import dev.tintwym.home_mart_backend.service.InertiaService;
import dev.tintwym.home_mart_backend.service.ListingSoldService;
import dev.tintwym.home_mart_backend.service.OrderStatuses;
import dev.tintwym.home_mart_backend.service.PasskeyService;
import dev.tintwym.home_mart_backend.service.ShopConfig;
import dev.tintwym.home_mart_backend.service.StripeService;
import dev.tintwym.home_mart_backend.utility.UlidService;
import com.stripe.model.Customer;
import com.stripe.model.PaymentMethod;
import com.stripe.model.SetupIntent;
import com.stripe.param.CustomerUpdateParams;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SettingsPagesController extends PageControllerSupport {

    private final PasswordEncoder passwordEncoder;
    private final AuthCookieService authCookieService;
    private final PasskeyRepository passkeyRepository;
    private final PasskeyService passkeyService;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ListingRepository listingRepository;
    private final LocalPaymentMethodRepository localPaymentMethodRepository;
    private final CartItemRepository cartItemRepository;
    private final FavoriteRepository favoriteRepository;
    private final StripeService stripeService;
    private final GeoRegionService geoRegionService;
    private final ListingSoldService listingSoldService;

    public SettingsPagesController(
            InertiaService inertia,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuthCookieService authCookieService,
            PasskeyRepository passkeyRepository,
            PasskeyService passkeyService,
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            ListingRepository listingRepository,
            LocalPaymentMethodRepository localPaymentMethodRepository,
            CartItemRepository cartItemRepository,
            FavoriteRepository favoriteRepository,
            StripeService stripeService,
            GeoRegionService geoRegionService,
            ListingSoldService listingSoldService) {
        super(inertia, userRepository);
        this.passwordEncoder = passwordEncoder;
        this.authCookieService = authCookieService;
        this.passkeyRepository = passkeyRepository;
        this.passkeyService = passkeyService;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.listingRepository = listingRepository;
        this.localPaymentMethodRepository = localPaymentMethodRepository;
        this.cartItemRepository = cartItemRepository;
        this.favoriteRepository = favoriteRepository;
        this.stripeService = stripeService;
        this.geoRegionService = geoRegionService;
        this.listingSoldService = listingSoldService;
    }

    @GetMapping("/settings")
    public ResponseEntity<?> settingsMenu(HttpServletRequest request, HttpServletResponse response) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        return render(request, response, "settings/menu", Map.of());
    }

    @GetMapping("/settings/profile")
    @Transactional(readOnly = true)
    public ResponseEntity<?> profileEdit(HttpServletRequest request, HttpServletResponse response) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        User user = requireUser();
        List<Map<String, Object>> passkeys = passkeyRepository.findByAuthenticatableId(user.getId()).stream()
                .map(passkeyService::passkeyJson)
                .toList();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("mustVerifyEmail", true);
        props.put("status", null);
        props.put("passkeys", passkeys);
        return render(request, response, "settings/profile", props);
    }

    @PatchMapping("/settings/profile")
    @PostMapping("/settings/profile")
    @Transactional
    public ResponseEntity<?> profileUpdate(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String address,
            @RequestParam(value = "seller_type", required = false) String sellerType,
            @RequestParam(required = false) String region,
            @RequestParam(value = "_method", required = false) String method) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        User user = requireUser();
        if (name != null && !name.isBlank()) {
            user.setName(name.trim());
        }
        if (email != null && !email.isBlank()) {
            String normalized = email.trim().toLowerCase();
            if (!normalized.equals(user.getEmail()) && userRepository.existsByEmail(normalized)) {
                Map<String, Object> props = new LinkedHashMap<>();
                props.put("mustVerifyEmail", true);
                props.put("errors", errors("email", "Email has already been taken."));
                props.put("passkeys", passkeyRepository.findByAuthenticatableId(user.getId()).stream()
                        .map(passkeyService::passkeyJson).toList());
                return render(request, response, "settings/profile", props);
            }
            if (!normalized.equals(user.getEmail())) {
                user.setEmail(normalized);
                user.setEmailVerifiedAt(null);
            }
        }
        if (phone != null) {
            user.setPhone(phone);
        }
        if (address != null) {
            user.setAddress(address);
        }
        if (sellerType != null && ("individual".equals(sellerType) || "business".equals(sellerType))) {
            user.setSellerType(sellerType);
        }
        if (region != null && ShopConfig.REGIONS.contains(region.trim().toUpperCase())) {
            user.setRegion(region.trim().toUpperCase());
        }
        userRepository.save(user);
        return redirect(request, "/settings/profile");
    }

    @DeleteMapping("/settings/profile")
    @Transactional
    public ResponseEntity<?> profileDestroy(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam(value = "password", required = false) String password) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        User user = requireUser();
        if (password == null || !passwordEncoder.matches(password, user.getPassword())) {
            return backWithError(request, response, "The provided password was incorrect.");
        }
        String userId = user.getId();
        cartItemRepository.deleteByUserId(userId);
        favoriteRepository.deleteByUserId(userId);
        localPaymentMethodRepository.deleteByUserId(userId);
        for (var listing : listingRepository.findByUserId(userId)) {
            listingRepository.delete(listing);
        }
        authCookieService.clearTokenCookie(response);
        userRepository.delete(user);
        return redirect(request, "/");
    }

    @GetMapping("/settings/password")
    public ResponseEntity<?> passwordEdit(HttpServletRequest request, HttpServletResponse response) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        return render(request, response, "settings/password", Map.of());
    }

    @PutMapping("/settings/password")
    @PostMapping(value = "/settings/password", params = "_method=put")
    @Transactional
    public ResponseEntity<?> passwordUpdate(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam("current_password") String currentPassword,
            @RequestParam String password,
            @RequestParam(value = "password_confirmation", required = false) String passwordConfirmation) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        User user = requireUser();
        Map<String, Object> fieldErrors = new LinkedHashMap<>();
        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            fieldErrors.put("current_password", "The provided password was incorrect.");
        }
        if (password == null || password.length() < 8) {
            fieldErrors.put("password", "Password must be at least 8 characters.");
        } else if (passwordConfirmation != null && !password.equals(passwordConfirmation)) {
            fieldErrors.put("password", "Password confirmation does not match.");
        }
        if (!fieldErrors.isEmpty()) {
            return render(request, response, "settings/password", Map.of("errors", fieldErrors));
        }
        user.setPassword(passwordEncoder.encode(password));
        userRepository.save(user);
        return inertia.back(request, response, "/settings/password");
    }

    @GetMapping("/settings/orders")
    @Transactional(readOnly = true)
    public ResponseEntity<?> orders(HttpServletRequest request, HttpServletResponse response) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        User user = requireUser();
        List<OrderEntity> orders = orderRepository.findByUserIdAndStatusIn(
                user.getId(), OrderStatuses.BUYER_VISIBLE);
        List<Map<String, Object>> orderMaps = new ArrayList<>();
        for (OrderEntity order : orders) {
            Map<String, Object> om = new LinkedHashMap<>();
            om.put("id", order.getId());
            om.put("status", order.getStatus());
            om.put("total", order.getTotal());
            om.put("payment_gateway", order.getPaymentGateway());
            om.put("created_at", ApiJson.formatInstant(order.getCreatedAt()));
            List<Map<String, Object>> items = new ArrayList<>();
            for (OrderItem oi : orderItemRepository.findByOrderId(order.getId())) {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("id", oi.getId());
                im.put("listing_id", oi.getListingId());
                im.put("quantity", oi.getQuantity());
                im.put("price", oi.getPrice());
                listingRepository.findDetailedById(oi.getListingId())
                        .ifPresent(l -> im.put("listing", listingSoldService.toSummaryJson(l)));
                items.add(im);
            }
            om.put("items", items);
            orderMaps.add(om);
        }
        return render(request, response, "settings/orders", Map.of("orders", orderMaps));
    }

    @GetMapping("/settings/two-factor")
    public ResponseEntity<?> twoFactorSettings(HttpServletRequest request, HttpServletResponse response) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        User user = requireUser();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("twoFactorEnabled", user.hasConfirmedTwoFactor());
        props.put("requiresConfirmation", true);
        return render(request, response, "settings/two-factor", props);
    }

    @GetMapping("/settings/payment")
    @Transactional(readOnly = true)
    public ResponseEntity<?> paymentIndex(HttpServletRequest request, HttpServletResponse response) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        User user = requireUser();
        String region = geoRegionService.detect(request);
        boolean isC2cRegion = "MM".equals(region) || "VN".equals(region);
        List<Map<String, Object>> paymentMethods = new ArrayList<>();
        List<Map<String, Object>> localPaymentMethods = new ArrayList<>();
        String stripeKey = null;

        // MM/VN checkout is peer-to-peer (pay seller in chat) — no platform wallet / Stripe vault.
        if (!isC2cRegion && stripeService.isConfigured()) {
            stripeKey = stripeService.getPublishableKey();
            try {
                Customer customer = stripeService.ensureCustomer(user);
                userRepository.save(user);
                String defaultPmId = customer.getInvoiceSettings() == null
                        ? null
                        : customer.getInvoiceSettings().getDefaultPaymentMethod();
                List<PaymentMethod> pms = stripeService.listPaymentMethods(user);
                if ((defaultPmId == null || defaultPmId.isBlank()) && !pms.isEmpty()) {
                    defaultPmId = pms.get(0).getId();
                }
                for (PaymentMethod pm : pms) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", pm.getId());
                    m.put("brand", pm.getCard() == null ? "card" : pm.getCard().getBrand());
                    m.put("last4", pm.getCard() == null ? "****" : pm.getCard().getLast4());
                    m.put("is_default", pm.getId().equals(defaultPmId));
                    paymentMethods.add(m);
                }
            } catch (Exception ignored) {
                // Stripe unavailable
            }
        }

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("region", region);
        props.put("c2cCheckout", isC2cRegion);
        props.put("testMode", true);
        props.put("paymentMethods", paymentMethods);
        props.put("localPaymentMethods", localPaymentMethods);
        props.put("stripePublishableKey", stripeKey);
        return render(request, response, "settings/payment", props);
    }

    @PostMapping("/settings/payment")
    @Transactional
    public ResponseEntity<?> paymentStore(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam String type,
            @RequestParam String identifier,
            @RequestParam(value = "is_default", required = false) Boolean isDefault) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        if (!"MM".equals(geoRegionService.detect(request))) {
            return redirectWithError(request, response, "/settings/payment", "Invalid request.");
        }
        if (!ShopConfig.MYANMAR_PAYMENT_METHODS.contains(type)) {
            return redirectWithError(request, response, "/settings/payment", "Invalid payment type.");
        }
        User user = requireUser();
        boolean makeDefault = Boolean.TRUE.equals(isDefault)
                || localPaymentMethodRepository.findByUserId(user.getId()).isEmpty();
        if (makeDefault) {
            for (LocalPaymentMethod pm : localPaymentMethodRepository.findByUserId(user.getId())) {
                pm.setIsDefault(false);
                localPaymentMethodRepository.save(pm);
            }
        }
        LocalPaymentMethod pm = new LocalPaymentMethod();
        pm.setId(UlidService.newUlid());
        pm.setUserId(user.getId());
        pm.setType(type);
        pm.setIdentifier(identifier);
        pm.setIsDefault(makeDefault);
        localPaymentMethodRepository.save(pm);
        return redirectWithStatus(request, response, "/settings/payment", "Payment method saved.");
    }

    @PostMapping("/settings/payment/setup-intent")
    public ResponseEntity<?> createSetupIntent() {
        User user = requireUser();
        try {
            SetupIntent intent = stripeService.createSetupIntent(user);
            userRepository.save(user);
            return ResponseEntity.ok(Map.of("clientSecret", intent.getClientSecret()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", e.getMessage() == null ? "Stripe unavailable" : e.getMessage()));
        }
    }

    @PostMapping("/settings/payment/default")
    public ResponseEntity<?> setDefaultStripe(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam("payment_method_id") String paymentMethodId) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        User user = requireUser();
        try {
            Customer customer = stripeService.ensureCustomer(user);
            CustomerUpdateParams params = CustomerUpdateParams.builder()
                    .setInvoiceSettings(CustomerUpdateParams.InvoiceSettings.builder()
                            .setDefaultPaymentMethod(paymentMethodId)
                            .build())
                    .build();
            customer.update(params);
            userRepository.save(user);
            return redirectWithStatus(request, response, "/settings/payment", "Default payment method updated.");
        } catch (Exception e) {
            return redirectWithError(request, response, "/settings/payment", "Could not update default payment method.");
        }
    }

    @DeleteMapping("/settings/payment/{paymentMethodId}")
    public ResponseEntity<?> destroyStripePm(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String paymentMethodId) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        try {
            User user = requireUser();
            PaymentMethod pm = PaymentMethod.retrieve(paymentMethodId);
            if (user.getStripeCustomerId() == null
                    || pm.getCustomer() == null
                    || !user.getStripeCustomerId().equals(pm.getCustomer())) {
                return redirectWithError(request, response, "/settings/payment",
                        "Payment method not found.");
            }
            pm.detach();
            return redirectWithStatus(request, response, "/settings/payment", "Payment method removed.");
        } catch (Exception e) {
            return redirectWithError(request, response, "/settings/payment", "Could not remove payment method.");
        }
    }

    @PostMapping("/settings/payment/local/default")
    @Transactional
    public ResponseEntity<?> setDefaultLocal(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam("local_payment_method_id") String localPaymentMethodId) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        User user = requireUser();
        LocalPaymentMethod target = localPaymentMethodRepository.findById(localPaymentMethodId)
                .filter(pm -> user.getId().equals(pm.getUserId()))
                .orElse(null);
        if (target == null) {
            return redirectWithError(request, response, "/settings/payment", "Invalid payment method.");
        }
        for (LocalPaymentMethod pm : localPaymentMethodRepository.findByUserId(user.getId())) {
            pm.setIsDefault(pm.getId().equals(target.getId()));
            localPaymentMethodRepository.save(pm);
        }
        return redirectWithStatus(request, response, "/settings/payment", "Default payment method updated.");
    }

    @DeleteMapping("/settings/payment/local/{localPaymentMethodId}")
    @Transactional
    public ResponseEntity<?> destroyLocal(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String localPaymentMethodId) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        User user = requireUser();
        localPaymentMethodRepository.findById(localPaymentMethodId)
                .filter(pm -> user.getId().equals(pm.getUserId()))
                .ifPresent(localPaymentMethodRepository::delete);
        return redirectWithStatus(request, response, "/settings/payment", "Payment method removed.");
    }

    @GetMapping("/upgrades")
    @Transactional(readOnly = true)
    public ResponseEntity<?> upgrades(HttpServletRequest request, HttpServletResponse response) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        User user = requireUser();
        long listingCount = listingRepository.countByUserId(user.getId());
        int maxSlots = ShopConfig.maxListingSlots(user.getSellerType(), user.getExtraListingSlots());
        ShopConfig.Currency currency = currencyFor(request);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("listingCount", listingCount);
        props.put("maxListingSlots", maxSlots);
        props.put("slotPrice", ShopConfig.SLOT_PRICE);
        props.put("slotPriceLabel", ShopConfig.formatUsdFee(ShopConfig.SLOT_PRICE, currency, "per slot"));
        props.put("trendPrice", ShopConfig.TREND_PRICE);
        props.put("trendPriceLabel", ShopConfig.formatUsdFee(
                ShopConfig.TREND_PRICE, currency, "for " + ShopConfig.TREND_DURATION_DAYS + " days"));
        props.put("trendDurationDays", ShopConfig.TREND_DURATION_DAYS);
        return render(request, response, "upgrades/index", props);
    }

    @PostMapping("/upgrades/slots")
    @Transactional
    public ResponseEntity<?> purchaseSlots(HttpServletRequest request, HttpServletResponse response) {
        ResponseEntity<?> gate = requireLogin(request, response);
        if (gate != null) {
            return gate;
        }
        return redirectWithError(request, response, "/upgrades",
                "Listing slot purchases require payment configuration.");
    }
}
