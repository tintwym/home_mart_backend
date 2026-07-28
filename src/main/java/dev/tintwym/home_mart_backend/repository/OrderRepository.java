package dev.tintwym.home_mart_backend.repository;

import dev.tintwym.home_mart_backend.entity.OrderEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<OrderEntity, String> {

    List<OrderEntity> findByUserId(String userId);

    List<OrderEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<OrderEntity> findByStripeSessionId(String stripeSessionId);

    List<OrderEntity> findByUserIdAndStatusIn(String userId, Collection<String> statuses);
}
