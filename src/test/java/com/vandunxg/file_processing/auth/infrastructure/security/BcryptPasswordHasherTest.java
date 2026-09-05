package com.vandunxg.file_processing.auth.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.vandunxg.file_processing.auth.application.AuthProperties;
import com.vandunxg.file_processing.auth.application.capability.PasswordHasher;
import org.junit.jupiter.api.Test;

class BcryptPasswordHasherTest {

  private final PasswordHasher hasher = hasherAtCost(10);

  private static PasswordHasher hasherAtCost(int cost) {
    return new BcryptPasswordHasher(
        new AuthProperties(
            new AuthProperties.Password("bcrypt", cost, 8, 128),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));
  }

  @Test
  void aHashProducedAtTheConfiguredCostDoesNotNeedRehashing() {
    assertThat(hasher.needsRehash(hasher.hash("correct horse battery"))).isFalse();
  }

  @Test
  void aHashProducedAtAWeakerCostNeedsRehashing() {
    String weaker = hasherAtCost(4).hash("correct horse battery");

    assertThat(hasher.needsRehash(weaker)).isTrue();
  }

  @Test
  void anUnrecognisedEncodingNeedsRehashing() {
    // A scheme we cannot read the cost from is not evidence that it is strong enough.
    assertThat(hasher.needsRehash("{noop}plaintext")).isTrue();
  }

  @Test
  void theDummyHashIsStableAndMatchesNoPassword() {
    assertThat(hasher.dummyHash()).isEqualTo(hasher.dummyHash()).startsWith("{bcrypt}$2");
    assertThat(hasher.matches("correct horse battery", hasher.dummyHash())).isFalse();
  }
}
