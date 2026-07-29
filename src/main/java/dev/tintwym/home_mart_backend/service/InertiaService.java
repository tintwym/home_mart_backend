package dev.tintwym.home_mart_backend.service;

import tools.jackson.databind.ObjectMapper;
import dev.tintwym.home_mart_backend.config.AppProperties;
import dev.tintwym.home_mart_backend.entity.Category;
import dev.tintwym.home_mart_backend.entity.Favorite;
import dev.tintwym.home_mart_backend.entity.Subcategory;
import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.repository.CartItemRepository;
import dev.tintwym.home_mart_backend.repository.CategoryRepository;
import dev.tintwym.home_mart_backend.repository.FavoriteRepository;
import dev.tintwym.home_mart_backend.repository.MessageRepository;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import dev.tintwym.home_mart_backend.interfaces.UserPrincipal;
import dev.tintwym.home_mart_backend.mapper.ApiJson;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InertiaService {

    public static final String VERSION = "spring-1";
    public static final String HEADER_INERTIA = "X-Inertia";
    public static final String HEADER_LOCATION = "X-Inertia-Location";
    public static final String HEADER_VERSION = "X-Inertia-Version";
    public static final String FLASH_STATUS = "hm_flash_status";
    public static final String FLASH_ERROR = "hm_flash_error";

    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    private final TranslationService translationService;
    private final GeoRegionService geoRegionService;
    private final UserRepository userRepository;
    private final CartItemRepository cartItemRepository;
    private final FavoriteRepository favoriteRepository;
    private final MessageRepository messageRepository;
    private final CategoryRepository categoryRepository;

    public InertiaService(
            ObjectMapper objectMapper,
            AppProperties appProperties,
            TranslationService translationService,
            GeoRegionService geoRegionService,
            UserRepository userRepository,
            CartItemRepository cartItemRepository,
            FavoriteRepository favoriteRepository,
            MessageRepository messageRepository,
            CategoryRepository categoryRepository) {
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
        this.translationService = translationService;
        this.geoRegionService = geoRegionService;
        this.userRepository = userRepository;
        this.cartItemRepository = cartItemRepository;
        this.favoriteRepository = favoriteRepository;
        this.messageRepository = messageRepository;
        this.categoryRepository = categoryRepository;
    }

    @Transactional(readOnly = true)
    public ResponseEntity<?> render(
            HttpServletRequest request,
            HttpServletResponse response,
            String component,
            Map<String, Object> props) {
        Map<String, Object> page = buildPage(request, response, component, props);
        boolean isInertia = "true".equalsIgnoreCase(request.getHeader(HEADER_INERTIA));

        if (isInertia) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HEADER_INERTIA, "true")
                    .header(HEADER_VERSION, VERSION)
                    .body(page);
        }

        try {
            String json = objectMapper.writeValueAsString(page);
            String escaped = escapeHtml(json);
            // Vite serves the React entry; this shell is returned through the
            // Vite proxy (or when hitting the API host directly in local dev).
            String viteOrigin = trimTrailingSlash(appProperties.getFrontendUrl());
            String html = """
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                      <meta charset="utf-8"/>
                      <meta name="viewport" content="width=device-width, initial-scale=1"/>
                      <title>%s</title>
                      <link rel="icon" href="%s/favicon-32.png" type="image/png" sizes="32x32"/>
                      <link rel="icon" href="%s/favicon.ico" sizes="any"/>
                      <link rel="apple-touch-icon" href="%s/apple-touch-icon.png" sizes="180x180"/>
                      <script>
                        (function () {
                          var dark = window.matchMedia('(prefers-color-scheme: dark)').matches;
                          document.documentElement.classList.toggle('dark', dark);
                          document.documentElement.style.colorScheme = dark ? 'dark' : 'light';
                        })();
                      </script>
                      <script type="module">
                        import RefreshRuntime from '%s/@react-refresh'
                        RefreshRuntime.injectIntoGlobalHook(window)
                        window.$RefreshReg$ = () => {}
                        window.$RefreshSig$ = () => (type) => type
                        window.__vite_plugin_react_preamble_installed__ = true
                      </script>
                      <script type="module" src="%s/@vite/client"></script>
                      <script type="module" src="%s/resources/js/app.tsx"></script>
                    </head>
                    <body>
                      <div id="app" data-page="%s"></div>
                    </body>
                    </html>
                    """.formatted(
                    escapeHtml(appProperties.getName()),
                    viteOrigin,
                    viteOrigin,
                    viteOrigin,
                    viteOrigin,
                    viteOrigin,
                    viteOrigin,
                    escaped);
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render Inertia HTML shell", e);
        }
    }

    /** Same-origin Inertia/HTML redirect (303 See Other). */
    public ResponseEntity<?> redirect(HttpServletRequest request, String location) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .header(HttpHeaders.LOCATION, location)
                .build();
    }

    /**
     * External redirect. Inertia clients need 409 + {@code X-Inertia-Location};
     * plain browsers get 303 Location.
     */
    public ResponseEntity<?> externalRedirect(HttpServletRequest request, String location) {
        boolean isInertia = "true".equalsIgnoreCase(request.getHeader(HEADER_INERTIA));
        if (isInertia) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .header(HEADER_LOCATION, location)
                    .header(HEADER_INERTIA, "true")
                    .header(HEADER_VERSION, VERSION)
                    .build();
        }
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .header(HttpHeaders.LOCATION, location)
                .build();
    }

    public ResponseEntity<?> redirectWithFlash(
            HttpServletRequest request,
            HttpServletResponse response,
            String location,
            String status,
            String error) {
        if (status != null) {
            setFlashCookie(response, FLASH_STATUS, status);
        }
        if (error != null) {
            setFlashCookie(response, FLASH_ERROR, error);
        }
        return redirect(request, location);
    }

    public ResponseEntity<?> back(
            HttpServletRequest request, HttpServletResponse response, String fallback) {
        String referer = request.getHeader(HttpHeaders.REFERER);
        if (referer != null && !referer.isBlank()) {
            try {
                java.net.URI uri = java.net.URI.create(referer);
                String path = uri.getRawPath();
                if (path == null || path.isBlank()) {
                    path = "/";
                }
                String query = uri.getRawQuery();
                String location =
                        query == null || query.isBlank() ? path : path + "?" + query;
                return redirect(request, location);
            } catch (Exception ignored) {
                // Fall through to fallback.
            }
        }
        return redirect(request, fallback == null ? "/" : fallback);
    }

    public ResponseEntity<?> backWithFlash(
            HttpServletRequest request,
            HttpServletResponse response,
            String status,
            String error,
            String fallback) {
        if (status != null) {
            setFlashCookie(response, FLASH_STATUS, status);
        }
        if (error != null) {
            setFlashCookie(response, FLASH_ERROR, error);
        }
        return back(request, response, fallback);
    }

    public Map<String, Object> buildPage(
            HttpServletRequest request,
            HttpServletResponse response,
            String component,
            Map<String, Object> props) {
        Map<String, Object> merged = new LinkedHashMap<>(sharedProps(request, response));
        if (props != null) {
            merged.putAll(props);
        }

        Map<String, Object> page = new LinkedHashMap<>();
        page.put("component", component);
        page.put("props", merged);
        page.put("url", request.getRequestURI()
                + (request.getQueryString() == null ? "" : "?" + request.getQueryString()));
        page.put("version", VERSION);
        return page;
    }

    public Map<String, Object> sharedProps(HttpServletRequest request) {
        return sharedProps(request, null);
    }

    public Map<String, Object> sharedProps(HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> shared = new LinkedHashMap<>();
        String locale = resolveLocale(request);
        String region = geoRegionService.detect(request);
        ShopConfig.Currency currency = ShopConfig.CURRENCIES.getOrDefault(region, ShopConfig.DEFAULT_CURRENCY);

        String preferredCurrency = cookieValue(request, "shop_currency");
        if (preferredCurrency != null && !preferredCurrency.isBlank()) {
            for (ShopConfig.Currency c : ShopConfig.CURRENCIES.values()) {
                if (c.code().equals(preferredCurrency)) {
                    currency = c;
                    break;
                }
            }
        }

        Map<String, Object> flash = new LinkedHashMap<>();
        flash.put("status", consumeFlash(request, response, FLASH_STATUS));
        flash.put("error", consumeFlash(request, response, FLASH_ERROR));

        Optional<User> userOpt = currentUserEntity();
        List<String> cartListingIds = List.of();
        List<String> favoriteListingIds = List.of();
        int cartCount = 0;
        int chatUnreadCount = 0;
        Object userJson = null;

        if (userOpt.isPresent()) {
            User user = userOpt.get();
            userJson = ApiJson.userJson(user);
            var cartItems = cartItemRepository.findByUserId(user.getId());
            cartCount = cartItems.size();
            cartListingIds = cartItems.stream().map(c -> c.getListingId()).toList();
            favoriteListingIds = favoriteRepository.findByUserId(user.getId()).stream()
                    .map(Favorite::getListingId)
                    .toList();
            chatUnreadCount = (int) messageRepository.countUnreadForUser(user.getId());
        }

        List<Map<String, Object>> categories = new ArrayList<>();
        List<Map<String, Object>> categoryTree = new ArrayList<>();
        try {
            List<Category> parents = categoryRepository.findAllWithSubcategoriesOrderByNameAsc();
            for (Category cat : parents) {
                Map<String, Object> flat = new LinkedHashMap<>();
                flat.put("id", cat.getId());
                flat.put("name", cat.getName());
                flat.put("slug", cat.getSlug());
                flat.put("category_id", null);
                flat.put("subcategory_id", null);
                categories.add(flat);

                List<Map<String, Object>> children = new ArrayList<>();
                if (cat.getSubcategories() != null) {
                    for (Subcategory sub : cat.getSubcategories()) {
                        Map<String, Object> child = new LinkedHashMap<>();
                        child.put("id", sub.getId());
                        child.put("name", sub.getName());
                        child.put("slug", sub.getSlug());
                        child.put("category_id", cat.getId());
                        child.put("subcategory_id", sub.getId());
                        children.add(child);
                    }
                }
                Map<String, Object> treeNode = new LinkedHashMap<>(flat);
                treeNode.put("children", children);
                categoryTree.add(treeNode);
            }
        } catch (Exception ignored) {
            // DB may not be ready during early boot; keep empty lists.
        }

        List<ShopConfig.Location> locs = ShopConfig.REGION_LOCATIONS.getOrDefault(region, List.of());
        List<Map<String, Object>> locationMaps = locs.stream()
                .map(l -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", l.name());
                    m.put("lat", l.lat());
                    m.put("lng", l.lng());
                    return m;
                })
                .toList();

        Map<String, Object> authMap = new LinkedHashMap<>();
        authMap.put("user", userJson);
        authMap.put("cartCount", cartCount);
        authMap.put("cartListingIds", cartListingIds);
        authMap.put("favoriteListingIds", favoriteListingIds);
        authMap.put("chatUnreadCount", chatUnreadCount);

        shared.put("errors", Map.of());
        shared.put("flash", flash);
        shared.put("name", appProperties.getName());
        shared.put("locale", locale);
        shared.put("translations", translationService.get(locale));
        shared.put("auth", authMap);
        shared.put("sidebarOpen", !"false".equals(cookieValue(request, "sidebar_state")));
        shared.put("categories", categories);
        shared.put("categoryTree", categoryTree);
        shared.put("locations", locationMaps);
        shared.put("regionLabel", ShopConfig.REGION_LABELS.getOrDefault(region, "All"));
        shared.put("region", region);
        shared.put("currency", Map.of(
                "code", currency.code(),
                "symbol", currency.symbol(),
                "decimals", currency.decimals()));
        shared.put("currencies", ShopConfig.CURRENCIES);
        shared.put("exchangeRates", ShopConfig.exchangeRatesJson());
        return shared;
    }

    private Optional<User> currentUserEntity() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            return Optional.empty();
        }
        return userRepository.findById(principal.getId());
    }

    private String resolveLocale(HttpServletRequest request) {
        String cookie = cookieValue(request, "locale");
        if (cookie != null && !cookie.isBlank() && ShopConfig.SUPPORTED_LOCALES.contains(cookie)) {
            return cookie;
        }
        String region = geoRegionService.detect(request);
        if (region != null && ShopConfig.REGION_LOCALES.containsKey(region)) {
            return ShopConfig.REGION_LOCALES.get(region);
        }
        return "en";
    }

    private void setFlashCookie(HttpServletResponse response, String name, String value) {
        String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
        Cookie cookie = new Cookie(name, encoded);
        cookie.setPath("/");
        cookie.setHttpOnly(false);
        cookie.setMaxAge(60);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    private String consumeFlash(
            HttpServletRequest request, HttpServletResponse response, String name) {
        String value = cookieValue(request, name);
        if (value == null) {
            return null;
        }
        if (response != null) {
            Cookie clear = new Cookie(name, "");
            clear.setPath("/");
            clear.setMaxAge(0);
            clear.setHttpOnly(false);
            clear.setAttribute("SameSite", "Lax");
            response.addCookie(clear);
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
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

    private static String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "http://localhost:3000";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
