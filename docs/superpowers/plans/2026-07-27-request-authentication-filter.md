# Request Authentication Filter Implementation Plan

<!-- prettier-ignore -->
> [!WARNING]
> **LEGACY ARCHITECTURE NOTICE — SUPERSEDED ARCHITECTURE GUIDANCE**
>
> Tài liệu này được tạo trước quyết định chuyển sang Pragmatic Modular DDD.
> Các package `adapter/*`, `port/*`, `*UseCase`, `*Port` và `*Adapter` trong tài
> liệu này mô tả legacy implementation và **không còn là architecture guidance**.
>
> This document predates the migration to Pragmatic Modular DDD. Every
> `adapter/in`, `adapter/out`, `port/in`, `port/out`, `*UseCase`,
> `*RepositoryPort`, and `*PersistenceAdapter` reference below records the
> legacy implementation **as it was actually built**. It is a historical record,
> not an instruction. Do not reproduce this layout, naming, or interface
> structure in new code or in a refactor.
>
> [`RULE.md`](../../../RULE.md) §4 is the source of truth for architecture. The
> business behavior, API contracts, and security requirements described here
> remain valid; only the structural guidance is superseded.

> **PLAN COMPLETED — DO NOT RE-EXECUTE.** Every task in this plan was implemented and merged. Its package layout and type names follow the legacy Hexagonal structure and are superseded by `RULE.md` §4. Kept for history only.
>
> <sub>Original agent instruction, retained verbatim for the record: **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.</sub>

**Goal:** Reload an authenticated user's active status and permissions on every JWT request while preserving the common-library `UserAuthentication` contract.

**Architecture:** A new application use case resolves a minimal request-authentication result from the user repository and existing authority service. `CustomAuthenticationFilter` remains an inbound security adapter: it invokes that use case and replaces the security context authentication after Spring has verified the JWT.

**Tech Stack:** Java 21, Spring Security resource server, Spring MVC filters, JUnit 5, Mockito, AssertJ.

## Global Constraints

- Java version is 21 and the build uses Maven wrapper `./mvnw`.
- `domain` must not import Spring, JPA, Jackson, servlet, HTTP, or adapter classes.
- `application` may use Spring transactions but must not depend on controllers, JPA entities, or adapters.
- Use the existing `UserRepositoryPort`, `AuthorityService`, `AuthDomainException`, and common-library `UserAuthentication`; add no dependency.
- A missing or non-active user must return `401 INVALID_CREDENTIALS` without revealing account state.
- JWT signature, issuer, audience, type, session, and credential-version validation remain in `JwtConfiguration`.
- Do not log JWTs, raw token values, or PII.
- Run `./mvnw spotless:apply` and the targeted tests before the full verification suite.

---

## File Structure

- Create `src/main/java/com/vandunxg/file_processing/auth/application/port/in/ResolveRequestAuthenticationUseCase.java`: inbound application boundary for per-request user state and permission resolution.
- Create `src/main/java/com/vandunxg/file_processing/auth/application/result/RequestAuthenticationResult.java`: immutable security-neutral result returned to the filter.
- Create `src/main/java/com/vandunxg/file_processing/auth/application/service/ResolveRequestAuthenticationService.java`: read-only implementation using `UserRepositoryPort` and `AuthorityService`.
- Create `src/test/java/com/vandunxg/file_processing/auth/application/service/ResolveRequestAuthenticationServiceTest.java`: unit tests for active, missing, and disabled users.
- Modify `src/main/java/com/vandunxg/file_processing/auth/configuration/filter/CustomAuthenticationFilter.java`: replace legacy persistence/cache types with the use case and common `UserAuthentication`.
- Create `src/test/java/com/vandunxg/file_processing/auth/configuration/filter/CustomAuthenticationFilterTest.java`: isolated servlet-filter tests for authority replacement and skipping non-JWT contexts.
- Modify `src/main/java/com/vandunxg/file_processing/configuration/security/SecurityConfiguration.java`: register the filter after bearer authentication.
- Modify `src/test/java/com/vandunxg/file_processing/configuration/security/SecurityConfigurationIT.java`: assert the filter is positioned after `BearerTokenAuthenticationFilter`.

### Task 1: Request Authentication Application Use Case

**Files:**
- Create: `src/main/java/com/vandunxg/file_processing/auth/application/port/in/ResolveRequestAuthenticationUseCase.java`
- Create: `src/main/java/com/vandunxg/file_processing/auth/application/result/RequestAuthenticationResult.java`
- Create: `src/main/java/com/vandunxg/file_processing/auth/application/service/ResolveRequestAuthenticationService.java`
- Test: `src/test/java/com/vandunxg/file_processing/auth/application/service/ResolveRequestAuthenticationServiceTest.java`

**Consumes:** `UserRepositoryPort.findById(UUID)`, `AuthorityService.permissionsFor(User)`, `User.isActive()`.

**Produces:** `ResolveRequestAuthenticationUseCase.resolve(UUID)` returning `RequestAuthenticationResult(UUID userId, String username, List<String> permissions)`.

