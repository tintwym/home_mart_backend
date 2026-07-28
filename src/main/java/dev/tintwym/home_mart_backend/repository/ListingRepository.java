package dev.tintwym.home_mart_backend.repository;

import dev.tintwym.home_mart_backend.entity.Listing;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ListingRepository extends JpaRepository<Listing, String> {

    List<Listing> findByUserId(String userId);

    List<Listing> findByUserIdOrderByCreatedAtDesc(String userId);

    List<Listing> findBySubcategoryId(String subcategoryId);

    List<Listing> findBySubcategoryIdOrderByCreatedAtDesc(String subcategoryId);

    long countByUserId(String userId);

    @Query("""
            SELECT DISTINCT l FROM Listing l
            LEFT JOIN FETCH l.user
            LEFT JOIN FETCH l.subcategory s
            LEFT JOIN FETCH s.category
            WHERE l.id = :id
            """)
    java.util.Optional<Listing> findDetailedById(@Param("id") String id);

    @Query("""
            SELECT DISTINCT l FROM Listing l
            LEFT JOIN FETCH l.user
            LEFT JOIN FETCH l.subcategory s
            LEFT JOIN FETCH s.category
            WHERE l.id IN :ids
            """)
    List<Listing> findDetailedByIdIn(@Param("ids") java.util.Collection<String> ids);

    @Query("""
            SELECT l FROM Listing l
            LEFT JOIN FETCH l.user
            LEFT JOIN FETCH l.subcategory
            WHERE l.id = :id
            """)
    Optional<Listing> findByIdWithRelations(@Param("id") String id);

    @Query("""
            SELECT l FROM Listing l
            LEFT JOIN FETCH l.user
            LEFT JOIN FETCH l.subcategory
            WHERE l.userId = :userId
            ORDER BY l.createdAt DESC
            """)
    List<Listing> findByUserIdWithRelations(@Param("userId") String userId);

    @Query("""
            SELECT l FROM Listing l
            WHERE (:q IS NULL OR :q = ''
                   OR LOWER(l.title) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(l.description) LIKE LOWER(CONCAT('%', :q, '%')))
              AND (:subcategoryId IS NULL OR :subcategoryId = '' OR l.subcategoryId = :subcategoryId)
              AND (:meetupLocation IS NULL OR :meetupLocation = ''
                   OR LOWER(l.meetupLocation) LIKE LOWER(CONCAT('%', :meetupLocation, '%')))
            ORDER BY CASE
                       WHEN l.trendingUntil IS NOT NULL AND l.trendingUntil > CURRENT_TIMESTAMP THEN 0
                       ELSE 1
                     END,
                     l.trendingUntil DESC NULLS LAST,
                     l.createdAt DESC
            """)
    List<Listing> search(
            @Param("q") String q,
            @Param("subcategoryId") String subcategoryId,
            @Param("meetupLocation") String meetupLocation);

    @Query("""
            SELECT l FROM Listing l
            JOIN l.subcategory s
            WHERE (:q IS NULL OR :q = ''
                   OR LOWER(l.title) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(l.description) LIKE LOWER(CONCAT('%', :q, '%')))
              AND (:categoryId IS NULL OR :categoryId = '' OR s.categoryId = :categoryId)
              AND (:subcategoryId IS NULL OR :subcategoryId = '' OR l.subcategoryId = :subcategoryId)
              AND (:meetupLocation IS NULL OR :meetupLocation = ''
                   OR LOWER(l.meetupLocation) LIKE LOWER(CONCAT('%', :meetupLocation, '%')))
            ORDER BY CASE
                       WHEN l.trendingUntil IS NOT NULL AND l.trendingUntil > CURRENT_TIMESTAMP THEN 0
                       ELSE 1
                     END,
                     l.trendingUntil DESC NULLS LAST,
                     l.createdAt DESC
            """)
    List<Listing> searchByCategory(
            @Param("q") String q,
            @Param("categoryId") String categoryId,
            @Param("subcategoryId") String subcategoryId,
            @Param("meetupLocation") String meetupLocation);
}
