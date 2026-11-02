---
name: domain-modeling
description: Use when writing or changing an aggregate, entity, value object, domain repository contract, domain exception, reconstitution factory, or time-dependent business logic in the domain layer.
metadata:
  version: "0.0.1"
  author: vandunxg
  scope: shared
  category: architecture
  stack: java-21, spring-boot-4
---

# Domain modeling

Domain objects express business state and behavior. They are never the HTTP
model and never the persistence model.

## Construction and invariants

- Aggregates preserve invariants through private constructors, static
  factories, and behavior methods (`activate()`, `rename()`, `delete(now)`) —
  no public setters.
- No no-arg constructor for convenience; JPA constructors belong on entities.
- Builders may help tests or complex construction but **MUST NOT** bypass
  invariants.
- Equality follows domain identity: `@EqualsAndHashCode(of = "id")`; never
  generate equality over mutable fields.
- Child entity lifecycle methods stay package-private so only the root drives them.
- Domain models **MUST NOT** log.

```java
@Getter
public final class Role extends AuditableDomain {
  private final UUID id;
  private String name;
  private RoleStatus status;
  private Instant deletedAt;

  private Role(UUID id, String name) {
    this.id = Objects.requireNonNull(id);
    this.name = requireValidName(name);
    this.status = RoleStatus.ACTIVE;
  }

  public static Role create(String name) {
    return new Role(IdUtils.nextId(), name);
  }

  public void delete(Instant now) {
    if (status == RoleStatus.ACTIVE) {
      throw new RoleRuleViolation(RoleRule.ROLE_MUST_BE_INACTIVE);
    }
    if (deletedAt == null) {
      deletedAt = Objects.requireNonNull(now);
    }
  }
}
```

## No persistence or web mapping

A domain model **MUST NOT** carry `jakarta.persistence`/Hibernate annotations,
`@JsonProperty`/`@JsonIgnore`, servlet, or Spring Web types. Every aggregate has
three parts:

```text
domain/model/Order.java                                  extends AuditableDomain
infrastructure/persistence/entity/OrderEntity.java       extends AuditableEntity
infrastructure/persistence/mapper/OrderPersistenceMapper between the two
```

Why: model and table evolve independently; aggregates are unit-testable
without a persistence provider; the table stops dictating the model. Enforce
it with an ArchUnit conformance test.

**Reconstitution:** persistence rebuilds an aggregate through a static
`reconstitute(...)` taking **every** persisted field (lock version,
`deletedAt`, audit values). It does not re-check creation invariants — a stored
row is a fact, not a new request. Taking every field means adding one fails to
compile in the mapper until the entity carries it.

## Repository per aggregate root

- Only the aggregate root gets `domain/<Aggregate>Repository`. No
  `OrderItemRepository` for an entity inside `Order`.
- The contract speaks domain types only: no `Pageable`, `Specification`,
  `EntityManager`, or entities.
- Load and save the aggregate as a whole; no side-door mutation of children.
- Read models are a query concern. Paginated search (`count(query)`,
  `search(query)` over `PagingQuery`) goes on
  `application/capability/<Aggregate>SearchRepository`, because `PagingQuery`
  is an application type. One `Jpa<Aggregate>Repository` implements both.

## Time

- Persist timestamps as `Instant`.
- Inject `Clock` into time-dependent application code; pass `now` into domain
  methods. No `Instant.now()` in business logic.
- Convert to a user time zone only at the API/presentation boundary.

## Domain exceptions

A domain violation names the rule and nothing else — no HTTP status, numeric
code, or i18n key: a `<Module>Rule` enum plus
`<Module>RuleViolation extends RuntimeException` in `domain/exception`.
Translation to API errors is in `error-handling`.

Related: `modular-architecture`, `object-mapping`, `jpa-persistence`.
