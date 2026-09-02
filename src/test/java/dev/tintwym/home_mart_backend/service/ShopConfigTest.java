package dev.tintwym.home_mart_backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ShopConfigTest {

    @Test
    void maxListingSlotsIncludesExtraSlots() {
        int base = ShopConfig.maxListingSlots("individual", 0);
        int withExtra = ShopConfig.maxListingSlots("individual", 3);
        assertEquals(base + 3, withExtra);
    }

    @Test
    void slotAndTrendPricesArePositive() {
        assertTrue(ShopConfig.SLOT_PRICE.compareTo(BigDecimal.ZERO) > 0);
        assertTrue(ShopConfig.TREND_PRICE.compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void myanmarPaymentMethodsIncludeMmqr() {
        assertTrue(ShopConfig.MYANMAR_PAYMENT_METHODS.contains("mmqr"));
    }
}
