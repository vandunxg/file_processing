package com.vandunxg.file_processing.auth.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

import com.vandunxg.file_processing.auth.application.AuthProperties;
import com.vandunxg.file_processing.auth.application.capability.RefreshTokenGenerator;
import com.vandunxg.file_processing.configuration.web.ApiProperties;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The single definition of the refresh/CSRF cookie pair.
 *
 * <p>Both the writing and the clearing of these cookies live here on purpose. A browser only
 * removes a cookie when the deleting {@code Set-Cookie} repeats the same name <em>and path</em>, so
 * a controller that cleared them with its own hard-coded path would silently leave a stale cookie
 * behind the moment the two paths drifted apart — which is exactly what happened while {@code
 * CurrentUserSessionController} spelled the headers out by hand.
 */
@Component
@RequiredArgsConstructor
public class AuthCookies {

  /** Opaque refresh token. Never readable by JavaScript. */
  public static final String REFRESH_COOKIE = "fps_refresh";

  /** Double-submit CSRF token. Deliberately readable by JavaScript. */
  public static final String CSRF_COOKIE = "fps_csrf";

  public static final String CSRF_HEADER = "X-CSRF-Token";

  /**
   * Root path, unlike the refresh cookie.
   *
   * <p>A single-page app served from {@code /} has to read this value with {@code document.cookie}
   * to echo it back in {@code X-CSRF-Token}. {@code document.cookie} only exposes cookies whose
   * path is a prefix of the current document's path, so scoping this one to {@code /api/v1/auth}
   * made it invisible to every SPA route and turned each refresh into {@code 403
   * AUTH_CSRF_TOKEN_INVALID}.
   *
   * <p>Widening the path is safe: this cookie holds a random double-submit token, not a credential.
   * The credential is the refresh cookie, which stays HttpOnly and narrowly scoped.
   */
  private static final String CSRF_COOKIE_PATH = "/";

  /** CSRF cookies issued before path centralization must be expired to prevent duplicate values. */
  private static final String LEGACY_CSRF_COOKIE_PATH = "/api/v1/auth";

  private final RefreshTokenGenerator refreshTokenGenerator;
  private final AuthProperties authProperties;
  private final ApiProperties apiProperties;

  /** Issues a fresh cookie pair. A null refresh token means the caller issued no session. */
  public void write(HttpServletResponse response, @Nullable String refreshToken) {
    if (refreshToken == null) {
      return;
    }
    Duration ttl = authProperties.refresh().tokenTtl();
    addCookie(response, REFRESH_COOKIE, refreshToken, true, apiProperties.authPath(), ttl);
    expireLegacyCsrfCookie(response);
    addCookie(
        response, CSRF_COOKIE, refreshTokenGenerator.generate(), false, CSRF_COOKIE_PATH, ttl);
  }

  /** Expires both cookies, each on the path it was written to. */
  public void clear(HttpServletResponse response) {
    addCookie(response, REFRESH_COOKIE, "", true, apiProperties.authPath(), Duration.ZERO);
    addCookie(response, CSRF_COOKIE, "", false, CSRF_COOKIE_PATH, Duration.ZERO);
    expireLegacyCsrfCookie(response);
  }

  /** Constant-time comparison of the double-submit pair. */
  public boolean csrfMatches(@Nullable String cookieValue, @Nullable String headerValue) {
    return cookieValue != null
        && headerValue != null
        && MessageDigest.isEqual(
            cookieValue.getBytes(StandardCharsets.UTF_8),
            headerValue.getBytes(StandardCharsets.UTF_8));
  }

  private void addCookie(
      HttpServletResponse response,
      String name,
      String value,
      boolean httpOnly,
      String path,
      Duration maxAge) {
    response.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from(name, value)
            .httpOnly(httpOnly)
            .secure(authProperties.refresh().cookieSecure())
            .sameSite("Strict")
            .path(path)
            .maxAge(maxAge)
            .build()
            .toString());
  }

  private void expireLegacyCsrfCookie(HttpServletResponse response) {
    addCookie(response, CSRF_COOKIE, "", false, LEGACY_CSRF_COOKIE_PATH, Duration.ZERO);
  }
}
