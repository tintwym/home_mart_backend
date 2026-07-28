package dev.tintwym.home_mart_backend.controller.api;

import dev.tintwym.home_mart_backend.entity.Category;
import dev.tintwym.home_mart_backend.entity.Subcategory;
import dev.tintwym.home_mart_backend.repository.CategoryRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/categories")
public class CategoriesController {

    private final CategoryRepository categoryRepository;

    public CategoriesController(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<?> index() {
        List<Category> categories = categoryRepository.findAllWithSubcategoriesOrderByNameAsc();
        List<Map<String, Object>> data = new ArrayList<>();
        for (Category cat : categories) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", cat.getId());
            row.put("name", cat.getName());
            row.put("slug", cat.getSlug());
            List<Map<String, Object>> subs = new ArrayList<>();
            List<Subcategory> children = cat.getSubcategories() == null
                    ? List.of()
                    : cat.getSubcategories().stream()
                            .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                            .toList();
            for (Subcategory sub : children) {
                Map<String, Object> child = new LinkedHashMap<>();
                child.put("id", sub.getId());
                child.put("name", sub.getName());
                child.put("slug", sub.getSlug());
                child.put("category_id", cat.getId());
                child.put("subcategory_id", sub.getId());
                subs.add(child);
            }
            row.put("subcategories", subs);
            data.add(row);
        }
        return ResponseEntity.ok(Map.of("data", data));
    }
}
