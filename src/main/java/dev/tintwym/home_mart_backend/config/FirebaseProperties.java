package dev.tintwym.home_mart_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "firebase")
public class FirebaseProperties {

    /** Firebase project id (optional if present inside credentials JSON). */
    private String projectId = "";

    /**
     * Service account JSON as a single-line string.
     * Prefer this in cloud hosts; alternatively set GOOGLE_APPLICATION_CREDENTIALS.
     */
    private String credentialsJson = "";

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getCredentialsJson() {
        return credentialsJson;
    }

    public void setCredentialsJson(String credentialsJson) {
        this.credentialsJson = credentialsJson;
    }

    public boolean isConfigured() {
        return credentialsJson != null && !credentialsJson.isBlank();
    }
}
