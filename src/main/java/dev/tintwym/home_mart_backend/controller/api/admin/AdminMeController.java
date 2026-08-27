package dev.tintwym.home_mart_backend.controller.api.admin;

import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.mapper.ApiJson;
import dev.tintwym.home_mart_backend.repository.ListingRepository;
import dev.tintwym.home_mart_backend.repository.OrderRepository;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import dev.tintwym.home_mart_backend.utility.ApiResponses;
import dev.tintwym.home_mart_backend.utility.AuthSupport;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminMeController {

    private final UserRepository userRepository;
    private final ListingRepository listingRepository;
    private final OrderRepository orderRepository;

    public AdminMeController(
            UserRepository userRepository,
            ListingRepository listingRepository,
            OrderRepository orderRepository) {
        this.userRepository = userRepository;
        this.listingRepository = listingRepository;
        this.orderRepository = orderRepository;
    }

    @GetMapping("/me")
    @Transactional(readOnly = true)
    public ResponseEntity<?> me() {
        User user = userRepository.findById(AuthSupport.currentUserId()).orElse(null);
        if (user == null || !user.isAdmin()) {
            return ApiResponses.forbidden("Admin access required.");
        }
        return ResponseEntity.ok(ApiJson.apiUserJson(user));
    }

    @GetMapping("/stats")
    @Transactional(readOnly = true)
    public ResponseEntity<?> stats() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("users", userRepository.count());
        body.put("listings", listingRepository.count());
        body.put("orders", orderRepository.count());
        return ResponseEntity.ok(body);
    }
}
