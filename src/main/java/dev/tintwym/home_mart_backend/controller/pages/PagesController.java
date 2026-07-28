package dev.tintwym.home_mart_backend.controller.pages;

import dev.tintwym.home_mart_backend.entity.Category;
import dev.tintwym.home_mart_backend.entity.Listing;
import dev.tintwym.home_mart_backend.entity.Review;
import dev.tintwym.home_mart_backend.entity.Subcategory;
import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.repository.CategoryRepository;
import dev.tintwym.home_mart_backend.repository.ListingRepository;
import dev.tintwym.home_mart_backend.repository.ReviewRepository;
import dev.tintwym.home_mart_backend.repository.SubcategoryRepository;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import dev.tintwym.home_mart_backend.mapper.ApiJson;
import dev.tintwym.home_mart_backend.service.AuthCookieService;
import dev.tintwym.home_mart_backend.service.InertiaService;
import dev.tintwym.home_mart_backend.service.ShopConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class PagesController extends PageControllerSupport {

    private final ListingRepository listingRepository;
    private final CategoryRepository categoryRepository;
    private final SubcategoryRepository subcategoryRepository;
    private final ReviewRepository reviewRepository;
    private final AuthCookieService authCookieService;

    public PagesController(
            InertiaService inertia,
            UserRepository userRepository,
            ListingRepository listingRepository,
            CategoryRepository categoryRepository,
            SubcategoryRepository subcategoryRepository,
            ReviewRepository reviewRepository,
            AuthCookieService authCookieService) {
        super(inertia, userRepository);
        this.listingRepository = listingRepository;
        this.categoryRepository = categoryRepository;
        this.subcategoryRepository = subcategoryRepository;
        this.reviewRepository = reviewRepository;
        this.authCookieService = authCookieService;
    }

    @GetMapping({"/", "/dashboard", "/home"})
    @Transactional(readOnly = true)
    public ResponseEntity<?> dashboard(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam(value = "q", required = false) String q) {
        if ("/dashboard".equals(request.getRequestURI()) || "/home".equals(request.getRequestURI())) {
            return redirect(request, "/");
        }
        List<Listing> raw = listingRepository.search(blankToNull(q), null, null);
        if (raw.size() > 100) {
            raw = raw.subList(0, 100);
        }
        List<String> ids = raw.stream().map(Listing::getId).toList();
        List<Map<String, Object>> listings = listingRepository.findDetailedByIdIn(ids).stream()
                .map(ApiJson::listingSummaryJson)
                .toList();
        // Preserve trending order from search
        Map<String, Map<String, Object>> byId = listings.stream()
                .collect(Collectors.toMap(m -> String.valueOf(m.get("id")), m -> m, (a, b) -> a, LinkedHashMap::new));
        List<Map<String, Object>> ordered = ids.stream().map(byId::get).filter(m -> m != null).toList();

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("listings", ordered);
        props.put("searchQuery", q == null ? "" : q);
        return render(request, response, "dashboard", props);
    }

    @GetMapping("/categories/{slug}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> categoryShow(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String slug) {
        Optional<Subcategory> subOpt = subcategoryRepository.findBySlug(slug);
        if (subOpt.isPresent()) {
            Subcategory sub = subOpt.get();
            List<Listing> listings = listingRepository.search(null, sub.getId(), null);
            List<String> ids = listings.stream().map(Listing::getId).toList();
            Map<String, Map<String, Object>> byId = listingRepository.findDetailedByIdIn(ids).stream()
                    .map(ApiJson::listingSummaryJson)
                    .collect(Collectors.toMap(m -> String.valueOf(m.get("id")), m -> m, (a, b) -> a, LinkedHashMap::new));
            List<Map<String, Object>> ordered = ids.stream().map(byId::get).filter(m -> m != null).toList();

            Map<String, Object> props = new LinkedHashMap<>();
            props.put("category", ApiJson.subcategoryJson(sub));
            props.put("listings", ordered);
            return render(request, response, "categories/show", props);
        }

        Category category = categoryRepository.findBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found"));
        List<Listing> listings = listingRepository.searchByCategory(null, category.getId(), null, null);
        List<String> ids = listings.stream().map(Listing::getId).toList();
        Map<String, Map<String, Object>> byId = listingRepository.findDetailedByIdIn(ids).stream()
                .map(ApiJson::listingSummaryJson)
                .collect(Collectors.toMap(m -> String.valueOf(m.get("id")), m -> m, (a, b) -> a, LinkedHashMap::new));
        List<Map<String, Object>> ordered = ids.stream().map(byId::get).filter(m -> m != null).toList();

        Map<String, Object> catMap = new LinkedHashMap<>();
        catMap.put("id", category.getId());
        catMap.put("name", category.getName());
        catMap.put("slug", category.getSlug());

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("category", catMap);
        props.put("listings", ordered);
        return render(request, response, "categories/show", props);
    }

    @GetMapping("/download")
    public ResponseEntity<?> download(HttpServletRequest request, HttpServletResponse response) {
        Path apk = Path.of("public/downloads/homemart.apk");
        boolean apkAvailable = Files.exists(apk);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("playStoreUrl", null);
        props.put("apkAvailable", apkAvailable);
        props.put("apkUrl", apkAvailable ? "/downloads/homemart.apk" : null);
        props.put("iosStoreUrl", null);
        return render(request, response, "download", props);
    }

    @GetMapping("/users/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> userShow(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        List<Listing> listings = listingRepository.findByUserIdWithRelations(id);
        List<Map<String, Object>> listingJson = listings.stream().map(ApiJson::listingSummaryJson).toList();
        List<String> listingIds = listings.stream().map(Listing::getId).toList();
        List<Review> reviews = listingIds.isEmpty()
                ? List.of()
                : listingIds.stream()
                        .flatMap(lid -> reviewRepository.findByListingIdAndParentIdIsNullOrderByCreatedAtDesc(lid).stream())
                        .toList();
        double avg = reviews.stream().mapToInt(r -> r.getRating() == null ? 0 : r.getRating()).average().orElse(0);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("user", ApiJson.publicUserJson(user));
        props.put("listings", listingJson);
        props.put("averageRating", Math.round(avg * 10.0) / 10.0);
        props.put("reviewCount", reviews.size());
        return render(request, response, "users/show", props);
    }

    @PostMapping("/region")
    public ResponseEntity<?> setRegion(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam(value = "region", required = false) String region) {
        if (region != null && ShopConfig.REGIONS.contains(region)) {
            authCookieService.setShopRegion(response, region);
        }
        return inertia.back(request, response, "/");
    }

    @PostMapping("/locale")
    public ResponseEntity<?> setLocale(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam(value = "locale", required = false) String locale) {
        if (locale != null && ShopConfig.SUPPORTED_LOCALES.contains(locale)) {
            authCookieService.setLocale(response, locale, true);
        }
        return inertia.back(request, response, "/");
    }

    @PostMapping("/currency")
    public ResponseEntity<?> setCurrency(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam(value = "currency", required = false) String currency) {
        if (currency != null) {
            boolean ok = ShopConfig.CURRENCIES.values().stream().anyMatch(c -> c.code().equals(currency));
            if (ok) {
                authCookieService.setShopCurrency(response, currency);
            }
        }
        return inertia.back(request, response, "/");
    }
}
