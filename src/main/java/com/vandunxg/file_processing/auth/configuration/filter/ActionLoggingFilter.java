package com.vandunxg.file_processing.auth.configuration.filter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vandunxg.common.utils.Constants;
import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.common.utils.StrUtils;
import com.vandunxg.common.utils.StringPool;
import com.vandunxg.common.web.support.CachedHttpServletRequestWrapper;
import com.vandunxg.common.web.support.SecurityUtils;
import com.vandunxg.file_processing.auth.application.port.out.ActionLogEventPublisherPort;
import com.vandunxg.file_processing.auth.domain.model.ActionLog;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

@Component
@Order(101)
@WebFilter("/api/**")
@NullMarked
@Slf4j(topic = "ACTION-LOGGING-FILTER")
@RequiredArgsConstructor
public class ActionLoggingFilter extends OncePerRequestFilter {

  private static final List<String> BLACKLIST =
      List.of(
          "\\/api\\/certificate\\/.well-known\\/jwks\\.json",
          ".*\\/actuator\\/.*",
          ".*\\/(audit|action)-logs.*",
          "/swagger-ui.*",
          "/swagger-resources.*",
          "/v2/api-docs.*",
          ".*\\/integrations\\/files\\/upload",
          "/api/authenticate");

  private static final List<String> BLACKLIST_MIME_TYPE =
      List.of("multipart\\/form-data.*", "image\\/.*", "application\\/octet-stream.*");

  private static final List<String> MASK_FIELD_NAME =
      List.of(
          "password", "currentPassword", "newPassword", "clientSecret", "token", "refreshToken");

  private static final String MASKED_VALUE = "********";

  private final ActionLogEventPublisherPort actionLogEventPublisherPort;

  @Override
  protected void doFilterInternal(
      HttpServletRequest servletRequest,
      HttpServletResponse servletResponse,
      FilterChain filterChain)
      throws IOException, ServletException {

    Instant start = Instant.now();

    // check request content type
    HttpServletRequest httpServletRequest = servletRequest;
    String requestContentType = httpServletRequest.getHeader(HttpHeaders.CONTENT_TYPE);

    boolean ignoredRequestBody =
        Objects.nonNull(requestContentType)
            && BLACKLIST_MIME_TYPE.stream().anyMatch(requestContentType::matches);

    ContentCachingResponseWrapper cachedResponse =
        new ContentCachingResponseWrapper(servletResponse);
    cachedResponse.setCharacterEncoding(StringPool.UTF8);

    if (!ignoredRequestBody) {
      httpServletRequest = new CachedHttpServletRequestWrapper(servletRequest);
    }

    String remoteIp = this.getRemoteIp(httpServletRequest);

    RequestAttributes request = RequestContextHolder.getRequestAttributes();
    if (Objects.nonNull(request)) {
      request.setAttribute(Constants.REMOTE_IP, remoteIp, RequestAttributes.SCOPE_REQUEST);
    }

    try {
      filterChain.doFilter(httpServletRequest, cachedResponse);
    } finally {
      cachedResponse.copyBodyToResponse();

      if (cachedResponse.getStatus() >= 400) {
        Instant finishRequest = Instant.now();

        if (shouldFilter(servletRequest)) {
          recordActionLog(
              servletRequest,
              httpServletRequest,
              request,
              remoteIp,
              start,
              finishRequest,
              cachedResponse.getStatus(),
              ignoredRequestBody);
        }
      }
    }
  }

