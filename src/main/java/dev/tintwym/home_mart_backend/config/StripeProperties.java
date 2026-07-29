package dev.tintwym.home_mart_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stripe")
public class StripeProperties {

    private String key = "";
    private String secret = "";
    /** Optional — required for webhook fulfillment when buyers close the browser. */
    private String webhookSecret = "";
    /**
     * When true (default), only Stripe test keys (pk_test_ / sk_test_) are accepted.
     * Live keys are refused so purchases stay test-only.
     */
    private boolean testModeOnly = true;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public boolean isTestModeOnly() {
        return testModeOnly;
    }

    public void setTestModeOnly(boolean testModeOnly) {
        this.testModeOnly = testModeOnly;
    }

    public boolean isConfigured() {
        if (secret == null || secret.isBlank()) {
            return false;
        }
        if (!testModeOnly) {
            return true;
        }
        return secret.startsWith("sk_test_")
                && (key == null || key.isBlank() || key.startsWith("pk_test_"));
    }

    public boolean hasWebhookSecret() {
        return webhookSecret != null && !webhookSecret.isBlank();
    }
}
