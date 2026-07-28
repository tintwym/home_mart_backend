package dev.tintwym.home_mart_backend.controller.api;

import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.repository.ListingRepository;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import dev.tintwym.home_mart_backend.mapper.ApiJson;
import dev.tintwym.home_mart_backend.utility.ApiResponses;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UsersController {

    private final UserRepository userRepository;
    private final ListingRepository listingRepository;

    public UsersController(UserRepository userRepository, ListingRepository listingRepository) {
        this.userRepository = userRepository;
        this.listingRepository = listingRepository;
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> show(@PathVariable String id) {
        User user = userRepository.findById(id).orElse(null);
        if (user == null) {
            return ApiResponses.notFound("User not found.");
        }
        List<Map<String, Object>> listings = listingRepository.findByUserIdWithRelations(id).stream()
                .limit(50)
                .map(ApiJson::listingSummaryJson)
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", ApiJson.publicUserJson(user));
        body.put("listings", listings);
        return ResponseEntity.ok(body);
    }
}
