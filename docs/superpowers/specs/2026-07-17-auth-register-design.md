# Auth Register Design

**Goal:** Deliver self-service registration, email verification, and verification-email resend in accordance with AUTH-UC-02 through AUTH-UC-04.

**Scope:** The implementation first replaces the current Auth stubs with the required domain and persistence foundation, then delivers the three public endpoints. Login, JWT issuance, refresh sessions, password reset, and administrative user management remain outside this delivery.

## Architecture

- Domain: `User` owns normalized identity, `PENDING_VERIFY` status, credential version, and password state. `PasswordPolicy` enforces the 8-128 Unicode code-point policy without logging the raw password.
- Persistence: Flyway creates `auth_users`, RBAC tables, audit logs, and `auth_email_verification_tokens`. PostgreSQL partial unique indexes on active normalized username and email are the final duplicate guard. The `OPERATOR` role is seeded deterministically.
- Application: `RegisterService` validates and normalizes input, applies IP throttling, hashes the password, creates the user, assigns `OPERATOR`, persists a SHA-256 hash of a 256-bit opaque verification token, and records `USER_REGISTERED` in one transaction.
- Delivery: after the registration transaction commits, `EmailSenderPort` sends the verification link. A delivery failure is logged and metered without rolling back the committed registration; `resend-verification` recovers delivery by invalidating old tokens and sending a new one.
- Verification: `VerifyEmailService` locks the token row, rejects unknown, expired, or used tokens, consumes the valid token, and transitions the user from `PENDING_VERIFY` to `ACTIVE` in one transaction.
- Web: public `POST /api/v1/auth/register`, `POST /api/v1/auth/verify-email`, and `POST /api/v1/auth/resend-verification` use request/response DTOs and thin controllers. Rate-limit denial is `429`; duplicate username/email is `409`; password policy failure is `422`.

## Security Decisions

- Raw passwords, opaque tokens, token hashes, and full email addresses never enter logs, audit metadata, or API responses.
- Verification tokens are random 256-bit URL-safe values. Only their SHA-256 hash is stored. Tokens expire after 24 hours and are single-use.
- Registration starts users as `PENDING_VERIFY`, sets `mustChangePassword` to false, and initializes credential version to one. Such users cannot obtain an access token in the later Login delivery.
- Duplicate checks are a fast application guard; unique partial indexes remain authoritative under concurrent registration.
- Register uses a bounded IP limiter of 10 requests per hour. Resend uses the specified identifier/IP throttle and returns an enumeration-safe response for absent or already verified accounts.

## Testing

- Unit tests cover normalization, username/email/display-name validation, password policy, and user verification state transition.
- PostgreSQL Testcontainers integration tests prove migrations, seed data, duplicate races, token hashing, expiry, single use, and transaction boundaries.
- Web integration tests cover the public response contract, validation, rate limits, duplicate errors, and the absence of sensitive values in responses.

## Acceptance Criteria

- A valid registration returns `201 Created`, persists a `PENDING_VERIFY` user with `OPERATOR`, records audit data, and sends a verification email after commit.
- Duplicate email or username returns the correct `409` code even during concurrent requests.
- A valid verification token activates the account exactly once; expired or reused tokens return `410`.
- Resend never exposes whether an identifier is absent or already verified.
