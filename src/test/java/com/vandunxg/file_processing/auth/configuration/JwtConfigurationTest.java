package com.vandunxg.file_processing.auth.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jose.jwk.JWK;
import com.vandunxg.common.models.UserAuthentication;
import org.junit.jupiter.api.Test;

class JwtConfigurationTest {

  private final JwtConfiguration configuration = new JwtConfiguration();

  @Test
  void jwtKeyMaterial_rejectsMissingSigningKeys() {
    AuthProperties properties =
        new AuthProperties(
            null,
            null,
            null,
            null,
            null,
            new AuthProperties.Jwt(
                "issuer",
                "audience",
                Duration.ofMinutes(15),
                Duration.ofMinutes(5),
                Duration.ofSeconds(60),
                "current",
                null,
                List.of()),
            null,
            null,
            null);

    assertThatThrownBy(() -> configuration.jwtKeyMaterial(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("JWT signing keys are required");
  }

  @Test
  void jwtKeyMaterial_rejectsMismatchedActiveKeyPair() throws Exception {
    KeyPair signingKeyPair = keyPair();
    KeyPair verificationKeyPair = keyPair();
    AuthProperties properties =
        new AuthProperties(
            null,
            null,
            null,
            null,
            null,
            new AuthProperties.Jwt(
                "issuer",
                "audience",
                Duration.ofMinutes(15),
                Duration.ofMinutes(5),
                Duration.ofSeconds(60),
                "current",
                pemBase64(signingKeyPair, true),
                List.of(
                    new AuthProperties.Jwt.PublicKey(
                        "current", pemBase64(verificationKeyPair, false)))),
            null,
            null,
            null);

    assertThatThrownBy(() -> configuration.jwtKeyMaterial(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Active JWT private and public keys do not match");
  }

  @Test
  void jwtKeyMaterial_rejectsDuplicatePublicKeyIds() throws Exception {
    KeyPair signingKeyPair = keyPair();
    String publicKey = pemBase64(signingKeyPair, false);
    AuthProperties properties =
        new AuthProperties(
            null,
            null,
            null,
            null,
            null,
            new AuthProperties.Jwt(
                "issuer",
                "audience",
                Duration.ofMinutes(15),
                Duration.ofMinutes(5),
                Duration.ofSeconds(60),
                "current",
                pemBase64(signingKeyPair, true),
                List.of(
                    new AuthProperties.Jwt.PublicKey("current", publicKey),
                    new AuthProperties.Jwt.PublicKey("current", publicKey))),
            null,
            null,
            null);

    assertThatThrownBy(() -> configuration.jwtKeyMaterial(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("JWT public key ids must be unique");
  }

  @Test
  void jwtPublicJwkSet_includesEveryConfiguredPublicKey() throws Exception {
    KeyPair current = keyPair();
    KeyPair previous = keyPair();
    AuthProperties properties =
        new AuthProperties(
            null,
            null,
            null,
            null,
            null,
            new AuthProperties.Jwt(
                "issuer",
                "audience",
                Duration.ofMinutes(15),
                Duration.ofMinutes(5),
                Duration.ofSeconds(60),
                "current",
                pemBase64(current, true),
                List.of(
                    new AuthProperties.Jwt.PublicKey("current", pemBase64(current, false)),
                    new AuthProperties.Jwt.PublicKey("previous", pemBase64(previous, false)))),
            null,
            null,
            null);

    assertThat(configuration.jwtPublicJwkSet(configuration.jwtKeyMaterial(properties)).getKeys())
        .extracting(JWK::getKeyID)
        .containsExactly("current", "previous");
  }

  @Test
  void jwtAuthenticationConverter_mapsRolesAndPermissions() {
    Instant now = Instant.now();
    org.springframework.security.oauth2.jwt.Jwt jwt =
        new org.springframework.security.oauth2.jwt.Jwt(
            "token",
            now,
            now.plusSeconds(60),
            Map.of("alg", "RS256"),
            Map.of(
                "sub",
                UUID.randomUUID().toString(),
                "roles",
                List.of("OPERATOR"),
                "permissions",
                List.of("job:self_read")));

    assertThat(configuration.jwtAuthenticationConverter().convert(jwt))
        .isInstanceOf(UserAuthentication.class);
    assertThat(configuration.jwtAuthenticationConverter().convert(jwt).getAuthorities())
        .extracting(authority -> authority.getAuthority())
        .contains("ROLE_OPERATOR", "job:self_read");
  }

  private static KeyPair keyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  private static String pemBase64(KeyPair keyPair, boolean privateKey) {
    String label = privateKey ? "PRIVATE KEY" : "PUBLIC KEY";
    byte[] encoded =
        privateKey ? keyPair.getPrivate().getEncoded() : keyPair.getPublic().getEncoded();
    String pem =
        "-----BEGIN "
            + label
            + "-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(encoded)
            + "\n-----END "
            + label
            + "-----";
    return Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.UTF_8));
  }
}
