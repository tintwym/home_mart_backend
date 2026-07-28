package dev.tintwym.home_mart_backend.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path storage = Paths.get("storage").toAbsolutePath().normalize();
        String storageLocation = storage.toUri().toString();
        if (!storageLocation.endsWith("/")) {
            storageLocation = storageLocation + "/";
        }

        registry.addResourceHandler("/storage/**")
                .addResourceLocations(storageLocation, "classpath:/static/storage/");

        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(false);
    }
}
