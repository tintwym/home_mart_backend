package dev.tintwym.home_mart_backend.controller.api;

import dev.tintwym.home_mart_backend.service.ShopConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shop")
public class ShopController {

    @GetMapping("/config")
    public ResponseEntity<?> config() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("regions", ShopConfig.REGIONS);
        body.put("region_labels", ShopConfig.REGION_LABELS);
        body.put("locales", ShopConfig.SUPPORTED_LOCALES);
        body.put("region_locales", ShopConfig.REGION_LOCALES);
        body.put("currencies", ShopConfig.CURRENCIES);
        body.put("exchange_rates", ShopConfig.EXCHANGE_RATES);
        body.put("locations", ShopConfig.REGION_LOCATIONS);
        body.put("trend_price", ShopConfig.TREND_PRICE);
        body.put("slot_price", ShopConfig.SLOT_PRICE);
        body.put("trend_duration_days", ShopConfig.TREND_DURATION_DAYS);
        body.put("myanmar_payment_methods", ShopConfig.MYANMAR_PAYMENT_METHODS);
        body.put("vietnam_payment_methods", ShopConfig.VIETNAM_PAYMENT_METHODS);
        return ResponseEntity.ok(body);
    }
}
