# Security signals

Rules live in `backend-security`; these are what to look for in a diff.

- Unvalidated path variables or request parameters; missing `@Valid` on bodies.
- String concatenation in `@Query`, native SQL, or JDBC; unescaped `LIKE` input.
- Hard-coded credentials; secrets with defaults in `application.yml`.
- Endpoint reachable without a matching security rule.
- Resource loaded by id without an owner/tenant check.
- Role or permission read from the request payload.
- Token, password, or full PII passed to a logger.
