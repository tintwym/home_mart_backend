package dev.tintwym.home_mart_backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "passkeys")
public class Passkey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "authenticatable_id", nullable = false, length = 26)
    private String authenticatableId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "authenticatable_id", insertable = false, updatable = false)
    private User authenticatable;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(name = "credential_id", nullable = false, columnDefinition = "text")
    private String credentialId;

    @Column(nullable = false, columnDefinition = "json")
    private String data;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAuthenticatableId() {
        return authenticatableId;
    }

    public void setAuthenticatableId(String authenticatableId) {
        this.authenticatableId = authenticatableId;
    }

    public User getAuthenticatable() {
        return authenticatable;
    }

    public void setAuthenticatable(User authenticatable) {
        this.authenticatable = authenticatable;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCredentialId() {
        return credentialId;
    }

    public void setCredentialId(String credentialId) {
        this.credentialId = credentialId;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
