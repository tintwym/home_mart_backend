package dev.tintwym.home_mart_backend.controller.api.admin;

import dev.tintwym.home_mart_backend.entity.OrderEntity;
import dev.tintwym.home_mart_backend.entity.OrderItem;
import dev.tintwym.home_mart_backend.mapper.ApiJson;
import dev.tintwym.home_mart_backend.repository.OrderRepository;
import dev.tintwym.home_mart_backend.utility.ApiResponses;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrdersController {

    private final OrderRepository orderRepository;

    public AdminOrdersController(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<?> index() {
        List<OrderEntity> orders = orderRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        List<Map<String, Object>> data = new ArrayList<>();
        for (OrderEntity order : orders) {
            data.add(orderSummary(order, false));
        }
        return ResponseEntity.ok(Map.of("data", data));
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> show(@PathVariable String id) {
        OrderEntity order = orderRepository.findById(id).orElse(null);
        if (order == null) {
            return ApiResponses.notFound("Order not found.");
        }
        return ResponseEntity.ok(orderSummary(order, true));
    }

    private static Map<String, Object> orderSummary(OrderEntity order, boolean withItems) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", order.getId());
        map.put("user_id", order.getUserId());
        map.put("status", order.getStatus());
        map.put("total", order.getTotal());
        map.put("payment_gateway", order.getPaymentGateway());
        map.put("payment_reference", order.getPaymentReference());
        map.put("stripe_session_id", order.getStripeSessionId());
        map.put("created_at", ApiJson.formatInstant(order.getCreatedAt()));
        map.put("updated_at", ApiJson.formatInstant(order.getUpdatedAt()));
        if (order.getUser() != null) {
            map.put("user", ApiJson.publicUserJson(order.getUser()));
        }
        if (withItems) {
            List<Map<String, Object>> items = new ArrayList<>();
            List<OrderItem> orderItems = order.getItems() == null ? List.of() : order.getItems();
            for (OrderItem item : orderItems) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", item.getId());
                row.put("listing_id", item.getListingId());
                row.put("quantity", item.getQuantity());
                row.put("unit_price", item.getPrice());
                items.add(row);
            }
            map.put("items", items);
        }
        return map;
    }
}
