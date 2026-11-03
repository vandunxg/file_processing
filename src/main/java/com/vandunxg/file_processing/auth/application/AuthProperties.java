package com.vandunxg.file_processing.auth.application;

import java.text.Normalizer;
import java.time.Duration;
import java.util.List;

import com.vandunxg.file_processing.auth.domain.policy.PasswordPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
    @Valid @NotNull Password password,
    @Valid @NotNull Register register,
    @Valid @NotNull Login login,
    @Valid @NotNull Refresh refresh,
    @Valid @NotNull Session session,
    @Valid @NotNull Bootstrap bootstrap,
    @Valid @NotNull Jwt jwt,
    @Valid @NotNull EmailVerification emailVerification,
    @Valid @NotNull PasswordReset passwordReset,
    @Valid @NotNull Cleanup cleanup,
    @Valid @NotNull Redis redis,
    @Valid @NotNull Amqp amqp) {

  @ConstructorBinding
  public AuthProperties {}

  public record Password(
      @NotBlank @Pattern(regexp = "bcrypt") String encoder,
      @Min(4) @Max(31) int bcryptCost,
      @Positive int minLength,
      @Positive int maxLength) {

    public Password {
      if (maxLength < minLength) {
        throw new IllegalArgumentException(
            "Password max length must not be smaller than min length");
      }
    }
  }

  public record Register(@Positive int maxAttemptsPerHour) {}

  /**
   * Every limit carries its own window. Baking "per hour" into the name and hard-coding an hour at
   * the call site made the refresh limit 60 per hour where the spec asks for 60 per minute, which
   * is 60x stricter and locks out everyone sharing an office IP.
   */
  public record Login(
      @Positive int ipMaxPerWindow,
      @NotNull @DurationMin(seconds = 0, inclusive = false) Duration ipWindow,
      @Positive int usernameMaxPerWindow,
      @NotNull @DurationMin(seconds = 0, inclusive = false) Duration usernameWindow,
      @Positive int refreshIpMaxPerWindow,
      @NotNull @DurationMin(seconds = 0, inclusive = false) Duration refreshIpWindow,
      @Positive int maxFailures,
      @NotNull @DurationMin(seconds = 0, inclusive = false) Duration failureWindow,
      @NotNull @DurationMin(seconds = 0, inclusive = false) Duration lockDuration) {}

  public record Refresh(
      @NotNull @DurationMin(seconds = 0, inclusive = false) Duration tokenTtl,
      boolean cookieSecure) {

    @ConstructorBinding
    public Refresh {}
  }

  public record Session(
      @NotNull @DurationMin(seconds = 0, inclusive = false) Duration credentialVersionCacheTtl) {}

  public record Bootstrap(@Valid @NotNull Admin admin) {

    public record Admin(
        boolean enabled, String username, String email, String password, String displayName) {

      private static final PasswordPolicy PASSWORD_POLICY = new PasswordPolicy();

      @AssertTrue(
          message = "Enabled bootstrap admin requires username, email, password, and display name")
      public boolean hasCredentialsWhenEnabled() {
        if (!enabled) {
          return true;
        }

        String normalizedUsername = normalize(username);
        String normalizedEmail = normalize(email);
        String normalizedDisplayName = normalizeDisplayName(displayName);
        return normalizedUsername.length() >= 3
            && normalizedUsername.length() <= 64
            && normalizedUsername.matches("[A-Za-z0-9._-]+")
            && normalizedEmail.length() <= 254
            && normalizedEmail.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
            && normalizedDisplayName.length() >= 2
            && normalizedDisplayName.length() <= 150
            && PASSWORD_POLICY.validate(password, normalizedUsername, normalizedEmail).valid();
      }

      private static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).trim();
      }

      private static String normalizeDisplayName(String value) {
        return normalize(value).replaceAll("\\s+", " ");
      }
    }
  }

  public record Jwt(
      @NotBlank String issuer,
      @NotBlank String audience,
      @NotNull @DurationMin(seconds = 0, inclusive = false) Duration accessTokenTtl,
      @NotNull @DurationMin(seconds = 0, inclusive = false) Duration passwordChangeTokenTtl,
      @NotNull @DurationMin(seconds = 0, inclusive = false) Duration clockSkew,
      @NotBlank String activeKid,
      @NotBlank String privateKeyPemBase64,
      @NotEmpty List<@Valid PublicKey> publicKeys) {

    public record PublicKey(@NotBlank String kid, @NotBlank String pemBase64) {}
  }

  public record EmailVerification(
      @NotNull @DurationMin(seconds = 0, inclusive = false) Duration tokenTtl,
      @NotBlank String baseUrl,
      @Positive int resendMaxAttemptsPerHour) {}

  public record PasswordReset(
      @NotNull @DurationMin(seconds = 0, inclusive = false) Duration tokenTtl,
      @NotBlank String baseUrl,
      @Positive int ipMaxAttemptsPerHour,
      @Positive int identifierMaxAttemptsPerWindow,
      @NotNull @DurationMin(seconds = 0, inclusive = false) Duration identifierWindow) {}

  public record Cleanup(
      @NotNull @DurationMin(seconds = 0, inclusive = false) Duration cadence,
      @Positive int batchSize) {

    public Cleanup {
      if (cadence == null || cadence.isZero() || cadence.isNegative() || batchSize < 1) {
        throw new IllegalArgumentException(
            "Cleanup cadence must be positive and batch size at least one");
      }
    }
  }

  public record Redis(
      @Valid @NotNull Throttle throttle,
      @Valid @NotNull EmailVerificationKeys emailVerification,
      @Valid @NotNull SessionKeys session,
      @Valid @NotNull RefreshKeys refresh,
      @Valid @NotNull CredentialVersionKeys credentialVersion,
      @Valid @NotNull UserSessionsKeys userSessions) {

    public record Throttle(
        @NotBlank String keyPrefix,
        @NotNull @DurationMin(seconds = 0, inclusive = false) Duration window) {}

    public record EmailVerificationKeys(
        @NotBlank String tokenKeyPrefix, @NotBlank String userKeyPrefix) {}

    public record SessionKeys(@NotBlank String keyPrefix) {}

    public record RefreshKeys(
        @NotBlank String keyPrefix,
        @NotBlank String usedKeyPrefix,
        @NotNull @DurationMin(seconds = 0, inclusive = false) Duration reuseDetectionWindow) {}

    public record CredentialVersionKeys(@NotBlank String keyPrefix) {}

    public record UserSessionsKeys(@NotBlank String keyPrefix) {}
  }

  public record Amqp(
      @NotBlank String exchange,
      @Valid @NotNull RoutingKey routingKey,
      @Valid @NotNull Queue queue) {

    public record RoutingKey(
        @NotBlank String actionLog,
        @NotBlank String auditLog,
        @NotBlank String verificationEmail,
        @NotBlank String sessionPersist,
        @NotBlank String sessionUpdate,
        @NotBlank String sessionRevoke) {}

    public record Queue(
        @NotBlank String actionLog,
        @NotBlank String auditLog,
        @NotBlank String verificationEmail,
        @NotBlank String sessionPersist,
        @NotBlank String sessionUpdate,
        @NotBlank String sessionRevoke) {}
  }
}
