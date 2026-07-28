package dev.tintwym.home_mart_backend.service;

import dev.tintwym.home_mart_backend.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class StartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRunner.class);

    private final AppProperties appProperties;
    private final DataSeeder dataSeeder;

    public StartupRunner(AppProperties appProperties, DataSeeder dataSeeder) {
        this.appProperties = appProperties;
        this.dataSeeder = dataSeeder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (appProperties.getSeed().isOnStartup()) {
            try {
                log.info("Running data seeder (app.seed.on-startup=true)");
                dataSeeder.seed();
            } catch (Exception e) {
                throw new IllegalStateException("Data seed failed: " + e.getMessage(), e);
            }
        } else {
            log.info("Skipping data seeder (app.seed.on-startup=false)");
        }
    }
}
