package com.vandunxg.file_processing.auth.api;

import java.util.Map;

import com.nimbusds.jose.jwk.JWKSet;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/certificate/.well-known")
@Tag(name = "JWKS", description = "Public signing-key discovery")
public class JwksController {

  private final JWKSet jwkSet;

  public JwksController(JWKSet jwkSet) {
    this.jwkSet = jwkSet;
  }

  @Operation(
      summary = "Read public JWT signing keys",
      description = "Returns public RS256 key material only; no bearer token is required.")
  @SecurityRequirements
  @GetMapping(value = "/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
  public Map<String, Object> jwks() {
    return jwkSet.toPublicJWKSet().toJSONObject();
  }
}
