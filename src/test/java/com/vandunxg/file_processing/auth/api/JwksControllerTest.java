package com.vandunxg.file_processing.auth.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.Test;

class JwksControllerTest {

  @Test
  void jwks_exposesOnlyPublicKeyMaterial() throws Exception {
    RSAKey signingKey = new RSAKeyGenerator(2048).keyID("current").generate();

    Map<String, Object> response = new JwksController(new JWKSet(signingKey)).jwks();

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> keys = (List<Map<String, Object>>) response.get("keys");
    assertThat(keys).hasSize(1);
    assertThat(keys.getFirst()).containsKeys("kid", "kty", "n", "e").doesNotContainKey("d");
  }
}
