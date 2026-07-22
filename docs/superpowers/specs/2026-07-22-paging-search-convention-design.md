# Paging Search Convention Design

## Scope

Convert only unbounded admin list APIs to the established paging/search convention.
Keep bounded catalogs, JWKS, `/me`, and current-user session lists unchanged.

## Convention

Unbounded list/search/completion endpoints use:

- request DTO extending `PagingRequest`;
- controller parameter annotated with `@ValidatePaging(sortModel = Entity.class)`;
- application query extending `PagingQuery`;
- repository `count(query)` and `search(query)`;
- controller response `PagingResponse<ResponseDto>`.

## Targets

- `GET /api/v1/users` becomes paged/searchable by user identity/display fields.
- `GET /api/v1/admin/audit-logs` becomes paged/searchable/filterable for audit reads.

## Non-Goals

- Do not change bounded enum/catalog endpoints.
- Do not add a new paging framework.
- Do not replace the shared `@ValidatePaging` library behavior in this repo.

## Tests

Add or update focused integration tests proving paging metadata, sorting, and keyword/filter behavior for converted endpoints.
