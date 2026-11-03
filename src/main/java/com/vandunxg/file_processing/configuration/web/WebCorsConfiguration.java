package com.vandunxg.file_processing.configuration.web;

import com.vandunxg.file_processing.configuration.security.SecurityConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Installs the only CORS filter of this service.
 *
 * <p>Registered as a plain servlet filter ahead of everything else rather than through Spring
 * Security's {@code http.cors(...)}: {@link SecurityConfiguration}'s {@code WebSecurityCustomizer}
 * takes the public auth routes out of the security filter chain entirely, so a CORS configuration
 * living inside that chain would never run for {@code /auth/login} or {@code /auth/refresh} — the
 * two calls a browser needs it for most. Running first also answers a preflight before
 * authentication, which is required because a preflight carries no credentials.
 */
@NullMarked
@Configuration
@Slf4j(topic = "WEB-CORS")
public class WebCorsConfiguration {

  @Bean
  public FilterRegistrationBean<CorsFilter> corsFilterRegistration(
      CorsProperties properties, ApiProperties apiProperties) {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(properties.allowedOrigins());
    configuration.setAllowedMethods(properties.allowedMethods());
    configuration.setAllowedHeaders(properties.allowedHeaders());
    configuration.setExposedHeaders(properties.exposedHeaders());
    configuration.setAllowCredentials(properties.allowCredentials());
    configuration.setMaxAge(properties.maxAge());

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration(apiProperties.basePath() + "/**", configuration);

    log.info(
        "[cors] allowedOrigins={} allowCredentials={}",
        properties.allowedOrigins(),
        properties.allowCredentials());

    FilterRegistrationBean<CorsFilter> registration =
        new FilterRegistrationBean<>(new CorsFilter(source));
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    registration.setName("corsFilter");
    return registration;
  }
}
