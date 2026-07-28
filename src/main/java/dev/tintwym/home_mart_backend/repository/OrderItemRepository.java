package dev.tintwym.home_mart_backend.repository;

import dev.tintwym.home_mart_backend.entity.OrderItem;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItem, String> {

    List<OrderItem> findByOrderId(String orderId);

    List<OrderItem> findByListingId(String listingId);

    boolean existsByListingIdAndOrder_StatusIn(String listingId, Collection<String> statuses);
}
