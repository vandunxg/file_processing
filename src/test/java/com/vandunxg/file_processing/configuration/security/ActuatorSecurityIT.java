package com.vandunxg.file_processing.configuration.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vandunxg.file_processing.testsupport.AuthIntegrationTestBase;
import com.vandunxg.file_processing.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@PostgresIntegrationTest
@AutoConfigureMockMvc
class ActuatorSecurityIT extends AuthIntegrationTestBase {

  @Autowired private MockMvc mockMvc;

  @Test
  void permitsOnlyPublicProbesAndRequiresAllManageForPrometheus() throws Exception {
    mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    mockMvc.perform(get("/actuator/health")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            get("/actuator/prometheus")
                .with(jwt().authorities(new SimpleGrantedAuthority("user:self_read"))))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            get("/actuator/prometheus")
                .with(jwt().authorities(new SimpleGrantedAuthority("all:manage"))))
        .andExpect(status().isOk());
  }
}
