package dev.tintwym.home_mart_backend.repository;

import dev.tintwym.home_mart_backend.entity.ListingImage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ListingImageRepository extends JpaRepository<ListingImage, Long> {

    List<ListingImage> findByListingIdOrderBySortOrderAsc(String listingId);

    void deleteByListingId(String listingId);
}
