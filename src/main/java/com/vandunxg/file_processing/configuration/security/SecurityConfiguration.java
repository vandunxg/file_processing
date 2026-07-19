package com.vandunxg.file_processing.configuration.security;

import com.vandunxg.common.web.config.SpringSecurityAuditorAware;
import com.vandunxg.common.web.security.RegexPermissionEvaluator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@EnableMethodSecurity(securedEnabled = true)
@Slf4j(topic = "SECURITY-CONFIGURATION")
public class SecurityConfiguration {

  private static final String[] PUBLIC_URLS = {
    "/",
    "/health",
    "/ready",
    "/ws/**",
    "/api/public/**",
    "/api/v1/auth/register",
    "/api/v1/auth/verify-email",
    "/api/v1/auth/resend-verification",
    "/api/v1/auth/forgot-password",
    "/api/v1/auth/reset-password",
    "/api/v1/auth/complete-password-change",
    "/api/v1/auth/login",
    "/api/v1/auth/refresh",
    "/api/v1/certificate/.well-known/jwks.json",
  };

  private static final String[] AUTHENTICATED_URLS = {"/api/**"};

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

  private final RegexPermissionEvaluator customPermissionEvaluator;
  private final JwtAuthenticationConverter jwtAuthenticationConverter;
  private final JwtDecoder jwtDecoder;

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    JwtAuthenticationProvider jwtAuthenticationProvider = new JwtAuthenticationProvider(jwtDecoder);
    jwtAuthenticationProvider.setJwtAuthenticationConverter(jwtAuthenticationConverter);

    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            sessionAuthenticationStrategy ->
                sessionAuthenticationStrategy.sessionCreationPolicy(
                    SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .requestMatchers(IGNORE_URLS)
                    .permitAll()
                    .requestMatchers(PUBLIC_URLS)
                    .permitAll()
                    .requestMatchers(AUTHENTICATED_URLS)
                    .authenticated())
        .oauth2ResourceServer(
            oauth2 ->
                oauth2.authenticationManagerResolver(
                    request -> jwtAuthenticationProvider::authenticate));
    //            .exceptionHandling(
    //                exHandling ->
    // exHandling.authenticationEntryPoint(this.customAuthenticationEntryPoint));

    return http.build();
  }

  @Bean
  WebSecurityCustomizer webSecurityCustomizer() {
    return web -> web.ignoring().requestMatchers(PUBLIC_URLS).requestMatchers(IGNORE_URLS);
  }

  @Bean
  MethodSecurityExpressionHandler expressionHandler() {
    var expressionHandler = new DefaultMethodSecurityExpressionHandler();
    expressionHandler.setPermissionEvaluator(customPermissionEvaluator);
    return expressionHandler;
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public AuditorAware<String> springSecurityAuditorAware() {
    return new SpringSecurityAuditorAware();
  }
}
