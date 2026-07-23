package com.vandunxg.file_processing.auth.adapter.out.security;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.vandunxg.file_processing.auth.application.port.out.JwtIssuerPort;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-JWT-ISSUER")
public class NimbusJwtIssuerAdapter implements JwtIssuerPort {

  private final JwtEncoder jwtEncoder;
  private final AuthProperties authProperties;

  @Override
  public IssuedAccessToken issue(
      UUID userId,
      UUID sessionId,
      int credentialVersion,
      List<String> roles,
      List<String> permissions,
      Instant now) {
    Instant expiresAt = now.plus(authProperties.jwt().accessTokenTtl());
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(authProperties.jwt().issuer())
            .audience(List.of(authProperties.jwt().audience()))
            .subject(userId.toString())
            .claim("sid", sessionId.toString())
            .claim("cv", credentialVersion)
            .claim("typ", "access")
            .claim("roles", roles == null ? List.of() : roles)
            .claim("permissions", permissions == null ? List.of() : permissions)
            .issuedAt(now)
            .expiresAt(expiresAt)
            .id(sessionId + ":" + now.getEpochSecond())
            .build();
    JwsHeader header =
        JwsHeader.with(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256)
            .keyId(authProperties.jwt().activeKid())
            .build();
    Jwt jwt = jwtEncoder.encode(JwtEncoderParameters.from(header, claims));
    return new IssuedAccessToken(jwt.getTokenValue(), now, expiresAt);
  }

  @Override
  public IssuedPasswordChangeToken issuePasswordChange(
      UUID userId, int credentialVersion, Instant now) {
    Instant expiresAt = now.plus(authProperties.jwt().passwordChangeTokenTtl());
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(authProperties.jwt().issuer())
            .audience(List.of(authProperties.jwt().audience()))
            .subject(userId.toString())
            .claim("cv", credentialVersion)
            .claim("typ", "password_change")
            .issuedAt(now)
            .expiresAt(expiresAt)
            .id(UUID.randomUUID().toString())
            .build();
    JwsHeader header =
        JwsHeader.with(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256)
            .keyId(authProperties.jwt().activeKid())
            .build();
    Jwt jwt = jwtEncoder.encode(JwtEncoderParameters.from(header, claims));
    return new IssuedPasswordChangeToken(jwt.getTokenValue(), now, expiresAt);
  }
}
