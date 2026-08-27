package dev.tintwym.home_mart_backend.controller.api.admin;

import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.mapper.ApiJson;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import dev.tintwym.home_mart_backend.utility.ApiResponses;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUsersController {

    private final UserRepository userRepository;

    public AdminUsersController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<?> index(@RequestParam(required = false) String q) {
        List<User> users = userRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        List<Map<String, Object>> data = new ArrayList<>();
        String needle = q == null ? "" : q.trim().toLowerCase();
        for (User user : users) {
            if (!needle.isEmpty()) {
                String hay = ((user.getName() == null ? "" : user.getName()) + " "
                        + (user.getEmail() == null ? "" : user.getEmail())).toLowerCase();
                if (!hay.contains(needle)) {
                    continue;
                }
            }
            data.add(ApiJson.userJson(user));
        }
        return ResponseEntity.ok(Map.of("data", data));
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> show(@PathVariable String id) {
        User user = userRepository.findById(id).orElse(null);
        if (user == null) {
            return ApiResponses.notFound("User not found.");
        }
        return ResponseEntity.ok(ApiJson.userJson(user));
    }

    @PatchMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        User user = userRepository.findById(id).orElse(null);
        if (user == null) {
            return ApiResponses.notFound("User not found.");
        }

        if (body.containsKey("role")) {
            String role = String.valueOf(body.get("role")).trim().toLowerCase();
            if ("admin".equals(role) || "user".equals(role)) {
                user.setRole(role);
            } else {
                return ApiResponses.unprocessable("Invalid role.", Map.of("role", List.of("Must be user or admin.")));
            }
        }
        if (body.containsKey("seller_type")) {
            String sellerType = String.valueOf(body.get("seller_type")).trim().toLowerCase();
            if ("individual".equals(sellerType) || "business".equals(sellerType)) {
                user.setSellerType(sellerType);
            } else {
                return ApiResponses.unprocessable(
                        "Invalid seller_type.",
                        Map.of("seller_type", List.of("Must be individual or business.")));
            }
        }
        if (body.containsKey("banned")) {
            Object raw = body.get("banned");
            boolean banned = raw instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(raw));
            user.setBannedAt(banned ? Instant.now() : null);
        }

        userRepository.save(user);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("user", ApiJson.userJson(user));
        resp.put("message", "User updated.");
        return ResponseEntity.ok(resp);
    }
}
