package com.vandunxg.file_processing.auth.adapter.in.web;

import java.util.Map;

import com.nimbusds.jose.jwk.JWKSet;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/certificate/.well-known")
public class JwksController {

  private final JWKSet jwkSet;

  public JwksController(JWKSet jwkSet) {
    this.jwkSet = jwkSet;
  }

  @GetMapping(value = "/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
  public Map<String, Object> jwks() {
    return jwkSet.toPublicJWKSet().toJSONObject();
  }
}
