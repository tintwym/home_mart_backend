package dev.tintwym.home_mart_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@ConfigurationProperties(prefix = "shop")
public class ShopProperties {

    private String defaultRegion = "MM";
    private String defaultRegionPrivate = "US";
    private boolean gpsRegionEnabled = true;

    @NestedConfigurationProperty
    private final Mmqr mmqr = new Mmqr();

    @NestedConfigurationProperty
    private final Vnqr vnqr = new Vnqr();

    public String getDefaultRegion() {
        return defaultRegion;
    }

    public void setDefaultRegion(String defaultRegion) {
        this.defaultRegion = defaultRegion;
    }

    public String getDefaultRegionPrivate() {
        return defaultRegionPrivate;
    }

    public void setDefaultRegionPrivate(String defaultRegionPrivate) {
        this.defaultRegionPrivate = defaultRegionPrivate;
    }

    public boolean isGpsRegionEnabled() {
        return gpsRegionEnabled;
    }

    public void setGpsRegionEnabled(boolean gpsRegionEnabled) {
        this.gpsRegionEnabled = gpsRegionEnabled;
    }

    public Mmqr getMmqr() {
        return mmqr;
    }

    public Vnqr getVnqr() {
        return vnqr;
    }

    public static class Mmqr {
        /** EMV application GUID for MMQR merchant account (tag 26 / 00). */
        private String guid = "A0000006150001";
        private String merchantId = "HOMEMART001";
        private String merchantName = "Home Mart";
        private String merchantCity = "Yangon";
        private String mcc = "5411";

        public String getGuid() {
            return guid;
        }

        public void setGuid(String guid) {
            this.guid = guid;
        }

        public String getMerchantId() {
            return merchantId;
        }

        public void setMerchantId(String merchantId) {
            this.merchantId = merchantId;
        }

        public String getMerchantName() {
            return merchantName;
        }

        public void setMerchantName(String merchantName) {
            this.merchantName = merchantName;
        }

        public String getMerchantCity() {
            return merchantCity;
        }

        public void setMerchantCity(String merchantCity) {
            this.merchantCity = merchantCity;
        }

        public String getMcc() {
            return mcc;
        }

        public void setMcc(String mcc) {
            this.mcc = mcc;
        }
    }

    public static class Vnqr {
        /** NAPAS bank BIN (6 digits), e.g. 970422 = MB Bank. */
        private String bankBin = "970422";
        private String accountNumber = "0123456789";
        private String accountName = "HOME MART";
        private String merchantCity = "Hanoi";
        private String mcc = "5411";

        public String getBankBin() {
            return bankBin;
        }

        public void setBankBin(String bankBin) {
            this.bankBin = bankBin;
        }

        public String getAccountNumber() {
            return accountNumber;
        }

        public void setAccountNumber(String accountNumber) {
            this.accountNumber = accountNumber;
        }

        public String getAccountName() {
            return accountName;
        }

        public void setAccountName(String accountName) {
            this.accountName = accountName;
        }

        public String getMerchantCity() {
            return merchantCity;
        }

        public void setMerchantCity(String merchantCity) {
            this.merchantCity = merchantCity;
        }

        public String getMcc() {
            return mcc;
        }

        public void setMcc(String mcc) {
            this.mcc = mcc;
        }
    }
}
