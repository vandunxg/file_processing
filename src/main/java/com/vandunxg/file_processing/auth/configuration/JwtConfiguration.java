package com.vandunxg.file_processing.auth.configuration;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.vandunxg.file_processing.auth.adapter.out.security.CredentialVersionJwtValidator;
import com.vandunxg.file_processing.auth.adapter.out.security.SessionAllowListJwtValidator;
import com.vandunxg.file_processing.auth.application.port.out.CredentialVersionCachePort;
import com.vandunxg.file_processing.auth.application.port.out.SessionRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/** Wires the configured RSA keypair used to sign and verify access tokens. */
@Configuration
public class JwtConfiguration {

  @Bean
  KeyMaterial jwtKeyMaterial(AuthProperties authProperties) {
    validatePublicKeyIds(authProperties.jwt().publicKeys());
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
      if (!priv.getModulus().equals(pub.getModulus())) {
        throw new IllegalStateException("Active JWT private and public keys do not match");
      }
      List<JWK> publicKeys =
          authProperties.jwt().publicKeys().stream()
              .map(
                  key ->
                      (JWK)
                          new RSAKey.Builder(readPublicKey(key.pemBase64()))
                              .keyID(key.kid())
                              .build())
              .toList();
      return new KeyMaterial(priv, pub, activeKid, new JWKSet(publicKeys));
    }
    throw new IllegalStateException("JWT signing keys are required");
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
  JWKSet jwtPublicJwkSet(KeyMaterial keyMaterial) {
    return keyMaterial.publicJwkSet();
  }

  @Bean
  @Primary
  JwtDecoder jwtDecoder(
      KeyMaterial keyMaterial,
      AuthProperties authProperties,
      SessionRepositoryPort sessionRepositoryPort,
      CredentialVersionCachePort credentialVersionCachePort,
      UserRepositoryPort userRepositoryPort,
      Clock clock) {
    NimbusJwtDecoder decoder = newJwtDecoder(keyMaterial);

    OAuth2TokenValidator<Jwt> defaults =
        JwtValidators.createDefaultWithIssuer(authProperties.jwt().issuer());
    OAuth2TokenValidator<Jwt> audienceValidator =
        new JwtClaimValidator<List<String>>(
            JwtClaimNames.AUD, aud -> aud != null && aud.contains(authProperties.jwt().audience()));
    OAuth2TokenValidator<Jwt> typeValidator =
        new JwtClaimValidator<>("typ", type -> "access".equals(type));
    OAuth2TokenValidator<Jwt> sessionAllowList =
        new SessionAllowListJwtValidator(sessionRepositoryPort, clock);
    OAuth2TokenValidator<Jwt> credentialVersion =
        new CredentialVersionJwtValidator(credentialVersionCachePort, userRepositoryPort);

    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            defaults, audienceValidator, typeValidator, sessionAllowList, credentialVersion));
    return decoder;
  }

  @Bean("passwordChangeJwtDecoder")
  JwtDecoder passwordChangeJwtDecoder(
      KeyMaterial keyMaterial,
      AuthProperties authProperties,
      CredentialVersionCachePort credentialVersionCachePort,
      UserRepositoryPort userRepositoryPort) {
    NimbusJwtDecoder decoder = newJwtDecoder(keyMaterial);
    OAuth2TokenValidator<Jwt> defaults =
        JwtValidators.createDefaultWithIssuer(authProperties.jwt().issuer());
    OAuth2TokenValidator<Jwt> audienceValidator =
        new JwtClaimValidator<List<String>>(
            JwtClaimNames.AUD, aud -> aud != null && aud.contains(authProperties.jwt().audience()));
    OAuth2TokenValidator<Jwt> typeValidator =
        new JwtClaimValidator<>("typ", type -> "password_change".equals(type));
    OAuth2TokenValidator<Jwt> credentialVersion =
        new CredentialVersionJwtValidator(credentialVersionCachePort, userRepositoryPort);
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            defaults, audienceValidator, typeValidator, credentialVersion));
    return decoder;
  }

  @Bean
  JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
    roles.setAuthoritiesClaimName("roles");
    roles.setAuthorityPrefix("ROLE_");
    JwtGrantedAuthoritiesConverter permissions = new JwtGrantedAuthoritiesConverter();
    permissions.setAuthoritiesClaimName("permissions");
    permissions.setAuthorityPrefix("");
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(
        jwt -> {
          Collection<GrantedAuthority> authorities = new ArrayList<>(roles.convert(jwt));
          authorities.addAll(permissions.convert(jwt));
          return authorities;
        });
    converter.setPrincipalClaimName("sub");
    return converter;
  }

  public record KeyMaterial(
      RSAPrivateKey privateKey, RSAPublicKey publicKey, String kid, JWKSet publicJwkSet) {}

  private static NimbusJwtDecoder newJwtDecoder(KeyMaterial keyMaterial) {
    JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(keyMaterial.publicJwkSet());
    DefaultJWTProcessor<SecurityContext> jwtProcessor = new DefaultJWTProcessor<>();
    jwtProcessor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
    return new NimbusJwtDecoder(jwtProcessor);
  }

  private static String publicKeyPem(AuthProperties authProperties, String kid) {
    return authProperties.jwt().publicKeys().stream()
        .filter(pk -> kid.equals(pk.kid()))
        .findFirst()
        .map(AuthProperties.Jwt.PublicKey::pemBase64)
        .orElseThrow(
            () -> new IllegalStateException("No public key registered for active kid " + kid));
  }

  private static void validatePublicKeyIds(List<AuthProperties.Jwt.PublicKey> publicKeys) {
    if (publicKeys == null) {
      return;
    }
    Set<String> kids = new HashSet<>();
    if (publicKeys.stream()
        .anyMatch(key -> key.kid() == null || key.kid().isBlank() || !kids.add(key.kid()))) {
      throw new IllegalStateException("JWT public key ids must be unique");
    }
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
