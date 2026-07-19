package com.vandunxg.file_processing.auth.configuration;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.List;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.vandunxg.file_processing.auth.adapter.out.security.CredentialVersionJwtValidator;
import com.vandunxg.file_processing.auth.adapter.out.security.SessionAllowListJwtValidator;
import com.vandunxg.file_processing.auth.application.port.out.CredentialVersionCachePort;
import com.vandunxg.file_processing.auth.application.port.out.SessionRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Wires the RSA keypair used to sign and verify access tokens. When {@code
 * app.auth.jwt.private-key-pem-base64} and its matching public key entry are absent the config
 * generates an in-memory keypair at boot — safe only for dev / integration tests, and warned
 * loudly. Production must supply real PEMs via environment variables.
 */
@Configuration
@Slf4j(topic = "AUTH-JWT-CONFIG")
public class JwtConfiguration {

  @Bean
  KeyMaterial jwtKeyMaterial(AuthProperties authProperties) {
    String kidCandidate = authProperties.jwt().activeKid();
    final String activeKid =
        kidCandidate == null || kidCandidate.isBlank() ? "dev-generated" : kidCandidate;
    boolean hasPrivate =
        authProperties.jwt().privateKeyPemBase64() != null
            && !authProperties.jwt().privateKeyPemBase64().isBlank();
    boolean hasPublic =
        authProperties.jwt().publicKeys() != null
            && authProperties.jwt().publicKeys().stream()
                .anyMatch(pk -> activeKid.equals(pk.kid()) && pk.pemBase64() != null);
    if (hasPrivate && hasPublic) {
      RSAPrivateKey priv = readPrivateKey(authProperties.jwt().privateKeyPemBase64());
      RSAPublicKey pub = readPublicKey(publicKeyPem(authProperties, activeKid));
      return new KeyMaterial(priv, pub, activeKid);
    }
    log.warn(
        "[jwtKeyMaterial] generating an ephemeral RSA keypair — DO NOT USE IN PRODUCTION. "
            + "Set app.auth.jwt.private-key-pem-base64 and app.auth.jwt.public-keys.");
    try {
      KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      KeyPair pair = gen.generateKeyPair();
      return new KeyMaterial(
          (RSAPrivateKey) pair.getPrivate(), (RSAPublicKey) pair.getPublic(), activeKid);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to generate RSA keypair", e);
    }
  }

  @Bean
  JwtEncoder jwtEncoder(KeyMaterial keyMaterial) {
    RSAKey rsaKey =
        new RSAKey.Builder(keyMaterial.publicKey())
            .privateKey(keyMaterial.privateKey())
            .keyID(keyMaterial.kid())
            .build();
    JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(rsaKey));
    return new NimbusJwtEncoder(jwkSource);
  }

  @Bean
  JwtDecoder jwtDecoder(
      KeyMaterial keyMaterial,
      AuthProperties authProperties,
      SessionRepositoryPort sessionRepositoryPort,
      CredentialVersionCachePort credentialVersionCachePort,
      UserRepositoryPort userRepositoryPort,
      Clock clock) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keyMaterial.publicKey()).build();

    OAuth2TokenValidator<Jwt> defaults =
        JwtValidators.createDefaultWithIssuer(authProperties.jwt().issuer());
    OAuth2TokenValidator<Jwt> audienceValidator =
        new JwtClaimValidator<List<String>>(
            JwtClaimNames.AUD, aud -> aud != null && aud.contains(authProperties.jwt().audience()));
    OAuth2TokenValidator<Jwt> sessionAllowList =
        new SessionAllowListJwtValidator(sessionRepositoryPort, clock);
    OAuth2TokenValidator<Jwt> credentialVersion =
        new CredentialVersionJwtValidator(credentialVersionCachePort, userRepositoryPort);

    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            defaults, audienceValidator, sessionAllowList, credentialVersion));
    return decoder;
  }

  @Bean
  JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName("roles");
    authorities.setAuthorityPrefix("ROLE_");
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authorities);
    converter.setPrincipalClaimName("sub");
    return converter;
  }

  public record KeyMaterial(RSAPrivateKey privateKey, RSAPublicKey publicKey, String kid) {}

  private static String publicKeyPem(AuthProperties authProperties, String kid) {
    return authProperties.jwt().publicKeys().stream()
        .filter(pk -> kid.equals(pk.kid()))
        .findFirst()
        .map(AuthProperties.Jwt.PublicKey::pemBase64)
        .orElseThrow(
            () -> new IllegalStateException("No public key registered for active kid " + kid));
  }

  private static RSAPublicKey readPublicKey(String value) {
    try {
      byte[] der =
          decodeBase64EncodedPem(value, "-----BEGIN PUBLIC KEY-----", "-----END PUBLIC KEY-----");

      return (RSAPublicKey)
          KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to parse RSA public key", e);
    }
  }

  private static RSAPrivateKey readPrivateKey(String value) {
    try {
      byte[] der =
          decodeBase64EncodedPem(value, "-----BEGIN PRIVATE KEY-----", "-----END PRIVATE KEY-----");

      return (RSAPrivateKey)
          KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to parse RSA private key", e);
    }
  }

  private static byte[] decodeBase64EncodedPem(
      String envValue, String beginMarker, String endMarker) {
    if (envValue == null || envValue.isBlank()) {
      throw new IllegalArgumentException("Key environment value must not be blank");
    }

    String pem =
        new String(Base64.getMimeDecoder().decode(envValue.trim()), StandardCharsets.UTF_8).trim();

    if (!pem.contains(beginMarker) || !pem.contains(endMarker)) {
      throw new IllegalArgumentException(
          "Decoded value is not expected PEM format: " + beginMarker);
    }

    String derBase64 =
        pem.replace(beginMarker, "")
            .replace(endMarker, "")
            .replace("\r", "")
            .replace("\n", "")
            .replace(" ", "")
            .trim();

    return Base64.getMimeDecoder().decode(derBase64);
  }
}
