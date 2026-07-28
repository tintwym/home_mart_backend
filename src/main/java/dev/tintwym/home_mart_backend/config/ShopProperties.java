package dev.tintwym.home_mart_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shop")
public class ShopProperties {

    private String defaultRegion = "MM";
    private String defaultRegionPrivate = "US";
    private boolean gpsRegionEnabled = true;

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
}
