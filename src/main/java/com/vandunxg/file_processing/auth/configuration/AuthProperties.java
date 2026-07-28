package com.vandunxg.file_processing.auth.configuration;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
    Password password,
    Register register,
    Login login,
    Refresh refresh,
    Session session,
    Bootstrap bootstrap,
    Jwt jwt,
    EmailVerification emailVerification,
    PasswordReset passwordReset,
    Cleanup cleanup,
    Redis redis,
    Amqp amqp) {

  @ConstructorBinding
  public AuthProperties {}

  /**
   * Keeps existing programmatic construction disabled unless bootstrap is explicitly configured.
   */
  public AuthProperties(
      Password password,
      Register register,
      Login login,
      Refresh refresh,
      Session session,
      Jwt jwt,
      EmailVerification emailVerification,
      Redis redis,
      Amqp amqp) {
    this(
        password,
        register,
        login,
        refresh,
        session,
        new Bootstrap(new Bootstrap.Admin(false, "", "", "", "System Administrator")),
        jwt,
        emailVerification,
        new PasswordReset(
            Duration.ofMinutes(15),
            "https://app.example.com/reset-password",
            20,
            5,
            Duration.ofMinutes(15)),
        new Cleanup(Duration.ofHours(1), 100),
        redis,
        amqp);
  }

  public AuthProperties(
      Password password,
      Register register,
      Login login,
      Refresh refresh,
      Session session,
      Bootstrap bootstrap,
      Jwt jwt,
      EmailVerification emailVerification,
      Redis redis,
      Amqp amqp) {
    this(
        password,
        register,
        login,
        refresh,
        session,
        bootstrap,
        jwt,
        emailVerification,
        new PasswordReset(
            Duration.ofMinutes(15),
            "https://app.example.com/reset-password",
            20,
            5,
            Duration.ofMinutes(15)),
        new Cleanup(Duration.ofHours(1), 100),
        redis,
        amqp);
  }

  public record Password(String encoder, int bcryptCost, int minLength, int maxLength) {}

  public record Register(int maxAttemptsPerHour) {}

  public record Login(
      int ipMaxPerHour,
      int usernameMaxPerWindow,
      Duration usernameWindow,
      int refreshIpMaxPerHour,
      int maxFailures,
      Duration failureWindow,
      Duration lockDuration) {}

  public record Refresh(Duration tokenTtl, boolean cookieSecure) {

    @ConstructorBinding
    public Refresh {}

    public Refresh(Duration tokenTtl) {
      this(tokenTtl, true);
    }
  }

  public record Session(Duration credentialVersionCacheTtl) {}

  public record Bootstrap(Admin admin) {

    public record Admin(
        boolean enabled, String username, String email, String password, String displayName) {}
  }

  public record Jwt(
      String issuer,
      String audience,
      Duration accessTokenTtl,
      Duration passwordChangeTokenTtl,
      Duration clockSkew,
      String activeKid,
      String privateKeyPemBase64,
      List<PublicKey> publicKeys) {

    public record PublicKey(String kid, String pemBase64) {}
  }

  public record EmailVerification(
      Duration tokenTtl, String baseUrl, int resendMaxAttemptsPerHour) {}

  public record PasswordReset(
      Duration tokenTtl,
      String baseUrl,
      int ipMaxAttemptsPerHour,
      int identifierMaxAttemptsPerWindow,
      Duration identifierWindow) {}

  public record Cleanup(Duration cadence, int batchSize) {

    public Cleanup {
      if (cadence == null || cadence.isZero() || cadence.isNegative() || batchSize < 1) {
        throw new IllegalArgumentException(
            "Cleanup cadence must be positive and batch size at least one");
      }
    }
  }

  public record Redis(
      Throttle throttle,
      EmailVerificationKeys emailVerification,
      SessionKeys session,
      RefreshKeys refresh,
      CredentialVersionKeys credentialVersion,
      UserSessionsKeys userSessions) {

    public record Throttle(String keyPrefix, Duration window) {}

    public record EmailVerificationKeys(String tokenKeyPrefix, String userKeyPrefix) {}

    public record SessionKeys(String keyPrefix) {}

    public record RefreshKeys(
        String keyPrefix, String usedKeyPrefix, Duration reuseDetectionWindow) {}

    public record CredentialVersionKeys(String keyPrefix) {}

    public record UserSessionsKeys(String keyPrefix) {}
  }

  public record Amqp(String exchange, RoutingKey routingKey, Queue queue) {

    public record RoutingKey(
        String actionLog,
        String auditLog,
        String verificationEmail,
        String sessionPersist,
        String sessionUpdate,
        String sessionRevoke) {}

    public record Queue(
        String actionLog,
        String auditLog,
        String verificationEmail,
        String sessionPersist,
        String sessionUpdate,
        String sessionRevoke) {}
  }
}
