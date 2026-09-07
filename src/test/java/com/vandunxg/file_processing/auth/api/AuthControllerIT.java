package com.vandunxg.file_processing.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.vandunxg.file_processing.auth.api.dto.request.ForgotPasswordRequest;
import com.vandunxg.file_processing.auth.api.dto.request.LoginRequest;
import com.vandunxg.file_processing.auth.api.dto.request.RegisterRequest;
import com.vandunxg.file_processing.auth.api.dto.request.ResendVerificationRequest;
import com.vandunxg.file_processing.auth.api.dto.request.ResetPasswordRequest;
import com.vandunxg.file_processing.auth.api.dto.request.VerifyEmailRequest;
import com.vandunxg.file_processing.auth.application.capability.EmailSender;
import com.vandunxg.file_processing.auth.application.capability.PasswordHasher;
import com.vandunxg.file_processing.auth.domain.RoleRepository;
import com.vandunxg.file_processing.auth.domain.UserRepository;
import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.auth.domain.model.UserStatus;
import com.vandunxg.file_processing.testsupport.AuthIntegrationTestBase;
import com.vandunxg.file_processing.testsupport.PostgresIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * Covers the public register / verify-email / resend-verification HTTP contract end to end against
 * a real Postgres, Redis, and RabbitMQ. Each test method (other than the dedicated throttle test)
 * uses a distinct fake client IP via the {@code X-Real-IP} header so the shared Redis
 * sliding-window throttle counter ({@link
 * com.vandunxg.file_processing.auth.infrastructure.cache.RedisAuthThrottle}) does not leak attempts
 * between unrelated test methods sharing the same Spring context.
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
class AuthControllerIT extends AuthIntegrationTestBase {

  private static final String BASE_URL = "/api/v1/auth";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private CapturingEmailSender capturingEmailSender;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private PasswordHasher passwordHasher;

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
  void register_returns422_whenPasswordViolatesPolicy() throws Exception {
    RegisterRequest request =
        registerRequest("weak-pass", "weak-pass@example.com", "Weak Pass", "short1");

    mockMvc
        .perform(registerCall(request, "203.0.113.13"))
        .andExpect(status().isUnprocessableEntity());
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

    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> capturingEmailSender.hasVerificationLinkFor("verify-ok@example.com"));
    String rawToken =
        extractToken(capturingEmailSender.verificationLinkFor("verify-ok@example.com"));
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

  @Test
  void forgotPassword_returns404UserNotFound_whenIdentifierDoesNotExist() throws Exception {
    ForgotPasswordRequest request = new ForgotPasswordRequest();
    request.setIdentifier("missing-password-reset@example.com");

    mockMvc
        .perform(
            post(BASE_URL + "/forgot-password")
                .header("X-Real-IP", "203.0.113.18")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isNotFound());
  }

