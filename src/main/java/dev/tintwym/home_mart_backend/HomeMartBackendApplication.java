package dev.tintwym.home_mart_backend;

import dev.tintwym.home_mart_backend.config.DatabaseUrlEnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class HomeMartBackendApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(HomeMartBackendApplication.class);
        DatabaseUrlEnvironmentPostProcessor.register(app);
        app.run(args);
    }
}
