package com.vandunxg.file_processing.auth.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.vandunxg.file_processing.auth.application.AuthProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AuthConfigurationTest {

  @Test
  void bindsACompleteValidConfiguration() {
    contextWith().run(context -> assertThat(context).hasNotFailed());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "app.auth.jwt.issuer=",
        "app.auth.jwt.private-key-pem-base64=",
        "app.auth.jwt.access-token-ttl=PT0S",
        "app.auth.jwt.clock-skew=",
        "app.auth.login.ip-max-per-window=0",
        "app.auth.redis.refresh.reuse-detection-window=PT0S",
        "app.auth.amqp.queue.audit-log="
      })
  void refusesInvalidRequiredAuthConfiguration(String override) {
    contextWith(override).run(context -> assertThat(context).hasFailed());
  }

  @Test
  void refusesPasswordBoundsInReverseOrder() {
    contextWith("app.auth.password.min-length=128", "app.auth.password.max-length=8")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void refusesUnsupportedPasswordEncoder() {
    contextWith("app.auth.password.encoder=argon2").run(context -> assertThat(context).hasFailed());
  }

  @ParameterizedTest
  @ValueSource(ints = {3, 32})
  void refusesBcryptCostOutsideSupportedRange(int bcryptCost) {
    contextWith("app.auth.password.bcrypt-cost=" + bcryptCost)
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void refusesMissingBootstrapCredentialsWhenEnabled() {
    contextWith("app.auth.bootstrap.admin.enabled=true", "app.auth.bootstrap.admin.username=")
        .run(context -> assertThat(context).hasFailed());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "app.auth.bootstrap.admin.username=invalid username",
        "app.auth.bootstrap.admin.email=not-an-email",
        "app.auth.bootstrap.admin.password=short",
        "app.auth.bootstrap.admin.display-name=A"
      })
  void refusesInvalidBootstrapCredentialsWhenEnabled(String override) {
    contextWith("app.auth.bootstrap.admin.enabled=true", override)
        .run(context -> assertThat(context).hasFailed());
  }

  @EnableConfigurationProperties(AuthProperties.class)
  static class BindingHarness {}

  private static ApplicationContextRunner contextWith(String... overrides) {
    return new ApplicationContextRunner()
        .withUserConfiguration(BindingHarness.class)
        .withPropertyValues(
            "app.auth.password.encoder=bcrypt",
            "app.auth.password.bcrypt-cost=10",
            "app.auth.password.min-length=8",
            "app.auth.password.max-length=128",
            "app.auth.register.max-attempts-per-hour=10",
            "app.auth.login.ip-max-per-window=20",
            "app.auth.login.ip-window=PT1H",
            "app.auth.login.username-max-per-window=5",
            "app.auth.login.username-window=PT15M",
            "app.auth.login.refresh-ip-max-per-window=60",
            "app.auth.login.refresh-ip-window=PT1M",
            "app.auth.login.max-failures=5",
            "app.auth.login.failure-window=PT15M",
            "app.auth.login.lock-duration=PT15M",
            "app.auth.refresh.token-ttl=PT168H",
            "app.auth.session.credential-version-cache-ttl=PT5M",
            "app.auth.bootstrap.admin.enabled=false",
            "app.auth.bootstrap.admin.username=system-admin",
            "app.auth.bootstrap.admin.email=admin@example.com",
            "app.auth.bootstrap.admin.password=a-secure-bootstrap-password",
            "app.auth.bootstrap.admin.display-name=System Administrator",
            "app.auth.jwt.issuer=file-processing",
            "app.auth.jwt.audience=file-processing-api",
            "app.auth.jwt.access-token-ttl=PT15M",
            "app.auth.jwt.password-change-token-ttl=PT5M",
            "app.auth.jwt.clock-skew=PT60S",
            "app.auth.jwt.active-kid=key-1",
            "app.auth.jwt.private-key-pem-base64=private-key",
            "app.auth.jwt.public-keys[0].kid=key-1",
            "app.auth.jwt.public-keys[0].pem-base64=public-key",
            "app.auth.email-verification.token-ttl=PT24H",
            "app.auth.email-verification.base-url=https://app.example.com/verify-email",
            "app.auth.email-verification.resend-max-attempts-per-hour=10",
            "app.auth.password-reset.token-ttl=PT15M",
            "app.auth.password-reset.base-url=https://app.example.com/reset-password",
            "app.auth.password-reset.ip-max-attempts-per-hour=20",
            "app.auth.password-reset.identifier-max-attempts-per-window=5",
            "app.auth.password-reset.identifier-window=PT15M",
            "app.auth.cleanup.cadence=PT1H",
            "app.auth.cleanup.batch-size=100",
            "app.auth.redis.throttle.key-prefix=auth:throttle:",
            "app.auth.redis.throttle.window=PT1H",
            "app.auth.redis.email-verification.token-key-prefix=auth:email-verify:token:",
            "app.auth.redis.email-verification.user-key-prefix=auth:email-verify:user:",
            "app.auth.redis.session.key-prefix=auth:session:",
            "app.auth.redis.refresh.key-prefix=auth:refresh:",
            "app.auth.redis.refresh.used-key-prefix=auth:refresh:used:",
            "app.auth.redis.refresh.reuse-detection-window=PT1M",
            "app.auth.redis.credential-version.key-prefix=auth:user:cv:",
            "app.auth.redis.user-sessions.key-prefix=auth:user:sessions:",
            "app.auth.amqp.exchange=auth.events",
            "app.auth.amqp.routing-key.action-log=auth.action-log.recorded",
            "app.auth.amqp.routing-key.audit-log=auth.audit-log.recorded",
            "app.auth.amqp.routing-key.verification-email=auth.email.verification-requested",
            "app.auth.amqp.routing-key.session-persist=auth.session.persist",
            "app.auth.amqp.routing-key.session-update=auth.session.update",
            "app.auth.amqp.routing-key.session-revoke=auth.session.revoke",
            "app.auth.amqp.queue.action-log=auth.action-log.queue",
            "app.auth.amqp.queue.audit-log=auth.audit-log.queue",
            "app.auth.amqp.queue.verification-email=auth.email-verification.queue",
            "app.auth.amqp.queue.session-persist=auth.session-persist.queue",
            "app.auth.amqp.queue.session-update=auth.session-update.queue",
            "app.auth.amqp.queue.session-revoke=auth.session-revoke.queue")
        .withPropertyValues(overrides);
  }
}
