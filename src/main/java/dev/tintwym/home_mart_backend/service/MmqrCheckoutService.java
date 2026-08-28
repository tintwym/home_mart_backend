package dev.tintwym.home_mart_backend.service;

import dev.tintwym.home_mart_backend.config.ShopProperties;
import dev.tintwym.home_mart_backend.entity.OrderEntity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class MmqrCheckoutService {

    private final ShopProperties shopProperties;

    public MmqrCheckoutService(ShopProperties shopProperties) {
        this.shopProperties = shopProperties;
    }

    public Map<String, Object> buildMmqrForOrder(OrderEntity order) {
        ShopProperties.Mmqr merchant = shopProperties.getMmqr();
        BigDecimal amountMmk = ShopConfig.convertFromUsd(
                order.getTotal() == null ? BigDecimal.ZERO : order.getTotal(), "MMK");
        amountMmk = amountMmk.setScale(0, RoundingMode.HALF_UP);

        String billNumber = order.getId();
        if (billNumber != null && billNumber.length() > 25) {
            billNumber = billNumber.substring(0, 25);
        }

        String payload = EmvQrService.buildMmqr(
                merchant.getGuid(),
                merchant.getMerchantId(),
                amountMmk,
                billNumber,
                "Home Mart order",
                merchant.getMerchantName(),
                merchant.getMerchantCity(),
                merchant.getMcc());

        Map<String, Object> mmqr = new LinkedHashMap<>();
        mmqr.put("payload", payload);
        mmqr.put("amount_mmk", amountMmk);
        mmqr.put("currency", "MMK");
        mmqr.put("merchant_name", merchant.getMerchantName());
        mmqr.put("merchant_id", merchant.getMerchantId());
        mmqr.put("merchant_city", merchant.getMerchantCity());
        mmqr.put("bill_number", billNumber);
        return mmqr;
    }
}
