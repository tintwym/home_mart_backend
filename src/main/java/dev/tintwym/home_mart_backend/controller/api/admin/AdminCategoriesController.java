package dev.tintwym.home_mart_backend.controller.api.admin;

import dev.tintwym.home_mart_backend.entity.Category;
import dev.tintwym.home_mart_backend.entity.Subcategory;
import dev.tintwym.home_mart_backend.repository.CategoryRepository;
import dev.tintwym.home_mart_backend.repository.SubcategoryRepository;
import dev.tintwym.home_mart_backend.utility.ApiResponses;
import dev.tintwym.home_mart_backend.utility.UlidService;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/categories")
public class AdminCategoriesController {

    private final CategoryRepository categoryRepository;
    private final SubcategoryRepository subcategoryRepository;

    public AdminCategoriesController(
            CategoryRepository categoryRepository, SubcategoryRepository subcategoryRepository) {
        this.categoryRepository = categoryRepository;
        this.subcategoryRepository = subcategoryRepository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<?> index() {
        List<Category> categories = categoryRepository.findAllWithSubcategoriesOrderByNameAsc();
        List<Map<String, Object>> data = new ArrayList<>();
        for (Category cat : categories) {
            data.add(categoryJson(cat));
        }
        return ResponseEntity.ok(Map.of("data", data));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        String name = body.get("name") == null ? "" : String.valueOf(body.get("name")).trim();
        if (name.isBlank()) {
            return ApiResponses.unprocessable("Name is required.", Map.of("name", List.of("Required.")));
        }
        String slug = body.get("slug") == null || String.valueOf(body.get("slug")).isBlank()
                ? slugify(name)
                : slugify(String.valueOf(body.get("slug")));
        if (categoryRepository.findBySlug(slug).isPresent()) {
            return ApiResponses.unprocessable("Slug already exists.", Map.of("slug", List.of("Taken.")));
        }
        Category cat = new Category();
        cat.setId(UlidService.newUlid());
        cat.setName(name);
        cat.setSlug(slug);
        categoryRepository.save(cat);
        return ResponseEntity.status(HttpStatus.CREATED).body(categoryJson(cat));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Category cat = categoryRepository.findById(id).orElse(null);
        if (cat == null) {
            return ApiResponses.notFound("Category not found.");
        }
        if (body.containsKey("name") && body.get("name") != null) {
            cat.setName(String.valueOf(body.get("name")).trim());
        }
        if (body.containsKey("slug") && body.get("slug") != null) {
            String slug = slugify(String.valueOf(body.get("slug")));
            var existing = categoryRepository.findBySlug(slug);
            if (existing.isPresent() && !existing.get().getId().equals(cat.getId())) {
                return ApiResponses.unprocessable("Slug already exists.", Map.of("slug", List.of("Taken.")));
            }
            cat.setSlug(slug);
        }
        categoryRepository.save(cat);
        return ResponseEntity.ok(categoryJson(cat));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> destroy(@PathVariable String id) {
        Category cat = categoryRepository.findById(id).orElse(null);
        if (cat == null) {
            return ApiResponses.notFound("Category not found.");
        }
        List<Subcategory> subs = subcategoryRepository.findByCategoryId(id);
        subcategoryRepository.deleteAll(subs);
        categoryRepository.delete(cat);
        return ResponseEntity.ok(Map.of("message", "Category deleted."));
    }

    @PostMapping("/{id}/subcategories")
    @Transactional
    public ResponseEntity<?> createSubcategory(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Category cat = categoryRepository.findById(id).orElse(null);
        if (cat == null) {
            return ApiResponses.notFound("Category not found.");
        }
        String name = body.get("name") == null ? "" : String.valueOf(body.get("name")).trim();
        if (name.isBlank()) {
            return ApiResponses.unprocessable("Name is required.", Map.of("name", List.of("Required.")));
        }
        String slug = body.get("slug") == null || String.valueOf(body.get("slug")).isBlank()
                ? slugify(name)
                : slugify(String.valueOf(body.get("slug")));
        if (subcategoryRepository.findBySlug(slug).isPresent()) {
            return ApiResponses.unprocessable("Slug already exists.", Map.of("slug", List.of("Taken.")));
        }
        Subcategory sub = new Subcategory();
        sub.setId(UlidService.newUlid());
        sub.setCategoryId(cat.getId());
        sub.setName(name);
        sub.setSlug(slug);
        subcategoryRepository.save(sub);
        Map<String, Object> child = new LinkedHashMap<>();
        child.put("id", sub.getId());
        child.put("name", sub.getName());
        child.put("slug", sub.getSlug());
        child.put("category_id", cat.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(child);
    }

    private static Map<String, Object> categoryJson(Category cat) {
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
            subs.add(child);
        }
        row.put("subcategories", subs);
        return row;
    }

    private static String slugify(String input) {
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return normalized.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }
}
