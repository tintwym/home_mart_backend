package dev.tintwym.home_mart_backend.service;

import dev.tintwym.home_mart_backend.entity.Category;
import dev.tintwym.home_mart_backend.entity.Listing;
import dev.tintwym.home_mart_backend.entity.Subcategory;
import dev.tintwym.home_mart_backend.entity.User;
import dev.tintwym.home_mart_backend.repository.CategoryRepository;
import dev.tintwym.home_mart_backend.repository.ListingRepository;
import dev.tintwym.home_mart_backend.repository.SubcategoryRepository;
import dev.tintwym.home_mart_backend.repository.UserRepository;
import dev.tintwym.home_mart_backend.utility.UlidService;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the category taxonomy and demo listings when the database is empty.
 * Port of Laravel's CategorySeeder + ListingSeeder. Idempotent.
 */
@Service
public class DataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private record TaxRow(String parent, String child, String slug) {
    }

    private record ProductRow(
            String sub, String title, String desc, String condition, BigDecimal price, String image) {
    }

    private static final List<TaxRow> TAXONOMY = List.of(
            new TaxRow("Furniture", "Sofas & armchairs", null),
            new TaxRow("Furniture", "Beds & mattresses", "beds-mattresses-furniture"),
            new TaxRow("Furniture", "Wardrobes", null),
            new TaxRow("Furniture", "Chests of drawers", null),
            new TaxRow("Furniture", "Tables & desks", null),
            new TaxRow("Furniture", "Chairs & stools", null),
            new TaxRow("Furniture", "TV & media furniture", null),
            new TaxRow("Furniture", "Bookcases & shelving units", null),
            new TaxRow("Furniture", "Cabinets & sideboards", null),
            new TaxRow("Furniture", "Room dividers", null),
            new TaxRow("Furniture", "Children's furniture", null),
            new TaxRow("Furniture", "Outdoor furniture", "patio-furniture"),
            new TaxRow("Storage & organization", "Shelving systems", null),
            new TaxRow("Storage & organization", "Boxes & baskets", null),
            new TaxRow("Storage & organization", "Clothing & shoe storage", null),
            new TaxRow("Storage & organization", "Hooks & rails", null),
            new TaxRow("Storage & organization", "Garage & utility storage", null),
            new TaxRow("Storage & organization", "Home office organization", null),
            new TaxRow("Beds & mattresses", "Bed frames", null),
            new TaxRow("Beds & mattresses", "Mattresses", null),
            new TaxRow("Beds & mattresses", "Bedding sets", null),
            new TaxRow("Beds & mattresses", "Pillows", null),
            new TaxRow("Beds & mattresses", "Duvets", null),
            new TaxRow("Beds & mattresses", "Bed linen", null),
            new TaxRow("Kitchen", "Kitchen cabinets & fronts", null),
            new TaxRow("Kitchen", "Kitchen appliances", null),
            new TaxRow("Kitchen", "Cookware", null),
            new TaxRow("Kitchen", "Tableware", null),
            new TaxRow("Kitchen", "Kitchen utensils", null),
            new TaxRow("Kitchen", "Food storage", null),
            new TaxRow("Kitchen", "Kitchen organization", null),
            new TaxRow("Kitchen", "Kitchen textiles", null),
            new TaxRow("Home textiles", "Curtains & blinds", null),
            new TaxRow("Home textiles", "Rugs", null),
            new TaxRow("Home textiles", "Cushions & throws", null),
            new TaxRow("Home textiles", "Towels", null),
            new TaxRow("Home textiles", "Bath textiles", null),
            new TaxRow("Home textiles", "Table linens", null),
            new TaxRow("Lighting", "Ceiling lights", null),
            new TaxRow("Lighting", "Floor lamps", null),
            new TaxRow("Lighting", "Table lamps", null),
            new TaxRow("Lighting", "Wall lamps", null),
            new TaxRow("Lighting", "Smart lighting", null),
            new TaxRow("Lighting", "Light bulbs & accessories", null),
            new TaxRow("Home décor", "Mirrors", null),
            new TaxRow("Home décor", "Frames & pictures", null),
            new TaxRow("Home décor", "Plants & plant pots", null),
            new TaxRow("Home décor", "Vases & decorative objects", null),
            new TaxRow("Home décor", "Candles & candleholders", null),
            new TaxRow("Home décor", "Clocks", null),
            new TaxRow("Bathroom", "Bathroom furniture", null),
            new TaxRow("Bathroom", "Shower & bath", null),
            new TaxRow("Bathroom", "Bathroom accessories", null),
            new TaxRow("Bathroom", "Towels & mats", null),
            new TaxRow("Bathroom", "Storage for bathroom", null),
            new TaxRow("Laundry & cleaning", "Laundry baskets & bags", null),
            new TaxRow("Laundry & cleaning", "Drying racks", null),
            new TaxRow("Laundry & cleaning", "Ironing", null),
            new TaxRow("Laundry & cleaning", "Cleaning tools", null),
            new TaxRow("Laundry & cleaning", "Waste sorting & bins", null),
            new TaxRow("Children & baby", "Baby furniture", null),
            new TaxRow("Children & baby", "Kids beds", null),
            new TaxRow("Children & baby", "Kids storage", null),
            new TaxRow("Children & baby", "Toys & play", null),
            new TaxRow("Children & baby", "Kids textiles", null),
            new TaxRow("Outdoor", "Outdoor furniture", null),
            new TaxRow("Outdoor", "Outdoor lighting", null),
            new TaxRow("Outdoor", "Gardening", null),
            new TaxRow("Outdoor", "Outdoor storage", null),
            new TaxRow("Electronics & accessories", "Charging & cables", null),
            new TaxRow("Electronics & accessories", "Speakers", null),
            new TaxRow("Electronics & accessories", "Smart home", null),
            new TaxRow("Tools & hardware", "Handles & knobs", null),
            new TaxRow("Tools & hardware", "Hinges & fittings", null),
            new TaxRow("Tools & hardware", "Screws & fixings", null),
            new TaxRow("Tools & hardware", "Wall mounting", null),
            new TaxRow("Pets", "Pet beds", null),
            new TaxRow("Pets", "Bowls & feeding", null),
            new TaxRow("Pets", "Pet accessories", null));

    private static final List<ProductRow> PRODUCTS = List.of(
            new ProductRow(
                    "sofas-armchairs",
                    "SÖDERHAMN 3-seat Sectional Sofa",
                    "Comfortable and spacious sectional sofa in Samsta dark grey. Covers are completely removable and machine-washable.",
                    "like_new",
                    new BigDecimal("799"),
                    "https://images.unsplash.com/photo-1555041469-a586c61ea9bc?auto=format&fit=crop&w=600&q=80"),
            new ProductRow(
                    "tables-desks",
                    "KULLABERG Solid Pine Study Desk",
                    "Industrial style rustic wood desk with black steel framing. Ideal for study, bedroom, or home office setup.",
                    "good",
                    new BigDecimal("149"),
                    "https://images.unsplash.com/photo-1577140917170-285929fb55b7?auto=format&fit=crop&w=600&q=80"),
            new ProductRow(
                    "chairs-stools",
                    "POÄNG Armchair, Birch Veneer",
                    "Layer-glued bent birch frame gives comfortable resilience. Cushion in knitted beige fabric.",
                    "good",
                    new BigDecimal("129"),
                    "https://images.unsplash.com/photo-1506439773649-6e0eb8cfb237?auto=format&fit=crop&w=600&q=80"),
            new ProductRow(
                    "boxes-baskets",
                    "Handmade Bamboo Laundry Basket",
                    "Beautiful natural wicker bamboo storage bin with internal white fabric liner. High quality local craft.",
                    "new",
                    new BigDecimal("39"),
                    "https://images.unsplash.com/photo-1595079676339-1534801ad6cf?auto=format&fit=crop&w=600&q=80"),
            new ProductRow(
                    "table-lamps",
                    "Minimalist Scandinavian Ceramic Table Lamp",
                    "Elegant warm-glow nightstand lamp with ceramic base and fabric shade. Bulb is included.",
                    "good",
                    new BigDecimal("45"),
                    "https://images.unsplash.com/photo-1507473885765-e6ed057f782c?auto=format&fit=crop&w=600&q=80"),
            new ProductRow(
                    "bed-frames",
                    "MALM Queen Bed Frame, White Oak",
                    "Clean-lined bed frame with veneer finish. Adjustable bed sides allow mattresses of different thicknesses.",
                    "like_new",
                    new BigDecimal("249"),
                    "https://images.unsplash.com/photo-1505693416388-ac5ce068fe85?auto=format&fit=crop&w=600&q=80"),
            new ProductRow(
                    "cookware",
                    "Stainless Steel 5-Piece Cookware Set",
                    "Durable stainless steel pots and pans with encapsulated bases for even heat distribution. Oven safe.",
                    "new",
                    new BigDecimal("89"),
                    "https://images.unsplash.com/photo-1584990347449-a2d4c2c044ba?auto=format&fit=crop&w=600&q=80"),
            new ProductRow(
                    "rugs",
                    "STOCKHOLM Handwoven Wool Rug",
                    "Hand-woven flatweave wool rug with a classic diamond pattern. Soft underfoot and very hard-wearing.",
                    "good",
                    new BigDecimal("199"),
                    "https://images.unsplash.com/photo-1600166898405-da9535204843?auto=format&fit=crop&w=600&q=80"),
            new ProductRow(
                    "mirrors",
                    "Round Brass-Frame Wall Mirror 80cm",
                    "Elegant round mirror with a slim brass-tone metal frame. Perfect above a console or in the hallway.",
                    "like_new",
                    new BigDecimal("75"),
                    "https://images.unsplash.com/photo-1618220179428-22790b461013?auto=format&fit=crop&w=600&q=80"),
            new ProductRow(
                    "ceiling-lights",
                    "Rattan Pendant Ceiling Light",
                    "Natural handwoven rattan shade that casts warm decorative light patterns. E27 socket, bulb not included.",
                    "new",
                    new BigDecimal("59"),
                    "https://images.unsplash.com/photo-1524484485831-a92ffc0de03f?auto=format&fit=crop&w=600&q=80"),
            new ProductRow(
                    "cushions-throws",
                    "Linen Cushion Cover Set (4 pcs)",
                    "Set of four stone-washed linen cushion covers in earthy tones. Hidden zippers, machine washable.",
                    "new",
                    new BigDecimal("35"),
                    "https://images.unsplash.com/photo-1584100936595-c0654b55a2e2?auto=format&fit=crop&w=600&q=80"),
            new ProductRow(
                    "shelving-systems",
                    "KALLAX Shelving Unit 4x2, Black-Brown",
                    "Versatile cube shelving that works standing or lying down. Great as a room divider or media storage.",
                    "good",
                    new BigDecimal("79"),
                    "https://images.unsplash.com/photo-1594620302200-9a762244a156?auto=format&fit=crop&w=600&q=80"));

    private final CategoryRepository categoryRepository;
    private final SubcategoryRepository subcategoryRepository;
    private final UserRepository userRepository;
    private final ListingRepository listingRepository;
    private final PasswordEncoder passwordEncoder;

    public DataSeeder(
            CategoryRepository categoryRepository,
            SubcategoryRepository subcategoryRepository,
            UserRepository userRepository,
            ListingRepository listingRepository,
            PasswordEncoder passwordEncoder) {
        this.categoryRepository = categoryRepository;
        this.subcategoryRepository = subcategoryRepository;
        this.userRepository = userRepository;
        this.listingRepository = listingRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public void seed() {
        seedCategories();
        seedListings();
        log.info("Data seed complete");
    }

    private void seedCategories() {
        if (categoryRepository.count() > 0) {
            return;
        }

        Map<String, Category> parents = new HashMap<>();
        Set<String> subSlugs = new HashSet<>();
        subcategoryRepository.findAll().forEach(s -> subSlugs.add(s.getSlug()));

        for (TaxRow row : TAXONOMY) {
            String parentSlug = slugify(row.parent());
            Category parent = parents.get(parentSlug);
            if (parent == null) {
                parent = new Category();
                parent.setId(UlidService.newUlid());
                parent.setName(row.parent());
                parent.setSlug(parentSlug);
                parent = categoryRepository.save(parent);
                parents.put(parentSlug, parent);
            }

            String childSlug = row.slug() != null ? row.slug() : slugify(row.child());
            if (!subSlugs.contains(childSlug)) {
                Subcategory sub = new Subcategory();
                sub.setId(UlidService.newUlid());
                sub.setCategoryId(parent.getId());
                sub.setName(row.child());
                sub.setSlug(childSlug);
                subcategoryRepository.save(sub);
                subSlugs.add(childSlug);
            }
        }
    }

    private void seedListings() {
        User store = userRepository.findByEmail("store@homemart.com").orElse(null);
        if (store == null) {
            store = new User();
            store.setId(UlidService.newUlid());
            store.setName("HomeMart Official");
            store.setEmail("store@homemart.com");
            store.setPassword(passwordEncoder.encode(
                    UUID.randomUUID().toString().replace("-", "")
                            + UUID.randomUUID().toString().replace("-", "")));
            store.setSellerType("business");
            store.setRegion("US");
            store = userRepository.save(store);
        }

        Map<String, String> subBySlug = new HashMap<>();
        for (Subcategory s : subcategoryRepository.findAll()) {
            subBySlug.put(s.getSlug(), s.getId());
        }

        Set<String> existingTitles = new HashSet<>();
        for (Listing existing : listingRepository.findByUserId(store.getId())) {
            existingTitles.add(existing.getTitle());
        }

        for (ProductRow product : PRODUCTS) {
            if (existingTitles.contains(product.title())) {
                continue;
            }
            String subId = subBySlug.get(product.sub());
            if (subId == null) {
                continue;
            }

            Listing listing = new Listing();
            listing.setId(UlidService.newUlid());
            listing.setUserId(store.getId());
            listing.setSubcategoryId(subId);
            listing.setTitle(product.title());
            listing.setDescription(product.desc());
            listing.setCondition(product.condition());
            listing.setPrice(product.price());
            listing.setImagePath(product.image());
            listingRepository.save(listing);
            existingTitles.add(product.title());
        }
    }

    /**
     * Mirrors Laravel Str::slug: transliterate, lowercase, non-alphanumerics → '-'.
     */
    public static String slugify(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.getType(ch) != Character.NON_SPACING_MARK) {
                sb.append(ch);
            }
        }
        String ascii = Normalizer.normalize(sb.toString(), Normalizer.Form.NFC).toLowerCase();

        StringBuilder slug = new StringBuilder(ascii.length());
        boolean pendingSpace = false;
        for (int i = 0; i < ascii.length(); i++) {
            char ch = ascii.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                if (pendingSpace && slug.length() > 0) {
                    slug.append('-');
                }
                pendingSpace = false;
                slug.append(ch);
            } else if (Character.isWhitespace(ch) || ch == '-' || ch == '_') {
                pendingSpace = true;
            }
        }
        String result = slug.toString();
        while (result.startsWith("-")) {
            result = result.substring(1);
        }
        while (result.endsWith("-")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
