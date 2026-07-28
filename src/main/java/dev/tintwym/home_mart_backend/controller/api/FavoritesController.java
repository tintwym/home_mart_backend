package dev.tintwym.home_mart_backend.controller.api;

import dev.tintwym.home_mart_backend.entity.Favorite;
import dev.tintwym.home_mart_backend.entity.Listing;
import dev.tintwym.home_mart_backend.repository.FavoriteRepository;
import dev.tintwym.home_mart_backend.repository.ListingRepository;
import dev.tintwym.home_mart_backend.mapper.ApiJson;
import dev.tintwym.home_mart_backend.utility.ApiResponses;
import dev.tintwym.home_mart_backend.utility.AuthSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class FavoritesController {

    private final FavoriteRepository favoriteRepository;
    private final ListingRepository listingRepository;

    public FavoritesController(FavoriteRepository favoriteRepository, ListingRepository listingRepository) {
        this.favoriteRepository = favoriteRepository;
        this.listingRepository = listingRepository;
    }

    @GetMapping("/favorites")
    @Transactional(readOnly = true)
    public ResponseEntity<?> index() {
        String userId = AuthSupport.currentUserId();
        List<Map<String, Object>> data = new ArrayList<>();
        for (Favorite favorite : favoriteRepository.findByUserId(userId)) {
            listingRepository
                    .findByIdWithRelations(favorite.getListingId())
                    .ifPresent(listing -> data.add(ApiJson.listingSummaryJson(listing)));
        }
        return ResponseEntity.ok(Map.of("data", data));
    }

    @PostMapping("/listings/{listingId}/favorite")
    @Transactional
    public ResponseEntity<?> toggle(@PathVariable String listingId) {
        String userId = AuthSupport.currentUserId();
        Listing listing = listingRepository.findById(listingId).orElse(null);
        if (listing == null) {
            return ApiResponses.notFound("Listing not found.");
        }
        var existing = favoriteRepository.findByUserIdAndListingId(userId, listingId);
        boolean favorited;
        if (existing.isPresent()) {
            favoriteRepository.delete(existing.get());
            favorited = false;
        } else {
            Favorite favorite = new Favorite();
            favorite.setUserId(userId);
            favorite.setListingId(listingId);
            favoriteRepository.save(favorite);
            favorited = true;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("favorited", favorited);
        body.put("message", favorited ? "Added to favorites." : "Removed from favorites.");
        return ResponseEntity.ok(body);
    }
}
