# Auth Security And Outbox Hardening Design

## Goal

Remove the identified credential disclosure, CSRF, enumeration, configuration,
and delivery-reliability risks from the auth module through small,
independently deployable changes.

## Constraints

- Every phase is one self-contained commit with focused regression tests.
- A fresh review agent reviews each phase before it is offered for human
  review.
- No phase may require a coordinated deployment with another phase.
- Outbox delivery is at-least-once; consumers must tolerate duplicate events.
- Cache invalidation remains best-effort because the database remains the
  authorization source of truth.

## Phase 1: Prevent Credential Disclosure In Action Logs

The action logging filter will not persist request bodies or query parameters
for auth and credential-management routes. For other routes it will recursively
mask sensitive JSON keys, including `temporaryPassword`, and replace malformed
bodies with a fixed redacted marker instead of retaining raw input. Tests cover
the temporary-password endpoint, nested secrets, query-string secrets, and
malformed JSON.

## Phase 2: Make Password Reset Enumeration-Safe

Forgot-password requests with a syntactically valid identifier return `204 No
Content` whether the account is unknown, disabled, or eligible for reset. The
unknown-account path performs the same password-hash work as the normal path
before returning. Existing rate limits are retained. Controller documentation
and HTTP integration tests adopt this contract.

## Phase 3: Escape User-Controlled Email Content

The mail sender HTML-escapes display names before inserting them into HTML
email bodies. Verification and reset links remain generated server-side and
are not logged. Unit tests prove markup in a display name is delivered as text.

## Phase 4: Harden Cookie CSRF And Route Configuration

The refresh endpoint validates an `Origin` header against the configured CORS
origins before accepting its double-submit CSRF token. Cookie paths and auth
public paths are derived from typed API prefix/version configuration rather
than `/api/v1` constants. Tests cover allowed and denied origins and a
non-default API prefix/version.

## Phase 5: Fail Fast On Auth Configuration

`AuthProperties` gains `@Validated` plus Jakarta constraints for required
issuer/audience/key identifiers, secrets, positive durations, and positive
rate limits. Invalid configuration fails at application startup rather than in
an authentication flow. This phase does not alter valid runtime configuration.

## Phase 6: Durable Audit Delivery

Auth audit writes persist an outbox record in the same database transaction as
the business change. A scheduled worker atomically claims a bounded batch using
PostgreSQL `FOR UPDATE SKIP LOCKED`, publishes to RabbitMQ with publisher
confirms, and sets `delivered_at` only after a positive broker acknowledgement.
Confirm timeouts and nacks release the lease and schedule a bounded retry with
exponential backoff; exhausted events are marked failed and emit metrics/logs
without secrets. The worker reclaims expired leases after a process crash.

This phase moves audit-log publication to the outbox and makes the audit
consumer idempotent by event ID. The existing audit RabbitMQ route remains the
external contract.

## Phase 7: Durable Verification Email Delivery

Verification-email requests persist into the established outbox rather than
publishing after commit. The existing verification RabbitMQ route and consumer
remain unchanged, so this change is independently deployable and reversible.

## Phase 8: Durable Password Reset Email Delivery

Password-reset requests persist their email event into the established outbox
rather than calling the mail sender after commit. This migration is separate
from verification-email delivery; it has its own regression tests, commit, and
review.

## Data Model

The outbox table contains an immutable event ID, event type, JSON payload,
aggregate ID, creation timestamp, attempt count, next-attempt timestamp,
claim owner, lease expiry, delivered timestamp, failure timestamp, and the
standard audit/soft-delete columns. A partial index supports selecting pending
records ordered by `next_attempt_at, created_at`.

## Failure Semantics

- A DB rollback creates neither the business change nor its event.
- A commit followed by a process crash leaves a pending outbox row for the
  worker to publish later.
- RabbitMQ or email delivery can occur more than once; consumers use event ID
  storage/unique constraints to avoid duplicate observable effects.
- Failed delivery remains visible for operations rather than being silently
  discarded.

## Verification

Each phase follows red-green TDD, runs its focused test class, then runs
`./mvnw spotless:apply && ./mvnw verify` before commit. After implementation,
an independent review agent checks the phase diff against this design and the
security, async-processing, persistence, configuration, and testing skills.
