package com.vandunxg.file_processing.auth.infrastructure.security;

import java.time.Duration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Puts {@code Retry-After} on the response of a request that is about to be rejected for exceeding
 * a rate limit.
 *
 * <p>Written at the moment the limit trips, while the response is still uncommitted: by the time
 * the resulting exception reaches an exception handler the window that produced it is no longer
 * known, and the application service that raised it has no servlet to write to.
 */
@Component
@Slf4j(topic = "AUTH-THROTTLE")
public class RetryAfterHeader {

  public void set(Duration window) {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
      // Not serving an HTTP request — a scheduler or a test calling the throttle directly.
      return;
    }
    var response = servletAttributes.getResponse();
    if (response == null || response.isCommitted()) {
      return;
    }
    response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, window.getSeconds())));
  }
}
