package dev.tintwym.home_mart_backend.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

@Service
public class TranslationService {

    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);

    private final ObjectMapper objectMapper;
    private final Map<String, Map<String, Object>> cache = new ConcurrentHashMap<>();

    public TranslationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void loadAll() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:locales/*.json");
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null || !filename.endsWith(".json")) {
                    continue;
                }
                String locale = filename.substring(0, filename.length() - 5);
                try (InputStream in = resource.getInputStream()) {
                    Map<String, Object> map = objectMapper.readValue(in, new TypeReference<>() {
                    });
                    cache.put(locale, Collections.unmodifiableMap(new LinkedHashMap<>(map)));
                }
            }
            log.info("Loaded {} locale file(s)", cache.size());
        } catch (Exception e) {
            log.warn("Failed to load locale JSON files: {}", e.getMessage());
        }
    }

    public Map<String, Object> get(String locale) {
        if (locale == null || locale.isBlank()) {
            locale = "en";
        }
        Map<String, Object> map = cache.get(locale);
        if (map != null) {
            return map;
        }
        return cache.getOrDefault("en", Map.of());
    }

    public Map<String, Map<String, Object>> all() {
        return Collections.unmodifiableMap(cache);
    }
}