- [ ] **Step 1: Write the failing application-service tests**

```java
@ExtendWith(MockitoExtension.class)
class ResolveRequestAuthenticationServiceTest {
  @Mock private UserRepositoryPort userRepositoryPort;
  @Mock private AuthorityService authorityService;

  private ResolveRequestAuthenticationService service;

  @BeforeEach
  void setUp() {
    service = new ResolveRequestAuthenticationService(userRepositoryPort, authorityService);
  }

  @Test
  void resolveReturnsCurrentPermissionsWhenUserIsActive() {
    UUID userId = UUID.randomUUID();
    User user = user(userId, UserStatus.ACTIVE);
    when(userRepositoryPort.findById(userId)).thenReturn(Optional.of(user));
    when(authorityService.permissionsFor(user)).thenReturn(List.of("file:read", "file:self_create"));

    assertThat(service.resolve(userId))
        .isEqualTo(
            new RequestAuthenticationResult(
                userId, "operator01", List.of("file:read", "file:self_create")));
  }

  @Test
  void resolveThrowsInvalidCredentialsWhenUserIsMissing() {
    UUID userId = UUID.randomUUID();
    when(userRepositoryPort.findById(userId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.resolve(userId))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
    verifyNoInteractions(authorityService);
  }

  @Test
  void resolveThrowsInvalidCredentialsWhenUserIsDisabled() {
    UUID userId = UUID.randomUUID();
    when(userRepositoryPort.findById(userId)).thenReturn(Optional.of(user(userId, UserStatus.DISABLED)));

    assertThatThrownBy(() -> service.resolve(userId))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
    verifyNoInteractions(authorityService);
  }

  private static User user(UUID userId, UserStatus status) {
    return User.builder()
        .id(userId)
        .username("operator01")
        .normalizedUsername("operator01")
        .email("operator01@example.com")
        .normalizedEmail("operator01@example.com")
        .displayName("Operator One")
        .passwordHash("{bcrypt}current")
        .status(status)
        .credentialVersion(1)
        .build();
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -Dtest=ResolveRequestAuthenticationServiceTest test`

Expected: compilation fails because the use case, result, and service do not exist.

- [ ] **Step 3: Add the inbound port, immutable result, and read-only service**

```java
// application/port/in/ResolveRequestAuthenticationUseCase.java
public interface ResolveRequestAuthenticationUseCase {
  RequestAuthenticationResult resolve(UUID userId);
}

// application/result/RequestAuthenticationResult.java
public record RequestAuthenticationResult(UUID userId, String username, List<String> permissions) {}

// application/service/ResolveRequestAuthenticationService.java
@Service
@RequiredArgsConstructor
public class ResolveRequestAuthenticationService implements ResolveRequestAuthenticationUseCase {
  private final UserRepositoryPort userRepositoryPort;
  private final AuthorityService authorityService;

  @Override
  @Transactional(readOnly = true)
  public RequestAuthenticationResult resolve(UUID userId) {
    User user = userRepositoryPort.findById(userId).orElseThrow(this::invalidCredentials);
    if (!user.isActive()) {
      throw invalidCredentials();
    }
    return new RequestAuthenticationResult(user.getId(), user.getUsername(), authorityService.permissionsFor(user));
  }

  private AuthDomainException invalidCredentials() {
    return new AuthDomainException(AuthErrorCode.INVALID_CREDENTIALS);
  }
}
```

- [ ] **Step 4: Run the application-service test to verify it passes**

Run: `./mvnw -Dtest=ResolveRequestAuthenticationServiceTest test`

Expected: PASS, with all three scenarios green.

### Task 2: Security Adapter, Filter Wiring, and Filter Tests

**Files:**
- Modify: `src/main/java/com/vandunxg/file_processing/auth/configuration/filter/CustomAuthenticationFilter.java`
- Create: `src/test/java/com/vandunxg/file_processing/auth/configuration/filter/CustomAuthenticationFilterTest.java`
- Modify: `src/main/java/com/vandunxg/file_processing/configuration/security/SecurityConfiguration.java`
- Modify: `src/test/java/com/vandunxg/file_processing/configuration/security/SecurityConfigurationIT.java`

**Consumes:** `ResolveRequestAuthenticationUseCase.resolve(UUID)`, verified `UserAuthentication`, and its JWT/user ID.

**Produces:** a replacement `UserAuthentication` containing authority strings freshly resolved from the application use case.

- [ ] **Step 1: Write the failing filter and wiring tests**

