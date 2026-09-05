package com.vandunxg.file_processing.testsupport;

import java.time.Duration;
import java.util.List;

import com.vandunxg.file_processing.auth.application.AuthProperties;

/**
 * Central test-side factory for {@link AuthProperties}. Every test that needs an {@code
 * AuthProperties} instance calls one of these helpers so that adding a new sub-record does not
 * force a diff across every service test.
 */
public final class AuthPropertiesFixture {

  private AuthPropertiesFixture() {}

  public static AuthProperties defaults() {
    return new AuthProperties(
        new AuthProperties.Password("bcrypt", 10, 8, 128),
        new AuthProperties.Register(5),
        new AuthProperties.Login(
            20, 5, Duration.ofMinutes(15), 60, 5, Duration.ofMinutes(15), Duration.ofMinutes(15)),
        new AuthProperties.Refresh(Duration.ofHours(168)),
        new AuthProperties.Session(Duration.ofMinutes(5)),
        new AuthProperties.Jwt(
            "test-issuer",
            "test-audience",
            Duration.ofMinutes(15),
            Duration.ofMinutes(5),
            Duration.ofSeconds(60),
            "test-kid",
            null,
            List.of()),
        new AuthProperties.EmailVerification(
            Duration.ofMinutes(15), "https://app.example.com/verify", 5),
        new AuthProperties.Redis(
            new AuthProperties.Redis.Throttle("test:throttle:", Duration.ofHours(1)),
            new AuthProperties.Redis.EmailVerificationKeys(
                "test:email-verify:token:", "test:email-verify:user:"),
            new AuthProperties.Redis.SessionKeys("test:session:"),
            new AuthProperties.Redis.RefreshKeys(
                "test:refresh:", "test:refresh:used:", Duration.ofSeconds(60)),
            new AuthProperties.Redis.CredentialVersionKeys("test:user:cv:"),
            new AuthProperties.Redis.UserSessionsKeys("test:user:sessions:")),
        new AuthProperties.Amqp(
            "test.auth.events",
            new AuthProperties.Amqp.RoutingKey(
                "test.action-log",
                "test.audit-log",
                "test.verification-email",
                "test.session.persist",
                "test.session.update",
                "test.session.revoke"),
            new AuthProperties.Amqp.Queue(
                "test.action-log.queue",
                "test.audit-log.queue",
                "test.verification-email.queue",
                "test.session-persist.queue",
                "test.session-update.queue",
                "test.session-revoke.queue")));
  }
}
