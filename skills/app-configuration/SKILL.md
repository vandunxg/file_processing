---
name: app-configuration
description: Use when adding application properties, @ConfigurationProperties, environment variables, secrets, feature toggles, or editing application.yml or .env.example.
metadata:
  version: "0.0.1"
  author: vandunxg
  scope: shared
  category: configuration
  stack: java-21, spring-boot-4
---

# Application configuration

Configuration is typed, validated, and fail-fast.

- `@ConfigurationProperties` **records** with `@Validated` and Jakarta
  constraints. Do not scatter `@Value` across services.
- Namespace: `app.<module>.<key>`.
- Placement:
  - settings the module's workflow reads → `<module>/application/<Module>Properties`;
  - settings only a technical client reads → `<module>/infrastructure/config/`;
  - application-wide cross-cutting settings (CORS, web) → the shared
    configuration module.
- Externalize environment-dependent and operationally tunable values; keep
  ordinary constants as constants.
- Secrets **MUST NOT** have defaults: `${JWT_SECRET}`, never
  `${JWT_SECRET:changeme}`.
- `.env.example` documents every required variable with no real secret.
- Feature toggles are simple typed properties unless a flag platform is approved.

```java
@Validated
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(
    @NotBlank String issuer,
    @NotBlank String secret,
    @NotNull Duration accessTokenExpiration) {}
```

```yaml
app:
  security:
    jwt:
      secret: ${JWT_SECRET}
      issuer: ${JWT_ISSUER:my-service}
```
