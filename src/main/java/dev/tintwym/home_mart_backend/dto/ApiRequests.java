package dev.tintwym.home_mart_backend.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public final class ApiRequests {

    private ApiRequests() {
    }

    public record RegisterRequest(
            @NotBlank @Size(max = 255) String name,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 255) String password,
            String passwordConfirmation,
            String sellerType,
            String region) {
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {
    }

    public record FirebaseLoginRequest(
            @NotBlank @JsonAlias({"idToken", "id_token"}) String idToken,
            String region) {
    }

    public record UpdatePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 255) String password) {
    }

    public record ProfileUpdateRequest(
            @Size(max = 255) String name,
            @Email String email,
            @Size(max = 50) String phone,
            String address,
            String region,
            String sellerType) {
    }

    public record LocalPaymentRequest(
            @NotBlank @Size(max = 30) String type,
            @Size(max = 50) String identifier,
            Boolean makeDefault,
            Boolean isDefault) {
    }

    public record LocalPaymentDefaultRequest(
            @NotBlank String localPaymentMethodId) {
    }

    public record LocalPayRequest(
            @NotBlank String orderId,
            @Size(max = 30) String method,
            @Size(max = 50) String identifier,
            Boolean saveMethod) {
    }

    public record ListingRequest(
            @NotBlank String subcategoryId,
            @NotBlank @Size(max = 255) String title,
            @NotBlank String description,
            @NotBlank String condition,
            @NotNull @DecimalMin("0") @DecimalMax("99999999") BigDecimal price,
            @Size(max = 2048) String imageUrl,
            @Size(max = 255) String meetupLocation) {
    }

    public record ReviewRequest(
            @NotNull @Min(1) @Max(5) Integer rating,
            @Size(max = 2000) String comment) {
    }

    public record MessageRequest(
            @JsonAlias({"text", "content"}) @Size(max = 2000) String body) {
    }

    public record ForgotPasswordRequest(@NotBlank @Email String email) {
    }

    public record ResetPasswordRequest(
            @NotBlank String token,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 255) String password,
            String passwordConfirmation) {
    }

    public record TwoFactorConfirmRequest(@NotBlank String code) {
    }

    public record TwoFactorChallengeRequest(
            @NotBlank @Email String email,
            @NotBlank String password,
            String code,
            String recoveryCode) {
    }

    public record StripeSessionRequest(@NotBlank String sessionId) {
    }

    public record StripeOrderRequest(@NotBlank String orderId) {
    }

    public record StripeDefaultPaymentRequest(@NotBlank String paymentMethodId) {
    }

    public record PasskeyRegisterRequest(
            @NotBlank String state,
            @Size(max = 255) String name,
            @NotNull JsonNode credential) {
    }

    public record PasskeyAuthOptionsRequest(String email) {
    }

    public record PasskeyAuthenticateRequest(
            @NotBlank String state,
            @NotNull JsonNode credential) {
    }
}
