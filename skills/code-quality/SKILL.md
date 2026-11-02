---
name: code-quality
description: Use when reviewing a Java/Spring Boot diff or pull request, refactoring existing code, or self-checking a change before merge.
metadata:
  version: "0.0.1"
  author: vandunxg
  scope: shared
  category: review
  stack: java-21, spring-boot-4
---

# Code quality review

Review against the convention skills; cite the skill instead of restating its
rule. Report only concrete findings visible in the diff and its direct context.

## Process

1. Scope defaults to `main...HEAD`; state it. Stop if the ref does not resolve
   or the diff is empty.
2. Each finding: `File.java:line`, the quoted code, the cited skill, one fix.
   Drop findings without all four.
3. Say what was verified (read, ran) versus inferred.
4. Group by severity, omit empty groups, end with a verdict: **Approve**,
   **Approve with comments**, or **Request changes**.

**Severity:** **Critical** (security, data loss, crash) · **Important**
(incorrect behavior, performance, boundary violation) · **Minor**
(readability, naming). Skip what Spotless already enforces.

## Checklist

**Architecture** (`modular-architecture`, `domain-modeling`)
- [ ] No `adapter/*`, `port/*`, `*UseCase`, `*Port`, `*Adapter`, empty packages.
- [ ] Every new interface protects a real boundary; no interface-per-class.
- [ ] Dependency direction valid; no module reaches into another's infrastructure.
- [ ] Domain has no Spring/Jackson/servlet/HTTP/JPA types; aggregate mutation via behavior methods.
- [ ] One repository per aggregate root; no Spring Data types on domain contracts.
- [ ] No Command/Query/Result wrapping a single value.

**Persistence** (`jpa-persistence`, `flyway-migration`, `object-mapping`)
- [ ] Every new table/entity has the four audit columns and `deleted_at`; reads exclude deleted rows; live uniqueness uses partial indexes.
- [ ] No hard-delete reachable from application services.
- [ ] Transactions short, on application services; no network calls inside.
- [ ] No N+1, `EAGER` workaround, unbounded page, or unstable sort.
- [ ] New append-only migration; no applied migration edited; `ddl-auto=validate`.
- [ ] Non-trivial mappings use MapStruct with `unmappedTargetPolicy = ERROR`; updates use `@MappingTarget`.

**API and errors** (`rest-api`, `error-handling`)
- [ ] No business logic or transactions in controllers; no entity/aggregate returned.
- [ ] `Response<T>`/`PagingResponse<T>` envelope; no duplicate controller advice.
- [ ] Error names prefixed, codes unique, keys present in every i18n file.
- [ ] Business failures are module exceptions, not bare runtime exceptions.

**Security, logging, config** (`backend-security`, `logging`, `app-configuration`)
- [ ] Ownership and authorization enforced server-side.
- [ ] No secrets, tokens, or unmasked PII in logs; exceptions logged once.
- [ ] Secrets have no defaults; config is typed and validated.

**Runtime and tests** (`async-processing`, `testing`, `java-style`)
- [ ] Files streamed; executors, queues, retries bounded; external calls have timeouts.
- [ ] No `@Data`/`@Value`; `@EqualsAndHashCode` identity-only on aggregates/entities; `@ToString` excludes sensitive fields; no field `@Autowired`.
- [ ] Risk-appropriate deterministic tests for changed behavior.
- [ ] No new framework or infrastructure without approval.
- [ ] No outdated APIs: `@MockBean`/`@SpyBean`, `RestTemplate`, `WebClient` for blocking calls, `javax.persistence`, `WebSecurityConfigurerAdapter`.

Generic Java signals: `references/correctness.md`, `references/smells.md`,
`references/security.md`, `references/performance.md`.