  @Test
  void forgotAndResetPassword_arePublicAndActivateAPendingUser() throws Exception {
    String email = "password-reset@example.com";
    mockMvc
        .perform(
            registerCall(
                registerRequest("password-reset", email, "Password Reset", "StrongPassw0rd!"),
                "203.0.113.19"))
        .andExpect(status().isCreated());

    ForgotPasswordRequest forgotRequest = new ForgotPasswordRequest();
    forgotRequest.setIdentifier(email);
    mockMvc
        .perform(
            post(BASE_URL + "/forgot-password")
                .header("X-Real-IP", "203.0.113.20")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(forgotRequest)))
        .andExpect(status().isNoContent());

    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> capturingEmailSender.hasPasswordResetLinkFor(email));
    ResetPasswordRequest resetRequest = new ResetPasswordRequest();
    resetRequest.setToken(extractToken(capturingEmailSender.passwordResetLinkFor(email)));
    resetRequest.setNewPassword("ResetStrongPassw0rd!");
    resetRequest.setConfirmPassword("ResetStrongPassw0rd!");

    mockMvc
        .perform(
            post(BASE_URL + "/reset-password")
                .header("X-Real-IP", "203.0.113.21")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(resetRequest)))
        .andExpect(status().isNoContent());
  }

  @Test
  void changePassword_requiresAnAccessToken() throws Exception {
    mockMvc
        .perform(
            post(BASE_URL + "/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"currentPassword\":\"CurrentStrongPassw0rd!\",\"newPassword\":\"NewStrongPassw0rd!\",\"confirmPassword\":\"NewStrongPassw0rd!\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void changePassword_acceptsANormalAccessToken() throws Exception {
    String username = "change-password";
    String email = "change-password@example.com";
    String currentPassword = "CurrentStrongPassw0rd!";
    String newPassword = "NewStrongPassw0rd!";
    mockMvc
        .perform(
            registerCall(
                registerRequest(username, email, "Change Password", currentPassword),
                "203.0.113.22"))
        .andExpect(status().isCreated());

    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> capturingEmailSender.hasVerificationLinkFor(email));
    VerifyEmailRequest verifyEmailRequest = new VerifyEmailRequest();
    verifyEmailRequest.setToken(extractToken(capturingEmailSender.verificationLinkFor(email)));
    mockMvc
        .perform(
            post(BASE_URL + "/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(verifyEmailRequest)))
        .andExpect(status().isOk());

    String accessToken = login(username, currentPassword, "203.0.113.23");
    mockMvc
        .perform(
            post(BASE_URL + "/change-password")
                .header("Authorization", "Bearer " + accessToken)
                .header("X-Real-IP", "203.0.113.24")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\",\"confirmPassword\":\"%s\"}"
                        .formatted(currentPassword, newPassword, newPassword)))
        .andExpect(status().isNoContent());
  }

  @Test
  void login_setsRefreshAndCsrfCookies_withoutReturningTheRefreshToken() throws Exception {
    String username = "cookie-login";
    String password = "CurrentStrongPassw0rd!";
    saveActiveUser(username, password, "Cookie Login");

    LoginRequest request = new LoginRequest();
    request.setUsername(username);
    request.setPassword(password);
    MvcResult result =
        mockMvc
            .perform(
                post(BASE_URL + "/login")
                    .header("X-Real-IP", "203.0.114.7")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
            .andReturn();

    Cookie refreshCookie = result.getResponse().getCookie("fps_refresh");
    assertThat(refreshCookie).isNotNull();
    assertThat(refreshCookie.isHttpOnly()).isTrue();
    assertThat(refreshCookie.getSecure()).isFalse();
    assertThat(refreshCookie.getPath()).isEqualTo(BASE_URL);
    assertThat(refreshCookie.getAttribute("SameSite")).isEqualTo("Strict");
    assertThat(refreshCookie.getMaxAge()).isPositive();

    Cookie csrfCookie = result.getResponse().getCookie("fps_csrf");
    assertThat(csrfCookie).isNotNull();
    assertThat(csrfCookie.isHttpOnly()).isFalse();
    assertThat(csrfCookie.getSecure()).isFalse();
    assertThat(csrfCookie.getPath()).isEqualTo(BASE_URL);
    assertThat(csrfCookie.getAttribute("SameSite")).isEqualTo("Strict");
    assertThat(csrfCookie.getMaxAge()).isPositive();
  }

  @Test
  void refresh_returnsCsrfTokenInvalid_whenHeaderDoesNotMatchCookie() throws Exception {
    mockMvc
        .perform(
            post(BASE_URL + "/refresh")
                .header("X-CSRF-Token", "wrong-token")
                .cookie(
                    new Cookie("fps_refresh", "refresh-token"),
                    new Cookie("fps_csrf", "csrf-token")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("AUTH_CSRF_TOKEN_INVALID"));
  }

  @Test
  void refresh_rotatesBothCookies_whenCsrfHeaderMatchesCookie() throws Exception {
    String username = "cookie-refresh";
    String password = "CurrentStrongPassw0rd!";
    saveActiveUser(username, password, "Cookie Refresh");

    LoginRequest request = new LoginRequest();
    request.setUsername(username);
    request.setPassword(password);
    MvcResult loginResult =
        mockMvc
            .perform(
                post(BASE_URL + "/login")
                    .header("X-Real-IP", "203.0.114.8")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andReturn();
    Cookie refreshCookie = loginResult.getResponse().getCookie("fps_refresh");
    Cookie csrfCookie = loginResult.getResponse().getCookie("fps_csrf");

    MvcResult refreshResult =
        mockMvc
            .perform(
                post(BASE_URL + "/refresh")
                    .header("X-CSRF-Token", csrfCookie.getValue())
                    .cookie(refreshCookie, csrfCookie)
                    .header("X-Real-IP", "203.0.114.8"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
            .andReturn();

    assertThat(refreshResult.getResponse().getCookie("fps_refresh").getValue())
        .isNotEqualTo(refreshCookie.getValue());
    assertThat(refreshResult.getResponse().getCookie("fps_csrf").getValue())
        .isNotEqualTo(csrfCookie.getValue());
  }

  @Test
  void logout_clearsRefreshAndCsrfCookies() throws Exception {
    String username = "cookie-logout";
    String password = "CurrentStrongPassw0rd!";
    saveActiveUser(username, password, "Cookie Logout");

    MvcResult result =
        mockMvc
            .perform(
                post(BASE_URL + "/logout")
                    .header("Authorization", "Bearer " + login(username, password, "203.0.114.9")))
            .andExpect(status().isNoContent())
            .andReturn();

    assertThat(result.getResponse().getCookie("fps_refresh").getMaxAge()).isZero();
    assertThat(result.getResponse().getCookie("fps_csrf").getMaxAge()).isZero();
  }

  @Test
  void revokeAll_clearsRefreshAndCsrfCookies() throws Exception {
    String username = "cookie-revoke-all";
    String password = "CurrentStrongPassw0rd!";
    UUID userId = saveActiveUser(username, password, "Cookie Revoke All");
    userRepository.replaceRoles(
        userId, Set.of(roleRepository.findByCode("OPERATOR").orElseThrow().getId()), Instant.now());

    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/me/sessions/revoke-all")
                    .header("Authorization", "Bearer " + login(username, password, "203.0.114.10")))
            .andExpect(status().isNoContent())
            .andReturn();

    assertThat(result.getResponse().getCookie("fps_refresh").getMaxAge()).isZero();
    assertThat(result.getResponse().getCookie("fps_csrf").getMaxAge()).isZero();
  }

  private UUID saveActiveUser(String username, String password, String displayName) {
    Instant now = Instant.now();
    UUID userId = UUID.randomUUID();
    userRepository.save(
        User.builder()
            .id(userId)
            .username(username)
            .normalizedUsername(username)
            .email(username + "@example.com")
            .normalizedEmail(username + "@example.com")
            .displayName(displayName)
            .passwordHash(passwordHasher.hash(password))
            .status(UserStatus.ACTIVE)
            .roles(Set.of())
            .mustChangePassword(false)
            .credentialVersion(1)
            .passwordChangedAt(now)
            .emailVerifiedAt(now)
            .build());
    return userId;
  }

  @Test
  void loginReturnsOnlyAPasswordChangeTokenWhenPasswordChangeIsRequired() throws Exception {
    String username = "force-password-change";
    String password = "CurrentStrongPassw0rd!";
    Instant now = Instant.now();
    userRepository.save(
        User.builder()
            .id(UUID.randomUUID())
            .username(username)
            .normalizedUsername(username)
            .email(username + "@example.com")
            .normalizedEmail(username + "@example.com")
            .displayName("Force Password Change")
            .passwordHash(passwordHasher.hash(password))
            .status(UserStatus.ACTIVE)
            .roles(Set.of())
            .mustChangePassword(true)
            .credentialVersion(1)
            .passwordChangedAt(now)
            .emailVerifiedAt(now)
            .build());

    LoginRequest request = new LoginRequest();
    request.setUsername(username);
    request.setPassword(password);

    MvcResult loginResult =
        mockMvc
            .perform(
                post(BASE_URL + "/login")
                    .header("X-Real-IP", "203.0.114.1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PASSWORD_CHANGE_REQUIRED"))
            .andExpect(jsonPath("$.data.passwordChangeToken").isNotEmpty())
            .andExpect(jsonPath("$.data.accessToken").doesNotExist())
            .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
            .andExpect(jsonPath("$.data.refreshExpiresIn").doesNotExist())
            .andExpect(jsonPath("$.data.sessionId").doesNotExist())
            .andReturn();

    String passwordChangeToken =
        objectMapper
            .readTree(loginResult.getResponse().getContentAsString())
            .path("data")
            .path("passwordChangeToken")
            .asText();
    mockMvc
        .perform(get(BASE_URL + "/me").header("Authorization", "Bearer " + passwordChangeToken))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void completePasswordChangeAcceptsAPasswordChangeToken() throws Exception {
    String username = "complete-password-change";
    String currentPassword = "CurrentStrongPassw0rd!";
    String newPassword = "NewStrongPassw0rd!";
    Instant now = Instant.now();
    userRepository.save(
        User.builder()
            .id(UUID.randomUUID())
            .username(username)
            .normalizedUsername(username)
            .email(username + "@example.com")
            .normalizedEmail(username + "@example.com")
            .displayName("Complete Password Change")
            .passwordHash(passwordHasher.hash(currentPassword))
            .status(UserStatus.ACTIVE)
            .roles(Set.of())
            .mustChangePassword(true)
            .credentialVersion(1)
            .passwordChangedAt(now)
            .emailVerifiedAt(now)
            .build());

    LoginRequest loginRequest = new LoginRequest();
    loginRequest.setUsername(username);
    loginRequest.setPassword(currentPassword);
    MvcResult loginResult =
        mockMvc
            .perform(
                post(BASE_URL + "/login")
                    .header("X-Real-IP", "203.0.114.2")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(loginRequest)))
            .andExpect(status().isOk())
            .andReturn();
    String passwordChangeToken =
        objectMapper
            .readTree(loginResult.getResponse().getContentAsString())
            .path("data")
            .path("passwordChangeToken")
            .asText();

    mockMvc
        .perform(
            post(BASE_URL + "/complete-password-change")
                .header("Authorization", "Bearer " + passwordChangeToken)
                .header("X-Real-IP", "203.0.114.3")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\",\"confirmPassword\":\"%s\"}"
                        .formatted(currentPassword, newPassword, newPassword)))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            post(BASE_URL + "/complete-password-change")
                .header("Authorization", "Bearer " + passwordChangeToken)
                .header("X-Real-IP", "203.0.114.3")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\",\"confirmPassword\":\"%s\"}"
                        .formatted(currentPassword, newPassword, newPassword)))
        .andExpect(status().isUnauthorized());

    assertThat(login(username, newPassword, "203.0.114.4")).isNotBlank();
  }

  @Test
  void completePasswordChangeRejectsANormalAccessToken() throws Exception {
    String username = "access-token-password-change";
    String currentPassword = "CurrentStrongPassw0rd!";
    Instant now = Instant.now();
    userRepository.save(
        User.builder()
            .id(UUID.randomUUID())
            .username(username)
            .normalizedUsername(username)
            .email(username + "@example.com")
            .normalizedEmail(username + "@example.com")
            .displayName("Access Token Password Change")
            .passwordHash(passwordHasher.hash(currentPassword))
            .status(UserStatus.ACTIVE)
            .roles(Set.of())
            .mustChangePassword(false)
            .credentialVersion(1)
            .passwordChangedAt(now)
            .emailVerifiedAt(now)
            .build());

    String accessToken = login(username, currentPassword, "203.0.114.5");

    mockMvc
        .perform(
            post(BASE_URL + "/complete-password-change")
                .header("Authorization", "Bearer " + accessToken)
                .header("X-Real-IP", "203.0.114.6")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"currentPassword\":\"%s\",\"newPassword\":\"NewStrongPassw0rd!\",\"confirmPassword\":\"NewStrongPassw0rd!\"}"
                        .formatted(currentPassword)))
        .andExpect(status().isUnauthorized());
  }

  private MockHttpServletRequestBuilder registerCall(RegisterRequest request, String ip)
      throws Exception {
    return post(BASE_URL + "/register")
        .header("X-Real-IP", ip)
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(request));
  }

  private String login(String username, String password, String ip) throws Exception {
    LoginRequest request = new LoginRequest();
    request.setUsername(username);
    request.setPassword(password);
    MvcResult result =
        mockMvc
            .perform(
                post(BASE_URL + "/login")
                    .header("X-Real-IP", ip)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper
        .readTree(result.getResponse().getContentAsString())
        .path("data")
        .path("accessToken")
        .asText();
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
    CapturingEmailSender capturingEmailSender() {
      return new CapturingEmailSender();
    }
  }

  /**
   * Test-only {@link EmailSender} double that captures verification links by recipient instead of
   * sending real email, so the IT can pull the raw token out of it (the controller never returns
   * the raw token, correctly, since it is a secret). Keyed by {@code toEmail} — not just the last
   * link received — because this bean is a singleton shared across every test method in this
   * class's cached Spring context, and several other tests register their own accounts (each
   * publishing their own verification-email event) before or after this one runs. Now invoked from
   * {@code VerificationEmailEventListener} rather than directly from the service, hence the {@code
   * hasVerificationLinkFor} poll helper used by the caller.
   */
  static class CapturingEmailSender implements EmailSender {

    private final Map<String, String> verificationLinksByEmail = new ConcurrentHashMap<>();
    private final Map<String, String> passwordResetLinksByEmail = new ConcurrentHashMap<>();

    @Override
    public void sendVerificationEmail(String toEmail, String displayName, String verificationLink) {
      verificationLinksByEmail.put(toEmail, verificationLink);
    }

    @Override
    public void sendPasswordResetEmail(String toEmail, String displayName, String resetLink) {
      passwordResetLinksByEmail.put(toEmail, resetLink);
    }

    boolean hasVerificationLinkFor(String email) {
      return verificationLinksByEmail.containsKey(email);
    }

    String verificationLinkFor(String email) {
      return verificationLinksByEmail.get(email);
    }

    boolean hasPasswordResetLinkFor(String email) {
      return passwordResetLinksByEmail.containsKey(email);
    }

    String passwordResetLinkFor(String email) {
      return passwordResetLinksByEmail.get(email);
    }
  }
}
