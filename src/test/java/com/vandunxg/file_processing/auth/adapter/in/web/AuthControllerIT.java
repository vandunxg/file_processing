package com.vandunxg.file_processing.auth.adapter.in.web;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.RegisterRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.ResendVerificationRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.VerifyEmailRequest;
import com.vandunxg.file_processing.auth.application.port.out.EmailSenderPort;
import com.vandunxg.file_processing.testsupport.PostgresIntegrationTest;
import com.vandunxg.file_processing.testsupport.PostgresTestContainerBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Covers the public register / verify-email / resend-verification HTTP contract end to end against
 * a real Postgres, Redis, and RabbitMQ. Each test method (other than the dedicated throttle test)
 * uses a distinct fake client IP via the {@code X-Real-IP} header so the shared Redis
 * sliding-window throttle counter ({@link
 * com.vandunxg.file_processing.auth.adapter.out.cache.RedisRegisterThrottleAdapter}) does not leak
 * attempts between unrelated test methods sharing the same Spring context.
 *
 * <p>{@code app.auth.register.max-attempts-per-hour} is lowered just for this test class (via
 * {@link TestPropertySource}, not the shared {@code application-test.yml}) so the 429 case can be
 * reached with a handful of requests instead of the production-matching default of 10.
 *
 * <p>Audit-log recording and verification-email sending now happen asynchronously via RabbitMQ
 * (publish in the HTTP request thread, consume on a separate listener container thread), so the
 * test that needs the captured verification link polls with Awaitility instead of asserting
 * immediately after the HTTP response returns.
 */
@PostgresIntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.auth.register.max-attempts-per-hour=3")
class AuthControllerIT extends PostgresTestContainerBase {

  private static final String BASE_URL = "/api/v1/auth";

  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
          .withExposedPorts(6379)
          .withReuse(true);

  private static final RabbitMQContainer RABBITMQ =
      new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management-alpine")).withReuse(true);

  static {
    REDIS.start();
    RABBITMQ.start();
  }

  @DynamicPropertySource
  static void infraProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
    registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
    registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
    registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private CapturingEmailSenderPort capturingEmailSenderPort;

