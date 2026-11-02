---
name: error-handling
description: Use when adding or changing an error code enum, a ResponseException, a domain-to-API error translation, an i18n message key, an HTTP error status, or exception handling across layers.
metadata:
  version: "0.0.1"
  author: vandunxg
  scope: shared
  category: api
  stack: java-21, spring-boot-4
---

# Error handling and i18n

The common web library (`vandunxg-common` → `ExceptionHandleAdvice`) owns the
final error response format. Modules own their error catalog and messages.
**MUST NOT** add a controller advice that duplicates the common handler, and
never replace the envelope with Problem Details.

## Error enum

One enum per module in `application/exception`, implementing `ResponseError`:

```java
@Getter
@RequiredArgsConstructor
public enum RoleErrorCode implements ResponseError {
  ROLE_NOT_FOUND(40411, "Role not found", 404),
  ROLE_IS_ACTIVE(40913, "Role must be inactive before deletion", 409);

  private final Integer code;
  private final String message;
  private final int status;

  @Override
  public String getName() {
    return name();
  }
}
```

- Constant name **MUST** start with an uppercase module or aggregate prefix:
  `<PREFIX>_<ERROR_NAME>` (`AUTH_INVALID_CREDENTIALS`, `ROLE_IS_ACTIVE`,
  `ORDER_NOT_FOUND`). `getName()` is the global i18n key, so generic names
  (`NOT_FOUND`, `ACCESS_DENIED`) collide and are forbidden.
- Numeric code format `{httpStatus}{2-digit sequence}`, **unique across the
  repository** (sequence is repository-wide per status, not per module).
- Never renumber a published code without an API compatibility decision.
- Fallback message is English and contains no secrets or PII.
- Add a unit test scanning every `ResponseError` enum that fails on a missing
  prefix, a duplicate code or name, or a missing i18n key.

## i18n

Every error name exists in every message file, keyed by the exact enum name
(no dotted keys):

```properties
# src/main/resources/i18n/messages.properties
ROLE_NOT_FOUND=Role not found
# src/main/resources/i18n/messages_vi.properties
ROLE_NOT_FOUND=Không tìm thấy vai trò
```

User-facing messages are never hard-coded outside i18n.

## Exceptions by layer

- **Domain:** throws `<Module>RuleViolation` only when the object itself
  enforces the rule (see `domain-modeling`).
- **Application:** translates domain violations and application failures into
  a module exception extending `ResponseException`. Keep the rule→error mapping
  in one place next to the error enum; translate at the call site:

```java
// application/exception/AuthErrorCode.java
public static AuthErrorCode from(AuthRule rule) {
  return switch (rule) {
    case ROLE_NOT_ASSIGNABLE -> ROLE_INVALID;
    case USER_ALREADY_VERIFIED -> USER_ALREADY_VERIFIED;
  };
}

// application/service/RegistrationCommandService.java
try {
  user.verifyEmail(now);
} catch (AuthRuleViolation violation) {
  throw AuthException.of(violation);
}
```

- **Infrastructure:** translate technology exceptions into a module error when
  callers can act on it; always keep the original cause.
  A unique-constraint race becomes a module error in `Jpa<Aggregate>Repository`;
  `saveAndFlush` so the violation surfaces inside the `try`, and match the
  constraint by its explicit name:

```java
try {
  entityRepository.saveAndFlush(entity);
} catch (DataIntegrityViolationException ex) {
  if (ex.getCause() instanceof ConstraintViolationException cve
      && RoleEntity.UK_ACTIVE_CODE.equals(cve.getConstraintName())) {
    throw new RoleException(RoleErrorCode.ROLE_CODE_DUPLICATED, ex);
  }
  throw ex;
}
```

- **Controllers:** do not create or translate business exceptions.
- Never signal a business failure with `IllegalArgumentException`,
  `RuntimeException`, or `NullPointerException`. Never swallow an exception and
  return `null` or a sentinel.

## HTTP statuses

- Success: `200`, `201`, `202`, `204`.
- Client: `400`, `401`, `403`, `404`, `409`, `413`, `415`, `429`.
- Server/dependency: `500`, `502`, `503`, `504`.

Framework-generated statuses (`405`, `406`) remain valid. Another status needs a
clear semantic reason; never map a condition to an incorrect status.
