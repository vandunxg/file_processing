package com.vandunxg.file_processing.auth.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

  private final PasswordPolicy policy = new PasswordPolicy();

  @Test
  void rejectsBlankPassword() {
    PasswordPolicy.ValidationResult result = policy.validate(" \t", "operator", "operator@example.com");

    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).isEqualTo(PasswordPolicy.Reason.BLANK);
  }

  @Test
  void rejectsPasswordShorterThanEightUnicodeCodePoints() {
    PasswordPolicy.ValidationResult result = policy.validate("seven77", "operator", "operator@example.com");

    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).isEqualTo(PasswordPolicy.Reason.TOO_SHORT);
  }

  @Test
  void acceptsEightSupplementaryUnicodeCodePoints() {
    PasswordPolicy.ValidationResult result =
        policy.validate("\uD83D\uDE00".repeat(8), "operator", "operator@example.com");

    assertThat(result.valid()).isTrue();
    assertThat(result.reason()).isNull();
  }

  @Test
  void rejectsPasswordLongerThanOneHundredTwentyEightUnicodeCodePoints() {
    PasswordPolicy.ValidationResult result =
        policy.validate("a".repeat(129), "operator", "operator@example.com");

    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).isEqualTo(PasswordPolicy.Reason.TOO_LONG);
  }

  @Test
  void rejectsPasswordEqualToNormalizedUsernameIgnoringCase() {
    PasswordPolicy.ValidationResult result = policy.validate("OPERATOR", "operator", "operator@example.com");

    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).isEqualTo(PasswordPolicy.Reason.MATCHES_USERNAME);
  }

  @Test
  void rejectsPasswordEqualToNormalizedEmailIgnoringCase() {
    PasswordPolicy.ValidationResult result =
        policy.validate("OPERATOR@EXAMPLE.COM", "operator", "operator@example.com");

    assertThat(result.valid()).isFalse();
    assertThat(result.reason()).isEqualTo(PasswordPolicy.Reason.MATCHES_EMAIL);
  }
}