  @Test
  void register_returns201WithPendingVerifyStatus_whenRequestValid() throws Exception {
    RegisterRequest request =
        registerRequest("reg-ok", "reg-ok@example.com", "Register Ok", "StrongPassw0rd!");

    mockMvc
        .perform(registerCall(request, "203.0.113.10"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.username").value("reg-ok"))
        .andExpect(jsonPath("$.data.email").value("reg-ok@example.com"))
        .andExpect(jsonPath("$.data.displayName").value("Register Ok"))
        .andExpect(jsonPath("$.data.status").value("PENDING_VERIFY"))
        .andExpect(jsonPath("$.data.id").isNotEmpty());
  }

  @Test
  void register_returns409_whenUsernameAlreadyExists() throws Exception {
    String ip = "203.0.113.11";
    RegisterRequest first =
        registerRequest("dup-user", "dup-user-1@example.com", "Dup User", "StrongPassw0rd!");
    mockMvc.perform(registerCall(first, ip)).andExpect(status().isCreated());

    RegisterRequest second =
        registerRequest("dup-user", "dup-user-2@example.com", "Dup User", "StrongPassw0rd!");
    mockMvc.perform(registerCall(second, ip)).andExpect(status().isConflict());
  }

  @Test
  void register_returns409_whenEmailAlreadyExists() throws Exception {
    String ip = "203.0.113.12";
    RegisterRequest first =
        registerRequest("dup-email-1", "dup-email@example.com", "Dup Email", "StrongPassw0rd!");
    mockMvc.perform(registerCall(first, ip)).andExpect(status().isCreated());

    RegisterRequest second =
        registerRequest("dup-email-2", "dup-email@example.com", "Dup Email", "StrongPassw0rd!");
    mockMvc.perform(registerCall(second, ip)).andExpect(status().isConflict());
  }

  @Test
  void register_returns400_whenPasswordViolatesPolicy() throws Exception {
    RegisterRequest request =
        registerRequest("weak-pass", "weak-pass@example.com", "Weak Pass", "short1");

    mockMvc.perform(registerCall(request, "203.0.113.13")).andExpect(status().isBadRequest());
  }

  @Test
  void register_returns429_afterExceedingConfiguredPerHourLimit() throws Exception {
    String ip = "203.0.113.14";

    for (int i = 0; i < 3; i++) {
      RegisterRequest request =
          registerRequest(
              "throttle-" + i,
              "throttle-" + i + "@example.com",
              "Throttle User",
              "StrongPassw0rd!");
      mockMvc.perform(registerCall(request, ip)).andExpect(status().isCreated());
    }

    RegisterRequest overLimit =
        registerRequest(
            "throttle-over", "throttle-over@example.com", "Throttle Over", "StrongPassw0rd!");
    mockMvc.perform(registerCall(overLimit, ip)).andExpect(status().isTooManyRequests());
  }

  @Test
  void verifyEmail_returns200WithActiveStatus_whenTokenValid() throws Exception {
    String ip = "203.0.113.15";
    RegisterRequest registerRequest =
        registerRequest("verify-ok", "verify-ok@example.com", "Verify Ok", "StrongPassw0rd!");
    mockMvc.perform(registerCall(registerRequest, ip)).andExpect(status().isCreated());

    await().atMost(Duration.ofSeconds(10)).until(capturingEmailSenderPort::hasVerificationLink);
    String rawToken = extractToken(capturingEmailSenderPort.lastVerificationLink());
    VerifyEmailRequest verifyEmailRequest = new VerifyEmailRequest();
    verifyEmailRequest.setToken(rawToken);

    mockMvc
        .perform(
            post(BASE_URL + "/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(verifyEmailRequest)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("ACTIVE"));
  }

  @Test
  void verifyEmail_returns400_whenTokenUnknown() throws Exception {
    VerifyEmailRequest verifyEmailRequest = new VerifyEmailRequest();
    verifyEmailRequest.setToken("plausible-but-unknown-token-1234567890abcdef");

    mockMvc
        .perform(
            post(BASE_URL + "/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(verifyEmailRequest)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void resendVerification_returns204_whenIdentifierUnknown() throws Exception {
    ResendVerificationRequest request = new ResendVerificationRequest();
    request.setIdentifier("no-such-account@example.com");

    mockMvc
        .perform(
            post(BASE_URL + "/resend-verification")
                .header("X-Real-IP", "203.0.113.16")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isNoContent());
  }

  @Test
  void resendVerification_returns204_whenIdentifierIsRealPendingAccount() throws Exception {
    String ip = "203.0.113.17";
    RegisterRequest registerRequest =
        registerRequest("resend-ok", "resend-ok@example.com", "Resend Ok", "StrongPassw0rd!");
    mockMvc.perform(registerCall(registerRequest, ip)).andExpect(status().isCreated());

    ResendVerificationRequest resendRequest = new ResendVerificationRequest();
    resendRequest.setIdentifier("resend-ok@example.com");

    mockMvc
        .perform(
            post(BASE_URL + "/resend-verification")
                .header("X-Real-IP", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(resendRequest)))
        .andExpect(status().isNoContent());
  }

  private MockHttpServletRequestBuilder registerCall(RegisterRequest request, String ip)
      throws Exception {
    return post(BASE_URL + "/register")
        .header("X-Real-IP", ip)
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(request));
  }

  private static RegisterRequest registerRequest(
      String username, String email, String displayName, String password) {
    RegisterRequest request = new RegisterRequest();
    request.setUsername(username);
    request.setEmail(email);
    request.setDisplayName(displayName);
    request.setPassword(password);
    return request;
  }

  private static String extractToken(String verificationLink) {
    String query = URI.create(verificationLink).getQuery();
    return Arrays.stream(query.split("&"))
        .filter(param -> param.startsWith("token="))
        .map(param -> param.substring("token=".length()))
        .findFirst()
        .orElseThrow(
            () -> new IllegalStateException("no token query parameter in " + verificationLink));
  }

  @TestConfiguration
  static class CapturingEmailSenderConfig {

    @Bean
    @Primary
    CapturingEmailSenderPort capturingEmailSenderPort() {
      return new CapturingEmailSenderPort();
    }
  }

  /**
   * Test-only {@link EmailSenderPort} double that captures the last verification link instead of
   * sending real email, so the IT can pull the raw token out of it (the controller never returns
   * the raw token, correctly, since it is a secret). Now invoked from {@code
   * VerificationEmailEventListener} rather than directly from the service, hence the {@code
   * hasVerificationLink} poll helper used by the caller.
   */
  static class CapturingEmailSenderPort implements EmailSenderPort {

    private final List<String> verificationLinks = new CopyOnWriteArrayList<>();

    @Override
    public void sendVerificationEmail(String toEmail, String displayName, String verificationLink) {
      verificationLinks.add(verificationLink);
    }

    boolean hasVerificationLink() {
      return !verificationLinks.isEmpty();
    }

    String lastVerificationLink() {
      return verificationLinks.get(verificationLinks.size() - 1);
    }
  }
}