  private void recordActionLog(
      HttpServletRequest servletRequest,
      HttpServletRequest httpServletRequest,
      RequestAttributes request,
      String remoteIp,
      Instant start,
      Instant finishRequest,
      int status,
      boolean ignoredRequestBody) {

    String body = StrUtils.EMPTY;
    if (httpServletRequest instanceof CachedHttpServletRequestWrapper cachedRequest
        && !ignoredRequestBody) {
      body = cachedRequest.getBody();
    }

    try {
      actionLogEventPublisherPort.publish(
          ActionLog.builder()
              .id(IdUtils.nextId())
              .userId(SecurityUtils.getCurrentUserLoginId().orElse(null))
              .username(authenticatedUsername(servletRequest))
              .startTime(start)
              .endTime(finishRequest)
              .duration(Duration.between(start, finishRequest).toMillis())
              .path(servletRequest.getServletPath())
              .apiDoc(requestAttribute(request, Constants.API_DOC))
              .requestMethod(servletRequest.getMethod())
              .ipAddress(remoteIp)
              .userAgent(servletRequest.getHeader(HttpHeaders.USER_AGENT))
              .requestData(replaceRequestBody(body))
              .requestParam(getRequestParams(servletRequest))
              .statusCode(status)
              .errorMessage(stackTrace(request))
              .build());
    } catch (Exception e) {
      log.warn(
          "[recordActionLog] failed to persist action log path={}",
          servletRequest.getServletPath(),
          e);
    }
  }

  private static @Nullable String requestAttribute(RequestAttributes request, String name) {
    Object value = request.getAttribute(name, RequestAttributes.SCOPE_REQUEST);
    return value == null ? null : value.toString();
  }

  private static @Nullable String stackTrace(RequestAttributes request) {
    Object exceptionObj =
        request.getAttribute(Constants.EXCEPTION_MESSAGE, RequestAttributes.SCOPE_REQUEST);
    if (exceptionObj instanceof Exception exception) {
      return exception.getClass().getSimpleName();
    }
    return null;
  }

  private @Nullable String getRequestParams(HttpServletRequest request) {
    try {
      ObjectMapper objectMapper = new ObjectMapper();
      return objectMapper.writeValueAsString(request.getParameterMap());
    } catch (Exception e) {
      return null;
    }
  }

  private boolean shouldFilter(HttpServletRequest request) {
    try {
      Optional<java.util.UUID> optionalUserAuthentication = SecurityUtils.getCurrentUserLoginId();
      if (optionalUserAuthentication.isEmpty()) {
        return false;
      }
    } catch (Exception e) {
      log.debug("Path url: {} ", request.getRequestURI());
      log.debug("Action log, ignore exception", e);
    }
    if (BLACKLIST.isEmpty()) {
      return true;
    }
    String uri = String.valueOf(request.getRequestURI());
    return uri.startsWith("/api/") && BLACKLIST.stream().noneMatch(uri::matches);
  }

  private static @Nullable String authenticatedUsername(HttpServletRequest request) {
    Object username =
        request.getAttribute(CustomAuthenticationFilter.AUTHENTICATED_USERNAME_ATTRIBUTE);
    return username instanceof String value ? value : null;
  }

  private String getRemoteIp(HttpServletRequest request) {
    String ip = request.getHeader("X-Forwarded-For");
    if (StringUtils.hasLength(ip) && !"unKnown".equalsIgnoreCase(ip)) {
      int index = ip.indexOf(",");
      if (index != -1) {
        log.info("get remote ip: {}", ip);
        return ip.substring(0, index);
      } else {
        return ip;
      }
    }
    ip = request.getHeader("X-Real-IP");
    if (StringUtils.hasLength(ip) && !"unKnown".equalsIgnoreCase(ip)) {
      return ip;
    }
    return request.getRemoteAddr();
  }

  private String replaceRequestBody(String body) {
    if (!StringUtils.hasLength(body)) {
      return body;
    }
    try {
      ObjectMapper objectMapper = new ObjectMapper();
      JsonNode rootNode = objectMapper.readTree(body);

      MASK_FIELD_NAME.forEach(field -> mask(rootNode, field));

      return objectMapper.writeValueAsString(rootNode);
    } catch (Exception e) {
      return body;
    }
  }

  private static void mask(JsonNode rootNode, String fieldName) {
    if (rootNode instanceof ObjectNode objectNode && rootNode.has(fieldName)) {
      objectNode.put(fieldName, MASKED_VALUE);
    }
  }
}
