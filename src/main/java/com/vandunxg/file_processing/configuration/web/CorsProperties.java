package com.vandunxg.file_processing.configuration.web;

import java.time.Duration;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Browser-facing CORS contract of this service.
 *
 * <p>The values are environment-dependent on purpose: the origin a browser calls this API from
 * differs per deployment, and the refresh-cookie flow only works when the exact front-end origin is
 * named. {@code common-web}'s own allow-all CORS filter is deliberately not component-scanned by
 * this application (see {@code CommonWebAutoConfiguration}'s javadoc), so this is the only CORS
 * definition in the service.
 */
@Validated
@ConfigurationProperties(prefix = "app.web.cors")
public record CorsProperties(
    /**
     * Origins a browser may call this API from, e.g. {@code http://localhost:5173}.
     *
     * <p>The cookie-based refresh flow requires an explicit allowlist. Wildcards would let any
     * origin pass the Origin validation that protects refresh requests.
     */
    @NotEmpty List<@NotBlank String> allowedOrigins,
    @NotEmpty List<String> allowedMethods,
    @NotEmpty List<String> allowedHeaders,
    /**
     * Response headers the browser is allowed to read. Only the CORS-safelisted headers are
     * readable by default, so anything the front end acts on — {@code Retry-After} for throttling,
     * {@code Content-Disposition} for the error-report download — has to be named here.
     */
    List<String> exposedHeaders,
    /** Must stay true: the refresh-token flow is cookie based. */
    boolean allowCredentials,
    /** How long a browser may cache a preflight result. */
    @NotNull Duration maxAge) {

  /**
   * Copies defensively but leaves {@code null} alone, so a missing key is reported by {@link
   * NotEmpty} as a readable binding failure instead of a {@link NullPointerException} thrown before
   * validation ever runs.
   */
  public CorsProperties {
    allowedOrigins = copyOrNull(allowedOrigins);
    allowedMethods = copyOrNull(allowedMethods);
    allowedHeaders = copyOrNull(allowedHeaders);
    exposedHeaders = exposedHeaders == null ? List.of() : List.copyOf(exposedHeaders);
    if (allowedOrigins != null
        && allowedOrigins.stream().anyMatch(origin -> origin.contains("*"))) {
      throw new IllegalArgumentException(
          "Credentialed CORS requires explicit allowed origins, not wildcards");
    }
  }

  private static List<String> copyOrNull(List<String> values) {
    return values == null ? null : List.copyOf(values);
  }
}
