package dev.tintwym.home_mart_backend.config;

import dev.tintwym.home_mart_backend.filter.JwtAuthenticationFilter;
import dev.tintwym.home_mart_backend.filter.MapiRewriteFilter;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({
        AppProperties.class,
        JwtProperties.class,
        CorsProperties.class,
        ShopProperties.class,
        StripeProperties.class,
        CloudinaryProperties.class,
        MailAppProperties.class,
        FirebaseProperties.class
})
public class SecurityConfig {

    private final CorsProperties corsProperties;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final MapiRewriteFilter mapiRewriteFilter;

    public SecurityConfig(
            CorsProperties corsProperties,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            MapiRewriteFilter mapiRewriteFilter) {
        this.corsProperties = corsProperties;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.mapiRewriteFilter = mapiRewriteFilter;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/health", "/sanctum/**", "/storage/**").permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/register",
                                "/api/login",
                                "/api/auth/firebase",
                                "/api/forgot-password",
                                "/api/reset-password",
                                "/api/two-factor-challenge",
                                "/api/verify-email/**",
                                "/api/passkeys/**")
                        .permitAll()
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/listings",
                                "/api/listings/*",
                                "/api/categories/**",
                                "/api/shop/**",
                                "/api/users/**",
                                "/api/bootstrap",
                                "/api/detect-region",
                                "/api/verify-email/**",
                                "/api/passkeys/**")
                        .permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .addFilterBefore(mapiRewriteFilter, SecurityContextHolderFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        String raw = corsProperties.getAllowedOrigins() == null
                ? "*"
                : corsProperties.getAllowedOrigins().trim();

        List<String> expose = List.of(
                "X-Inertia",
                "X-Inertia-Location",
                "X-Inertia-Version");

        if ("*".equals(raw)) {
            config.setAllowedOriginPatterns(List.of("*"));
            // Browsers disallow credentials with wildcard origins.
            config.setAllowCredentials(false);
        } else {
            List<String> origins = Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            config.setAllowedOrigins(origins);
            config.setAllowCredentials(true);
        }

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(expose);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
