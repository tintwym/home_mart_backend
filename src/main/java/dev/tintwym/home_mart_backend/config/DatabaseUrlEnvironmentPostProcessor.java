package dev.tintwym.home_mart_backend.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Parses {@code DATABASE_URL} (Neon / Render style) into Spring datasource
 * properties. Registered from {@code main} so it works inside the Boot fat jar
 * (META-INF/spring factories are lifted out of BOOT-INF/classes and are not
 * visible to {@code SpringFactoriesLoader}).
 */
public final class DatabaseUrlEnvironmentPostProcessor
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    public static final String PROPERTY_SOURCE_NAME = "databaseUrlProperties";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        apply(event.getEnvironment());
    }

    /** Apply DATABASE_URL → spring.datasource.* on the given environment. */
    public static void apply(ConfigurableEnvironment environment) {
        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;
        }
        String databaseUrl = firstNonBlank(
                environment.getProperty("DATABASE_URL"),
                System.getenv("DATABASE_URL"));
        if (databaseUrl == null || databaseUrl.isBlank()) {
            return;
        }
        try {
            Map<String, Object> props = parseDatabaseUrl(databaseUrl.trim());
            environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, props));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse DATABASE_URL: " + e.getMessage(), e);
        }
    }

    /** Register this listener on a {@link SpringApplication} before {@code run}. */
    public static void register(SpringApplication application) {
        application.addListeners(new DatabaseUrlEnvironmentPostProcessor());
    }

    static Map<String, Object> parseDatabaseUrl(String raw) throws Exception {
        String normalized = raw;
        if (normalized.startsWith("postgres://")) {
            normalized = "postgresql://" + normalized.substring("postgres://".length());
        }
        if (!normalized.startsWith("postgresql://") && !normalized.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("DATABASE_URL must be a postgresql URL");
        }

        if (normalized.startsWith("jdbc:")) {
            URI jdbcUri = URI.create(normalized.substring("jdbc:".length()));
            return fromUri(jdbcUri, normalized.contains("sslmode=") || normalized.contains("ssl="));
        }

        URI uri = URI.create(normalized);
        return fromUri(uri, false);
    }

    private static Map<String, Object> fromUri(URI uri, boolean forceSsl) {
        String userInfo = uri.getUserInfo();
        String username = null;
        String password = null;
        if (userInfo != null && !userInfo.isBlank()) {
            int colon = userInfo.indexOf(':');
            if (colon >= 0) {
                username = decode(userInfo.substring(0, colon));
                password = decode(userInfo.substring(colon + 1));
            } else {
                username = decode(userInfo);
                password = "";
            }
        }

        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 5432;
        String path = uri.getPath() == null ? "" : uri.getPath();
        String db = path.startsWith("/") ? path.substring(1) : path;
        if (db.contains("/")) {
            db = db.substring(0, db.indexOf('/'));
        }

        String query = uri.getQuery();
        boolean ssl = forceSsl;
        if (query != null) {
            for (String part : query.split("&")) {
                String[] kv = part.split("=", 2);
                if (kv.length == 2 && "sslmode".equalsIgnoreCase(kv[0])) {
                    String mode = kv[1].toLowerCase();
                    ssl = !(mode.equals("disable") || mode.equals("allow") || mode.equals("prefer"));
                }
            }
        }

        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://")
                .append(host)
                .append(':')
                .append(port)
                .append('/')
                .append(db);

        if (query != null && !query.isBlank()) {
            jdbc.append('?').append(query);
            if (ssl && !query.toLowerCase().contains("sslmode=")) {
                jdbc.append("&sslmode=require");
            }
        } else if (ssl) {
            jdbc.append("?sslmode=require");
        }

        Map<String, Object> props = new HashMap<>();
        props.put("spring.datasource.url", jdbc.toString());
        if (username != null) {
            props.put("spring.datasource.username", username);
        }
        if (password != null) {
            props.put("spring.datasource.password", password);
        }
        return props;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
