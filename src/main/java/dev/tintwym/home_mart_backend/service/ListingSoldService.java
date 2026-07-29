package dev.tintwym.home_mart_backend.service;

import dev.tintwym.home_mart_backend.entity.Listing;
import dev.tintwym.home_mart_backend.mapper.ApiJson;
import dev.tintwym.home_mart_backend.repository.OrderItemRepository;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ListingSoldService {

    private final OrderItemRepository orderItemRepository;

    public ListingSoldService(OrderItemRepository orderItemRepository) {
        this.orderItemRepository = orderItemRepository;
    }

    public boolean isSold(String listingId) {
        if (listingId == null || listingId.isBlank()) {
            return false;
        }
        return orderItemRepository.existsByListingIdAndOrder_StatusIn(
                listingId, OrderStatuses.SOLD_OR_HELD);
    }

    public Set<String> soldAmong(Collection<String> listingIds) {
        if (listingIds == null || listingIds.isEmpty()) {
            return Collections.emptySet();
        }
        return new HashSet<>(orderItemRepository.findListingIdsWithOrderStatusIn(
                listingIds, OrderStatuses.SOLD_OR_HELD));
    }

    public Map<String, Object> toSummaryJson(Listing listing) {
        if (listing == null) {
            return null;
        }
        return ApiJson.listingSummaryJson(listing, isSold(listing.getId()));
    }

    public List<Map<String, Object>> toSummaryJsonList(List<Listing> listings) {
        if (listings == null || listings.isEmpty()) {
            return List.of();
        }
        Set<String> sold = soldAmong(listings.stream().map(Listing::getId).toList());
        return listings.stream()
                .map(l -> ApiJson.listingSummaryJson(l, sold.contains(l.getId())))
                .toList();
    }
}
