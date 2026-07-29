package dev.tintwym.home_mart_backend.controller.api;

import dev.tintwym.home_mart_backend.entity.CartItem;
import dev.tintwym.home_mart_backend.entity.Category;
import dev.tintwym.home_mart_backend.entity.Favorite;
import dev.tintwym.home_mart_backend.entity.Subcategory;
import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.repository.CartItemRepository;
import dev.tintwym.home_mart_backend.repository.CategoryRepository;
import dev.tintwym.home_mart_backend.repository.FavoriteRepository;
import dev.tintwym.home_mart_backend.repository.MessageRepository;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import dev.tintwym.home_mart_backend.mapper.ApiJson;
import dev.tintwym.home_mart_backend.service.GeoRegionService;
import dev.tintwym.home_mart_backend.service.ShopConfig;
import dev.tintwym.home_mart_backend.service.TranslationService;
import dev.tintwym.home_mart_backend.utility.AuthSupport;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class BootstrapController {

    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final CartItemRepository cartItemRepository;
    private final FavoriteRepository favoriteRepository;
    private final MessageRepository messageRepository;
    private final GeoRegionService geoRegionService;
    private final TranslationService translationService;

    public BootstrapController(
            UserRepository userRepository,
            CategoryRepository categoryRepository,
            CartItemRepository cartItemRepository,
            FavoriteRepository favoriteRepository,
            MessageRepository messageRepository,
            GeoRegionService geoRegionService,
            TranslationService translationService) {
        this.userRepository = userRepository;
        this.categoryRepository = categoryRepository;
        this.cartItemRepository = cartItemRepository;
        this.favoriteRepository = favoriteRepository;
        this.messageRepository = messageRepository;
        this.geoRegionService = geoRegionService;
        this.translationService = translationService;
    }

    @GetMapping("/bootstrap")
    @Transactional(readOnly = true)
    public ResponseEntity<?> bootstrap(
            HttpServletRequest request,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String locale) {
        String userId = AuthSupport.currentUserIdOrNull();
        User user = userId == null ? null : userRepository.findById(userId).orElse(null);

        String resolvedRegion = resolveRegion(request, region, user);
        String resolvedCurrency = resolveCurrency(currency, resolvedRegion);
        String resolvedLocale = resolveLocale(locale, resolvedRegion);

        Map<String, Object> auth = new LinkedHashMap<>();
        if (user != null) {
            auth.put("user", ApiJson.apiUserJson(user));
            auth.put("cart_listing_ids", cartItemRepository.findByUserId(user.getId()).stream()
                    .map(CartItem::getListingId)
                    .toList());
            auth.put("favorite_listing_ids", favoriteRepository.findByUserId(user.getId()).stream()
                    .map(Favorite::getListingId)
                    .toList());
            auth.put("unread_messages", messageRepository.countUnreadForUser(user.getId()));
        } else {
            auth.put("user", null);
            auth.put("cart_listing_ids", List.of());
            auth.put("favorite_listing_ids", List.of());
            auth.put("unread_messages", 0);
        }

        List<Category> categories = categoryRepository.findAllWithSubcategoriesOrderByNameAsc();
        List<Map<String, Object>> categoryData = new ArrayList<>();
        List<Map<String, Object>> categoryTree = new ArrayList<>();
        for (Category cat : categories) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", cat.getId());
            node.put("name", cat.getName());
            node.put("slug", cat.getSlug());
            List<Map<String, Object>> children = new ArrayList<>();
            List<Subcategory> subs = cat.getSubcategories() == null
                    ? List.of()
                    : cat.getSubcategories().stream()
                            .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                            .toList();
            for (Subcategory sub : subs) {
                Map<String, Object> child = new LinkedHashMap<>();
                child.put("id", sub.getId());
                child.put("name", sub.getName());
                child.put("slug", sub.getSlug());
                child.put("category_id", cat.getId());
                child.put("subcategory_id", sub.getId());
                children.add(child);
            }
            node.put("subcategories", children);
            categoryData.add(node);

            Map<String, Object> treeNode = new LinkedHashMap<>(node);
            treeNode.put("children", children);
            categoryTree.add(treeNode);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("auth", auth);
        body.put("region", resolvedRegion);
        body.put("currency", resolvedCurrency);
        body.put("locale", resolvedLocale);
        body.put("categories", categoryData);
        body.put("category_tree", categoryTree);
        body.put("locations", ShopConfig.REGION_LOCATIONS.getOrDefault(resolvedRegion, List.of()));
        body.put("currencies", ShopConfig.CURRENCIES);
        body.put("exchange_rates", ShopConfig.exchangeRatesJson());
        body.put("regions", ShopConfig.REGIONS);
        body.put("region_labels", ShopConfig.REGION_LABELS);
        body.put("translations", translationService.get(resolvedLocale));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/detect-region")
    public ResponseEntity<?> detectRegion(HttpServletRequest request) {
        String region = geoRegionService.detect(request);
        if (region == null || !ShopConfig.REGIONS.contains(region)) {
            region = "MM";
        }
        ShopConfig.Currency currency = ShopConfig.CURRENCIES.getOrDefault(region, ShopConfig.DEFAULT_CURRENCY);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("region", region);
        body.put("currency", currency.code());
        body.put("locale", ShopConfig.REGION_LOCALES.getOrDefault(region, "en"));
        return ResponseEntity.ok(body);
    }

    private String resolveRegion(HttpServletRequest request, String queryRegion, User user) {
        if (queryRegion != null && ShopConfig.REGIONS.contains(queryRegion.trim().toUpperCase())) {
            return queryRegion.trim().toUpperCase();
        }
        String cookie = cookieValue(request, "shop_region");
        if (cookie != null && ShopConfig.REGIONS.contains(cookie.trim().toUpperCase())) {
            return cookie.trim().toUpperCase();
        }
        if (user != null && user.getRegion() != null && ShopConfig.REGIONS.contains(user.getRegion())) {
            return user.getRegion();
        }
        String detected = geoRegionService.detect(request);
        return detected != null && ShopConfig.REGIONS.contains(detected) ? detected : "MM";
    }

    private String resolveCurrency(String queryCurrency, String region) {
        if (queryCurrency != null) {
            String code = queryCurrency.trim().toUpperCase();
            if (ShopConfig.EXCHANGE_RATES.containsKey(code)) {
                return code;
            }
        }
        return ShopConfig.currencyCodeForRegion(region);
    }

    private String resolveLocale(String queryLocale, String region) {
        if (queryLocale != null && ShopConfig.SUPPORTED_LOCALES.contains(queryLocale.trim().toLowerCase())) {
            return queryLocale.trim().toLowerCase();
        }
        if (region != null && ShopConfig.REGION_LOCALES.containsKey(region)) {
            return ShopConfig.REGION_LOCALES.get(region);
        }
        return "en";
    }

    private static String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
