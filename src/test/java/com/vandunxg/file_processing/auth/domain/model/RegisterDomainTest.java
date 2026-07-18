package com.vandunxg.file_processing.auth.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.UUID;

import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import org.junit.jupiter.api.Test;

class RegisterDomainTest {

  private static final Instant NOW = Instant.parse("2026-07-17T10:15:30Z");
  private static final String TOKEN_HASH = "a".repeat(64);

  @Test
  void normalizesIdentityWithNfkcTrimAndRootLowercase() {
    assertThat(User.normalize("\u3000\uFF21DMIN\u3000")).isEqualTo("admin");
    assertThat(User.normalize("\u3000\uFF25XAMPLE@EXAMPLE.COM\u3000"))
        .isEqualTo("example@example.com");
  }

  @Test
  void registerCreatesPendingVerificationOperatorWithInitialCredentialVersion() {
    Role operatorRole = operatorRole();
    User user =
        User.register(
            "\u3000\uFF2Fperator\u3000",
            "\u3000\uFF2FPERATOR@EXAMPLE.COM\u3000",
            "\u3000  Dr\u3000  Jane\tDoe  \u3000",
            "hashed",
            operatorRole,
            NOW);

    assertThat(user.getId()).isNotNull();
    assertThat(user.getUsername()).isEqualTo("Operator");
    assertThat(user.getNormalizedUsername()).isEqualTo("operator");
    assertThat(user.getEmail()).isEqualTo("operator@example.com");
    assertThat(user.getNormalizedEmail()).isEqualTo("operator@example.com");
    assertThat(user.getDisplayName()).isEqualTo("Dr Jane Doe");
    assertThat(user.getPasswordHash()).isEqualTo("hashed");
    assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_VERIFY);
    assertThat(user.isPendingVerify()).isTrue();
    assertThat(user.isActive()).isFalse();
    assertThat(user.isMustChangePassword()).isFalse();
    assertThat(user.getCredentialVersion()).isEqualTo(1);
    assertThat(user.getRoles()).extracting(Role::getCode).containsExactly("OPERATOR");
    assertThat(user.getRoles()).extracting(Role::getId).containsExactly(operatorRole.getId());
  }

  @Test
  void verifyEmailActivatesPendingUserAndRecordsVerificationTime() {
    User user =
        User.register(
            "operator", "operator@example.com", "Operator", "hashed", operatorRole(), NOW);

    user.verifyEmail(NOW.plusSeconds(1));

    assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    assertThat(user.isActive()).isTrue();
    assertThat(user.isPendingVerify()).isFalse();
    assertThat(user.getEmailVerifiedAt()).isEqualTo(NOW.plusSeconds(1));
  }

  @Test
  void verifyEmailRejectsAlreadyVerifiedUser() {
    User user =
        User.register(
            "operator", "operator@example.com", "Operator", "hashed", operatorRole(), NOW);
    user.verifyEmail(NOW.plusSeconds(1));

    assertThatThrownBy(() -> user.verifyEmail(NOW.plusSeconds(2)))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.USER_ALREADY_VERIFIED);
  }

  @Test
  void registerRejectsRoleThatIsNotAnActiveOperator() {
    assertInvalidRole(null);
    assertInvalidRole(
        Role.builder().id(UUID.randomUUID()).code("ADMIN").status(ActiveStatus.ACTIVE).build());
    assertInvalidRole(
        Role.builder()
            .id(UUID.randomUUID())
            .code("OPERATOR")
            .status(ActiveStatus.INACTIVE)
            .build());
    assertInvalidRole(
        Role.builder()
            .id(UUID.randomUUID())
            .code("OPERATOR")
            .status(ActiveStatus.ACTIVE)
            .deletedAt(NOW)
            .build());
  }

  @Test
  void issueStoresOnlyTokenHash() {
    EmailVerificationToken token =
        EmailVerificationToken.issue(
            UUID.randomUUID(),
            UUID.randomUUID(),
            TOKEN_HASH,
            NOW,
            Duration.ofMinutes(15),
            "ip-hash");

    assertThat(token.getTokenHash()).isEqualTo(TOKEN_HASH);
    List<String> stringFieldNames =
        Arrays.stream(EmailVerificationToken.class.getDeclaredFields())
            .filter(field -> field.getType() == String.class)
            .map(Field::getName)
            .toList();
    assertThat(stringFieldNames).containsExactlyInAnyOrder("tokenHash", "ipAddressHash");
  }

  @Test
  void issueRejectsRawOrMalformedTokenHash() {
    assertThatThrownBy(
            () ->
                EmailVerificationToken.issue(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "raw-opaque-token",
                    NOW,
                    Duration.ofMinutes(15),
                    "ip-hash"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                EmailVerificationToken.issue(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "A".repeat(64),
                    NOW,
                    Duration.ofMinutes(15),
                    "ip-hash"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void issueRejectsMissingOrInvalidInternalPreconditions() {
    UUID id = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                EmailVerificationToken.issue(
                    null, userId, TOKEN_HASH, NOW, Duration.ofMinutes(15), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                EmailVerificationToken.issue(
                    id, null, TOKEN_HASH, NOW, Duration.ofMinutes(15), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                EmailVerificationToken.issue(
                    id, userId, TOKEN_HASH, null, Duration.ofMinutes(15), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> EmailVerificationToken.issue(id, userId, TOKEN_HASH, NOW, Duration.ZERO, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                EmailVerificationToken.issue(
                    id, userId, TOKEN_HASH, NOW, Duration.ofMinutes(15), " "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void consumeRejectsExpiredToken() {
    EmailVerificationToken token =
        EmailVerificationToken.issue(
            UUID.randomUUID(),
            UUID.randomUUID(),
            TOKEN_HASH,
            NOW,
            Duration.ofMinutes(15),
            "ip-hash");

    assertThatThrownBy(() -> token.consume(NOW.plus(Duration.ofMinutes(15))))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID);
  }

  @Test
  void consumeRejectsPreviouslyUsedToken() {
    EmailVerificationToken token =
        EmailVerificationToken.issue(
            UUID.randomUUID(),
            UUID.randomUUID(),
            TOKEN_HASH,
            NOW,
            Duration.ofMinutes(15),
            "ip-hash");
    token.consume(NOW.plusSeconds(1));

    assertThat(token.getUsedAt()).isEqualTo(NOW.plusSeconds(1));
    assertThatThrownBy(() -> token.consume(NOW.plusSeconds(2)))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID);
  }

  @Test
  void authErrorCodesAreUniqueAndHaveLocalizedMessages() {
    ResourceBundle english = ResourceBundle.getBundle("i18n.messages", Locale.ENGLISH);
    ResourceBundle vietnamese =
        ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));

    assertThat(Arrays.stream(AuthErrorCode.values()).map(AuthErrorCode::getCode).toList())
        .doesNotHaveDuplicates();
    assertThat(AuthErrorCode.USER_ALREADY_VERIFIED.getCode()).isEqualTo(40907);
    for (AuthErrorCode error : AuthErrorCode.values()) {
      assertThat(english.containsKey(error.getName())).isTrue();
      assertThat(vietnamese.containsKey(error.getName())).isTrue();
    }
  }

  private static Role operatorRole() {
    return Role.builder()
        .id(UUID.randomUUID())
        .code("OPERATOR")
        .status(ActiveStatus.ACTIVE)
        .build();
  }

  private static void assertInvalidRole(Role role) {
    assertThatThrownBy(
            () ->
                User.register("operator", "operator@example.com", "Operator", "hashed", role, NOW))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.INVALID_ROLE);
  }
}
