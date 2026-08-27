package dev.tintwym.home_mart_backend.controller.api.admin;

import dev.tintwym.home_mart_backend.entity.Listing;
import dev.tintwym.home_mart_backend.mapper.ApiJson;
import dev.tintwym.home_mart_backend.repository.ListingRepository;
import dev.tintwym.home_mart_backend.utility.ApiResponses;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/listings")
public class AdminListingsController {

    private final ListingRepository listingRepository;

    public AdminListingsController(ListingRepository listingRepository) {
        this.listingRepository = listingRepository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<?> index(@RequestParam(required = false) String q) {
        List<Listing> listings = listingRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        List<Map<String, Object>> data = new ArrayList<>();
        String needle = q == null ? "" : q.trim().toLowerCase();
        for (Listing listing : listings) {
            if (!needle.isEmpty()) {
                String hay = ((listing.getTitle() == null ? "" : listing.getTitle()) + " "
                        + (listing.getDescription() == null ? "" : listing.getDescription())).toLowerCase();
                if (!hay.contains(needle)) {
                    continue;
                }
            }
            data.add(ApiJson.listingSummaryJson(listing));
        }
        return ResponseEntity.ok(Map.of("data", data));
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> show(@PathVariable String id) {
        Listing listing = listingRepository.findDetailedById(id).orElse(null);
        if (listing == null) {
            return ApiResponses.notFound("Listing not found.");
        }
        return ResponseEntity.ok(ApiJson.listingSummaryJson(listing));
    }

    @PatchMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Listing listing = listingRepository.findById(id).orElse(null);
        if (listing == null) {
            return ApiResponses.notFound("Listing not found.");
        }

        if (body.containsKey("title") && body.get("title") != null) {
            listing.setTitle(String.valueOf(body.get("title")).trim());
        }
        if (body.containsKey("description") && body.get("description") != null) {
            listing.setDescription(String.valueOf(body.get("description")).trim());
        }
        if (body.containsKey("meetup_location")) {
            Object raw = body.get("meetup_location");
            listing.setMeetupLocation(raw == null ? null : String.valueOf(raw).trim());
        }
        if (body.containsKey("trending")) {
            Object raw = body.get("trending");
            boolean trending = raw instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(raw));
            listing.setTrendingUntil(trending ? Instant.now().plusSeconds(7L * 24 * 60 * 60) : null);
        }
        if (body.containsKey("hidden")) {
            Object raw = body.get("hidden");
            boolean hidden = raw instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(raw));
            String title = listing.getTitle() == null ? "" : listing.getTitle();
            if (hidden && !title.startsWith("[HIDDEN] ")) {
                listing.setTitle("[HIDDEN] " + title);
            } else if (!hidden && title.startsWith("[HIDDEN] ")) {
                listing.setTitle(title.substring("[HIDDEN] ".length()));
            }
        }

        listingRepository.save(listing);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("listing", ApiJson.listingSummaryJson(listing));
        resp.put("message", "Listing updated.");
        return ResponseEntity.ok(resp);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> destroy(@PathVariable String id) {
        Listing listing = listingRepository.findById(id).orElse(null);
        if (listing == null) {
            return ApiResponses.notFound("Listing not found.");
        }
        listingRepository.delete(listing);
        return ResponseEntity.ok(Map.of("message", "Listing deleted."));
    }
}
