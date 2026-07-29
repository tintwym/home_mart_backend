package dev.tintwym.home_mart_backend.repository;

import dev.tintwym.home_mart_backend.entity.OrderItem;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderItemRepository extends JpaRepository<OrderItem, String> {

    List<OrderItem> findByOrderId(String orderId);

    List<OrderItem> findByListingId(String listingId);

    boolean existsByListingIdAndOrder_StatusIn(String listingId, Collection<String> statuses);

    boolean existsByListingIdAndOrder_StatusInAndOrder_IdNot(
            String listingId, Collection<String> statuses, String orderId);

    @Query("""
            select distinct oi.listingId from OrderItem oi
            join oi.order o
            where oi.listingId in :listingIds
              and o.status in :statuses
            """)
    List<String> findListingIdsWithOrderStatusIn(
            @Param("listingIds") Collection<String> listingIds,
            @Param("statuses") Collection<String> statuses);
}
