---
name: object-mapping
description: Use when converting between DTOs, commands, domain models, and JPA entities — writing a MapStruct mapper, a from()/toResponse() factory, or a field-by-field copy across layers.
metadata:
  version: "0.0.1"
  author: vandunxg
  scope: shared
  category: persistence
  stack: java-21, spring-boot-4
---

# Object mapping (MapStruct)

## When a mapper is required

- aggregate ↔ persistence entity (every aggregate);
- multiple fields, nested values, conversions, or ignored fields;
- the same mapping is reused;
- API and application models must evolve independently.

A controller may build a trivial one/two-field command inline. Do not create a
mapper to copy one UUID. Assembling an object from several sources or scalars is
not a mapping and stays in the caller.

## Conventions

```java
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR,
    unmappedSourcePolicy = ReportingPolicy.WARN)
public interface RolePersistenceMapper {

  Role toDomain(RoleEntity entity);

  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "lastModifiedAt", ignore = true)
  RoleEntity toNewEntity(Role domain);

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "version", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "lastModifiedAt", ignore = true)
  void updateEntity(Role domain, @MappingTarget RoleEntity entity);
}
```

- `componentModel = SPRING`; inject mappers, never `new` them.
- `unmappedTargetPolicy = ERROR` is load-bearing: a write merges the mapped
  entity over the stored row, so an unmapped column is merged as `null` and the
  stored value is lost. Dropping `deletedAt` resurrects a deleted aggregate.
- `toNewEntity` for inserts, `@MappingTarget` for updates; do not replace a
  managed entity just to apply changes.
- `updateEntity` ignores `version`: Hibernate ignores a copied version anyway,
  so compare it explicitly (see `jpa-persistence`).
- `toDomain` calls the aggregate's `reconstitute(...)` (see `domain-modeling`).
- Keep `lombok-mapstruct-binding` when Lombok and MapStruct share classes.

## Forbidden

- ModelMapper, BeanUtils, Dozer, Orika, or reflection-based copying.
- Hand-written field-by-field copies (builder chains or constructors reading
  getters off one source) inside a service, repository, controller, or a DTO
  `from(...)` factory where a mapper is required.
