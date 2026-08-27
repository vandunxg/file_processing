# Role Search Design

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

## Scope

Refactor `GET /roles` to provide paged keyword search and status filtering
while preserving the auth module's hexagonal boundaries.

## Boundary

- Add `SearchRolesUseCase` in `auth/application/port/in`.
- `RoleManagementService` implements this port and returns `PageDTO<Role>`.
- `RoleManagementController` injects the inbound port for its list endpoint,
  maps the domain result through `RoleWebMapper`, then returns
  `PagingResponse<RoleResponse>`.
- The application layer does not import web DTOs or mapper types.
- Existing role write operations remain out of scope.

## Search Contract

- `keyword` is optional and searches `code` as a partial match using
  `SqlUtils.encodeKeyword`.
- `status` uses `auth.domain.model.ActiveStatus` end to end.
- Only live roles are returned: `deletedAt is null`.
- `pageIndex` and `pageSize` reuse `PagingRequest` validation.
- `sortBy` is a domain contract, limited to `code`, `name`, `status`, and
  `createdAt`, each with `.asc` or `.desc`, optionally comma-separated.
  No web or application type refers to `RoleEntity`.
- Empty results retain the requested page index and size.

## Persistence

`JpaRoleRepositoryCustomImpl` continues to extend the library's
`BaseEntityRepositoryCustom`. It builds only the predicate and bound values;
the common library owns paging, ordering, and query execution.

## Tests

- Unit-test `SearchRolesUseCase` behavior and empty-page metadata.
- Add a PostgreSQL integration test covering live-role filtering, keyword
  matching, and status filtering.
- Add a web contract test for paging and domain `sortBy` validation.
