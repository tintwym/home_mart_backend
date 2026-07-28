package dev.tintwym.home_mart_backend.repository;

import dev.tintwym.home_mart_backend.entity.Subcategory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubcategoryRepository extends JpaRepository<Subcategory, String> {

    Optional<Subcategory> findBySlug(String slug);

    List<Subcategory> findByCategoryId(String categoryId);

    List<Subcategory> findByCategoryIdOrderByNameAsc(String categoryId);
}
