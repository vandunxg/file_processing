---
name: java-style
description: Use when naming Java classes, methods, or tests, choosing between record and class, applying Lombok, adding a builder, using Optional, records, sealed types, pattern matching, or virtual threads in Java 21 code.
metadata:
  version: "0.0.1"
  author: vandunxg
  scope: shared
  category: style
  stack: java-21, spring-boot-4
---

# Java style

## Naming

| Concept                                   | Convention                      |
|-------------------------------------------|---------------------------------|
| Aggregate / value object / enum           | `User`, `EmailAddress`, `UserStatus` |
| Aggregate repository contract             | `<Aggregate>Repository`         |
| Domain policy / event                     | `<Rule>Policy`, `<Aggregate><PastTenseFact>` |
| Application services                      | `<Capability>CommandService`, `<Capability>QueryService`, or `<Capability>Service` |
| External capability contract              | `EmailSender`, `FileStorage`, `TokenIssuer` |
| Application input / output                | `<Action>Command`, `<Action>Query`, `<Action>Result` |
| Controller / DTOs                         | `<Xxx>Controller`, `<Xxx>Request`, `<Xxx>Response` |
| Persistence model                         | `<Xxx>Entity`                   |
| JPA implementation of domain repository   | `Jpa<Aggregate>Repository`      |
| Spring Data interface / custom fragment   | `<Xxx>EntityRepository`, `<Xxx>EntityRepositoryCustom` |
| Other infrastructure implementation       | `<Technology><Capability>` (`SmtpEmailSender`) |
| Configuration                             | `<Xxx>Configuration`            |
| Unit / integration test                   | `<ClassName>Test`, `<ClassName>IT` |

- Camel-case verb methods; booleans start with `is`, `has`, `can`.
- Constants and enum constants in `SCREAMING_SNAKE_CASE`.
- No abbreviations unless established domain language.
- Short focused methods; early returns over deep nesting.

## Lombok

| Annotation | Rule |
|---|---|
| `@RequiredArgsConstructor`, `@Slf4j`, `@Getter` | Allowed |
| `@Setter` | Entities and paginated query classes only; never on aggregates |
| `@Builder` / `@SuperBuilder` | Under the record rule below, or tests/complex construction that keeps invariants |
| `@EqualsAndHashCode` | Aggregates and entities: identity only, `@EqualsAndHashCode(of = "id")`. Paging request/query classes (which cannot be records): `@EqualsAndHashCode(callSuper = true)` value equality |
| `@ToString` | Only on types without sensitive data. Exclude passwords, hashes, tokens, email, phone, and lazy associations with `@ToString.Exclude` |
| `@Data`, `@Value` | **Never** — enforced by `lombok.config` |

```properties
# lombok.config
lombok.data.flagUsage = error
lombok.value.flagUsage = error
```

Constructor injection via `@RequiredArgsConstructor`; never field `@Autowired`.

## Records

- DTOs, commands, queries, results, and value objects are **records**.
- JPA entities and mutable aggregates are classes.
- Exception: a type that must extend a class (a paginated query extends
  `PagingQuery`) stays a class with `@Getter`/`@Setter`/`@SuperBuilder`.
- Add `@Builder` to a record only when it is constructed **by hand** (not only
  by a mapper) **and** two or more components share a type — positional calls
  can then transpose them and still compile. Otherwise use the canonical
  constructor.

## Values and nulls

- No `Optional` as a field, DTO field, entity field, or parameter; use it only
  as a return type. Prefer `orElseThrow(...)` over `get()`.
- Return an empty collection, never `null`.

## Modern Java

- Sealed hierarchies and pattern matching only when the type set is genuinely
  closed by the business.
- Virtual threads only for bounded blocking I/O — never a substitute for
  bounded CPU executors or backpressure. Do not enable them globally without a
  reason. Never pool them. On Java 21, `synchronized` around blocking I/O pins
  the carrier thread — use `ReentrantLock` in code that runs on virtual threads.
- Use sequenced collections (`getFirst()`, `getLast()`, `reversed()`), text
  blocks for JPQL/SQL and JSON test bodies, and `case null, default ->` /
  guarded `case X x when ...` in switches.
