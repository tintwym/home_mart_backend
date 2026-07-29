package dev.tintwym.home_mart_backend.controller.pages;

import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import dev.tintwym.home_mart_backend.interfaces.UserPrincipal;
import dev.tintwym.home_mart_backend.service.GeoRegionService;
import dev.tintwym.home_mart_backend.service.InertiaService;
import dev.tintwym.home_mart_backend.service.ShopConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Shared helpers for Inertia page controllers.
 */
public abstract class PageControllerSupport {

    protected final InertiaService inertia;
    protected final UserRepository userRepository;

    protected PageControllerSupport(InertiaService inertia, UserRepository userRepository) {
        this.inertia = inertia;
        this.userRepository = userRepository;
    }

    protected Optional<UserPrincipal> currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            return Optional.empty();
        }
        return Optional.of(principal);
    }

    protected Optional<String> currentUserId() {
        return currentPrincipal().map(UserPrincipal::getId);
    }

    protected Optional<User> currentUser() {
        return currentUserId().flatMap(userRepository::findById);
    }

    protected User requireUser() {
        return currentUser().orElseThrow(() -> new IllegalStateException("Unauthenticated"));
    }

    protected ResponseEntity<?> requireLogin(
            HttpServletRequest request, HttpServletResponse response) {
        if (currentUserId().isPresent()) {
            return null;
        }
        return inertia.redirect(request, "/login");
    }

    protected ResponseEntity<?> render(
            HttpServletRequest request,
            HttpServletResponse response,
            String component,
            Map<String, Object> props) {
        return inertia.render(request, response, component, props == null ? Map.of() : props);
    }

    protected ResponseEntity<?> redirect(
            HttpServletRequest request, String location) {
        return inertia.redirect(request, location);
    }

    protected ResponseEntity<?> redirectWithStatus(
            HttpServletRequest request,
            HttpServletResponse response,
            String location,
            String status) {
        return inertia.redirectWithFlash(request, response, location, status, null);
    }

    protected ResponseEntity<?> redirectWithError(
            HttpServletRequest request,
            HttpServletResponse response,
            String location,
            String error) {
        return inertia.redirectWithFlash(request, response, location, null, error);
    }

    protected ResponseEntity<?> backWithStatus(
            HttpServletRequest request, HttpServletResponse response, String status) {
        return inertia.backWithFlash(request, response, status, null, "/");
    }

    protected ResponseEntity<?> backWithError(
            HttpServletRequest request, HttpServletResponse response, String error) {
        return inertia.backWithFlash(request, response, null, error, "/");
    }

    protected ShopConfig.Currency currencyFor(HttpServletRequest request, GeoRegionService geo) {
        String region = geo.detect(request);
        ShopConfig.Currency currency =
                ShopConfig.CURRENCIES.getOrDefault(region, ShopConfig.DEFAULT_CURRENCY);
        String preferred = cookieOrNull(request, "shop_currency");
        if (preferred != null && !preferred.isBlank()) {
            for (ShopConfig.Currency c : ShopConfig.CURRENCIES.values()) {
                if (c.code().equals(preferred)) {
                    return c;
                }
            }
        }
        return currency;
    }

    protected ShopConfig.Currency currencyFor(HttpServletRequest request) {
        String region = cookieOrNull(request, "shop_region");
        if (region == null || !ShopConfig.REGIONS.contains(region)) {
            region = "MM";
        }
        ShopConfig.Currency currency =
                ShopConfig.CURRENCIES.getOrDefault(region, ShopConfig.DEFAULT_CURRENCY);
        String preferred = cookieOrNull(request, "shop_currency");
        if (preferred != null && !preferred.isBlank()) {
            for (ShopConfig.Currency c : ShopConfig.CURRENCIES.values()) {
                if (c.code().equals(preferred)) {
                    return c;
                }
            }
        }
        return currency;
    }

    protected Map<String, Object> errors(String field, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(field, message);
        return map;
    }

    protected static String cookieOrNull(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (var c : request.getCookies()) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    protected static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