```java
@ExtendWith(MockitoExtension.class)
class CustomAuthenticationFilterTest {
  @Mock private ResolveRequestAuthenticationUseCase resolveRequestAuthenticationUseCase;
  @Mock private FilterChain filterChain;

  private CustomAuthenticationFilter filter;

  @BeforeEach
  void setUp() {
    filter = new CustomAuthenticationFilter(resolveRequestAuthenticationUseCase);
    SecurityContextHolder.clearContext();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void refreshesAuthoritiesFromTheApplicationUseCase() throws Exception {
    UUID userId = UUID.randomUUID();
    Jwt jwt = jwt(userId);
    SecurityContextHolder.getContext().setAuthentication(new UserAuthentication(jwt, List.of(), userId));
    when(resolveRequestAuthenticationUseCase.resolve(userId))
        .thenReturn(new RequestAuthenticationResult(userId, "operator01", List.of("file:read")));

    filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), filterChain);

    Authentication refreshed = SecurityContextHolder.getContext().getAuthentication();
    assertThat(refreshed).isInstanceOf(UserAuthentication.class);
    assertThat(refreshed.getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("file:read");
    verify(filterChain).doFilter(any(), any());
  }

  @Test
  void skipsResolutionWhenContextDoesNotContainUserAuthentication() throws Exception {
    filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), filterChain);

    verifyNoInteractions(resolveRequestAuthenticationUseCase);
    verify(filterChain).doFilter(any(), any());
  }

  private static Jwt jwt(UUID userId) {
    Instant now = Instant.parse("2026-07-27T00:00:00Z");
    return Jwt.withTokenValue("test-token")
        .header("alg", "RS256")
        .subject(userId.toString())
        .issuedAt(now)
        .expiresAt(now.plusSeconds(60))
        .build();
  }
}
```

Add this assertion to `SecurityConfigurationIT` after its existing bearer lookup:

```java
@Autowired private CustomAuthenticationFilter customAuthenticationFilter;

@Test
void runsCustomAuthenticationAfterBearerAuthentication() {
  var filters = filterChainProxy.getFilters("/api/v1/me");
  int bearerFilterIndex = filters.indexOf(filters.stream()
      .filter(BearerTokenAuthenticationFilter.class::isInstance)
      .findFirst()
      .orElseThrow());

  assertThat(filters.indexOf(customAuthenticationFilter)).isGreaterThan(bearerFilterIndex);
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -Dtest=CustomAuthenticationFilterTest,SecurityConfigurationIT test`

Expected: compilation fails because the filter still has legacy dependencies and is not wired into `SecurityConfiguration`.

- [ ] **Step 3: Replace the legacy filter with the use-case adapter and register it**

```java
// CustomAuthenticationFilter core implementation
@Component
@RequiredArgsConstructor
@NullMarked
public class CustomAuthenticationFilter extends OncePerRequestFilter {
  private final ResolveRequestAuthenticationUseCase resolveRequestAuthenticationUseCase;

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !(SecurityContextHolder.getContext().getAuthentication() instanceof UserAuthentication);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    UserAuthentication authentication =
        (UserAuthentication) SecurityContextHolder.getContext().getAuthentication();
    Jwt jwt = (Jwt) authentication.getPrincipal();
    RequestAuthenticationResult result =
        resolveRequestAuthenticationUseCase.resolve(authentication.getUserId());
    List<SimpleGrantedAuthority> authorities =
        result.permissions().stream().map(SimpleGrantedAuthority::new).toList();
    SecurityContextHolder.getContext().setAuthentication(
        new UserAuthentication(jwt, authorities, result.userId()));
    filterChain.doFilter(request, response);
  }
}

// SecurityConfiguration fields and filter ordering
private final CustomAuthenticationFilter customAuthenticationFilter;

http.addFilterAfter(customAuthenticationFilter, BearerTokenAuthenticationFilter.class);
http.addFilterAfter(actionLoggingFilter, CustomAuthenticationFilter.class);
```

`common-models:3.0.4` exposes `UserAuthentication.getUserId()` but no JWT accessor; its first constructor argument is the principal, so the filter retrieves the verified `Jwt` by casting `authentication.getPrincipal()`. Keep `ActionLoggingFilter` after the custom filter so recorded authorities are fresh.

- [ ] **Step 4: Run focused tests and format the changed source**

Run: `./mvnw spotless:apply && ./mvnw -Dtest=ResolveRequestAuthenticationServiceTest,CustomAuthenticationFilterTest,SecurityConfigurationIT test`

Expected: PASS, with no Spotless changes remaining after the test run.

- [ ] **Step 5: Run the full verification suite**

Run: `./mvnw verify`

Expected: PASS.

## Plan Self-Review

- Spec coverage: Task 1 isolates the application boundary, active-user check, current permission lookup, and `401 INVALID_CREDENTIALS`; Task 2 adapts the result to `UserAuthentication`, runs after bearer authentication, avoids raw-token invalidation, and covers filter behavior and wiring.
- Placeholder scan: no TODO/TBD items or unspecified files remain. The common-library `UserAuthentication` API was verified from `common-models:3.0.4`; it exposes `getUserId()` and stores the verified `Jwt` as the principal.
- Type consistency: all later references use `ResolveRequestAuthenticationUseCase.resolve(UUID)` and `RequestAuthenticationResult(UUID, String, List<String>)` introduced in Task 1.
