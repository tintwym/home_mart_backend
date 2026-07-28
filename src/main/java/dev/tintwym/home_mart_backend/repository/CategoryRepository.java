package dev.tintwym.home_mart_backend.repository;

import dev.tintwym.home_mart_backend.entity.Category;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CategoryRepository extends JpaRepository<Category, String> {

    Optional<Category> findBySlug(String slug);

    List<Category> findAllByOrderByNameAsc();

    @Query("""
            SELECT DISTINCT c FROM Category c
            LEFT JOIN FETCH c.subcategories s
            ORDER BY c.name ASC
            """)
    List<Category> findAllWithSubcategoriesOrderByNameAsc();
}
