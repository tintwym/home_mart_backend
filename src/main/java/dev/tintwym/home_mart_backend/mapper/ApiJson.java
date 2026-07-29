package dev.tintwym.home_mart_backend.mapper;

import dev.tintwym.home_mart_backend.entity.Listing;
import dev.tintwym.home_mart_backend.entity.Subcategory;
import dev.tintwym.home_mart_backend.entity.User;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON shape helpers matching Laravel/Inertia API payloads (snake_case keys).
 */
public final class ApiJson {

    private static final DateTimeFormatter LARAVEL_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private ApiJson() {
    }

    public static Map<String, Object> userJson(User user) {
        if (user == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.getId());
        map.put("name", user.getName());
        map.put("email", user.getEmail());
        map.put("phone", user.getPhone());
        map.put("address", user.getAddress());
        map.put("region", user.getRegion());
        map.put("seller_type", user.getSellerType());
        map.put("extra_listing_slots", user.getExtraListingSlots() == null ? 0 : user.getExtraListingSlots());
        map.put("email_verified_at", formatInstant(user.getEmailVerifiedAt()));
        map.put("two_factor_confirmed_at", formatInstant(user.getTwoFactorConfirmedAt()));
        map.put("created_at", formatInstant(user.getCreatedAt()));
        map.put("updated_at", formatInstant(user.getUpdatedAt()));
        return map;
    }

    /** Slim user shape used by native/API auth responses. */
    public static Map<String, Object> apiUserJson(User user) {
        if (user == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.getId());
        map.put("name", user.getName());
        map.put("email", user.getEmail());
        map.put("seller_type", user.getSellerType());
        map.put("extra_listing_slots", user.getExtraListingSlots() == null ? 0 : user.getExtraListingSlots());
        map.put("region", user.getRegion());
        map.put("phone", user.getPhone());
        map.put("address", user.getAddress());
        map.put("created_at", formatInstant(user.getCreatedAt()));
        return map;
    }

    public static Map<String, Object> publicUserJson(User user) {
        if (user == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.getId());
        map.put("name", user.getName());
        map.put("seller_type", user.getSellerType());
        map.put("region", user.getRegion());
        map.put("created_at", formatInstant(user.getCreatedAt()));
        return map;
    }

    public static Map<String, Object> listingSummaryJson(Listing listing) {
        return listingSummaryJson(listing, false);
    }

    public static Map<String, Object> listingSummaryJson(Listing listing, boolean isSold) {
        if (listing == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", listing.getId());
        map.put("user_id", listing.getUserId());
        map.put("subcategory_id", listing.getSubcategoryId());
        map.put("title", listing.getTitle());
        map.put("description", listing.getDescription());
        map.put("condition", listing.getCondition());
        map.put("price", listing.getPrice());
        map.put("image_path", listing.getImagePath());
        map.put("image_url", imageUrl(listing.getImagePath()));
        map.put("meetup_location", listing.getMeetupLocation());
        map.put("views_count", listing.getViewsCount() == null ? 0 : listing.getViewsCount());
        map.put("trending_until", formatInstant(listing.getTrendingUntil()));
        map.put("is_trending", listing.isTrending());
        map.put("is_sold", isSold);
        map.put("created_at", formatInstant(listing.getCreatedAt()));
        map.put("updated_at", formatInstant(listing.getUpdatedAt()));

        if (listing.getUser() != null) {
            Map<String, Object> seller = userSummaryJson(listing.getUser());
            map.put("user", seller);
            map.put("seller", seller);
        }
        if (listing.getSubcategory() != null) {
            map.put("category", subcategoryJson(listing.getSubcategory()));
        }
        return map;
    }

    public static Map<String, Object> userSummaryJson(User user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.getId());
        map.put("name", user.getName());
        map.put("region", user.getRegion());
        map.put("seller_type", user.getSellerType());
        return map;
    }

    public static Map<String, Object> subcategoryJson(Subcategory subcategory) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", subcategory.getId());
        map.put("name", subcategory.getName());
        map.put("slug", subcategory.getSlug());
        map.put("category_id", subcategory.getCategoryId());
        map.put("subcategory_id", subcategory.getId());
        return map;
    }

    public static String imageUrl(String imagePath) {
        if (imagePath == null || imagePath.isBlank()) {
            return null;
        }
        if (imagePath.startsWith("http://") || imagePath.startsWith("https://")) {
            return imagePath;
        }
        if (imagePath.startsWith("/storage/")) {
            return imagePath;
        }
        return "/storage/" + imagePath.replaceFirst("^/+", "");
    }

    public static String formatInstant(Instant instant) {
        if (instant == null) {
            return null;
        }
        return LARAVEL_UTC.format(instant);
    }
}
