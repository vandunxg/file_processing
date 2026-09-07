package com.vandunxg.file_processing.configuration.security;

import java.util.List;

import com.vandunxg.common.web.config.SpringSecurityAuditorAware;
import com.vandunxg.common.web.security.ForbiddenTokenFilter;
import com.vandunxg.common.web.security.NoHandlerFoundFilter;
import com.vandunxg.common.web.security.RegexPermissionEvaluator;
import com.vandunxg.common.web.support.CustomAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.domain.AuditorAware;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@NullMarked
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@EnableMethodSecurity(securedEnabled = true)
@Slf4j(topic = "SECURITY-CONFIGURATION")
public class SecurityConfiguration {

  /**
   * Endpoints the application itself serves without authentication. A module's own public routes
   * are not listed here — it declares them through {@link ModuleSecurityContributor#publicPaths()}.
   */
  private static final String[] PLATFORM_PUBLIC_URLS = {
    "/", "/health", "/ready", "/ws/**", "/api/public/**"
  };

  private static final String[] AUTHENTICATED_URLS = {"/api/**"};

  private static final String[] ACTUATOR_PROBE_URLS = {
    "/actuator/health/liveness", "/actuator/health/readiness"
  };

  private static final String[] IGNORE_URLS = {
    "/js/*.js",
    "/js/*.html",
    "/i18n/**",
    "/content/**",
    "/swagger-ui",
    "/swagger-ui/**",
    "/api-docs",
    "/api-docs/**",
    "/webjars/**"
  };

  private static final String ALL_MANAGER_PERMISSION = "all:manage";

  private final RegexPermissionEvaluator customPermissionEvaluator;
  private final Converter<org.springframework.security.oauth2.jwt.Jwt, AbstractAuthenticationToken>
      jwtAuthenticationConverter;
  private final JwtDecoder jwtDecoder;

  /**
   * Optional on purpose: a context that loads this configuration without any business module on the
   * scan path should build a chain with no module contributions, not fail to start on a missing
   * bean.
   */
  private final ObjectProvider<ModuleSecurityContributor> moduleSecurityContributors;

  private final CustomAuthenticationEntryPoint customAuthenticationEntryPoint;
  private final NoHandlerFoundFilter noHandlerFoundFilter;
  private final ForbiddenTokenFilter forbiddenTokenFilter;

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    // orderedStream() honours @Order on each contributor, so filter placement does not depend on
    // classpath scan order once a second module starts contributing.
    List<ModuleSecurityContributor> contributors =
        moduleSecurityContributors.orderedStream().toList();
    String[] modulePublicUrls = publicUrlsOf(contributors);

    JwtAuthenticationProvider jwtAuthenticationProvider = new JwtAuthenticationProvider(jwtDecoder);
    jwtAuthenticationProvider.setJwtAuthenticationConverter(jwtAuthenticationConverter);

    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            sessionAuthenticationStrategy ->
                sessionAuthenticationStrategy.sessionCreationPolicy(
                    SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            authorize -> {
              authorize
                  .requestMatchers(HttpMethod.OPTIONS, "/**")
                  .permitAll()
                  .requestMatchers(IGNORE_URLS)
                  .permitAll()
                  .requestMatchers(PLATFORM_PUBLIC_URLS)
                  .permitAll();
              if (modulePublicUrls.length > 0) {
                authorize.requestMatchers(modulePublicUrls).permitAll();
              }
              authorize
                  .requestMatchers(ACTUATOR_PROBE_URLS)
                  .permitAll()
                  .requestMatchers("/actuator/prometheus")
                  .hasAuthority(ALL_MANAGER_PERMISSION)
                  .requestMatchers("/actuator/**")
                  .denyAll()
                  .requestMatchers(AUTHENTICATED_URLS)
                  .authenticated();
            })
        .oauth2ResourceServer(
            oauth2 ->
                oauth2.authenticationManagerResolver(
                    request -> jwtAuthenticationProvider::authenticate))
        .exceptionHandling(
            exHandling -> exHandling.authenticationEntryPoint(this.customAuthenticationEntryPoint));

    http.addFilterAfter(noHandlerFoundFilter, BearerTokenAuthenticationFilter.class);
    http.addFilterAfter(forbiddenTokenFilter, BearerTokenAuthenticationFilter.class);
    for (ModuleSecurityContributor contributor : contributors) {
      contributor.contribute(http);
    }

    return http.build();
  }

  @Bean
  WebSecurityCustomizer webSecurityCustomizer() {
    // Contributors are resolved inside the lambda, not at bean construction: WebSecurity applies
    // customizers while building the same context that builds the filter chain, so this touches
    // contributor beans no earlier than the chain already does.
    return web -> {
      WebSecurity.IgnoredRequestConfigurer ignoring =
          web.ignoring().requestMatchers(PLATFORM_PUBLIC_URLS);
      String[] modulePublicUrls = publicUrlsOf(moduleSecurityContributors.orderedStream().toList());
      if (modulePublicUrls.length > 0) {
        ignoring = ignoring.requestMatchers(modulePublicUrls);
      }
      ignoring.requestMatchers(IGNORE_URLS);
    };
  }

  private static String[] publicUrlsOf(List<ModuleSecurityContributor> contributors) {
    return contributors.stream()
        .flatMap(contributor -> contributor.publicPaths().stream())
        .distinct()
        .toArray(String[]::new);
  }

  @Bean
  MethodSecurityExpressionHandler expressionHandler() {
    var expressionHandler = new DefaultMethodSecurityExpressionHandler();
    expressionHandler.setPermissionEvaluator(customPermissionEvaluator);
    return expressionHandler;
  }

  @Bean
  public AuditorAware<String> springSecurityAuditorAware() {
    return new SpringSecurityAuditorAware();
  }
}
