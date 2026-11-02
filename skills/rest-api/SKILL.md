---
name: rest-api
description: Use when writing or changing a REST controller, request/response DTO, endpoint path, pagination or search endpoint, HTTP method or status choice, or OpenAPI/Swagger documentation.
metadata:
  version: "0.0.1"
  author: vandunxg
  scope: shared
  category: api
  stack: java-21, spring-boot-4
---

# REST API

Controllers translate HTTP into an application use case. They live in
`<module>/api` and call a concrete application service directly.

## Controller rules

- Only: request validation, mapping, service invocation, response construction.
  No business decisions, no transactions.
- DTOs in and out — never JPA entities or domain aggregates.
- Wrap responses in the common `Response<T>` or `PagingResponse<T>`.
- `@Valid` on request bodies; validate path variables and parameters.
- Cross-field rules (e.g. a date range) are class-level constraints that report
  on one field via `context.buildConstraintViolationWithTemplate(...)
  .addPropertyNode("to")`.
- Path from configured prefix and version:
  `@RequestMapping("${app.api.prefix}/${app.api.version}/roles")`.
- `201 Created` + `Location` for creation when supported; `202 Accepted` only
  when work continues asynchronously; `204` for delete.
- `GET` never changes state; retrieval is not `POST`; `PUT` is a full,
  idempotent replacement.
- Do not trust proxy forwarding headers unless trusted proxies are configured.

```java
@RestController
@RequestMapping("${app.api.prefix}/${app.api.version}/roles")
@RequiredArgsConstructor
public class RoleController {

  private final RoleCommandService roleCommandService;

  @DeleteMapping("/{roleId}")
  public Response<Void> delete(@PathVariable UUID roleId) {
    roleCommandService.delete(roleId);
    return Response.noContent();
  }
}
```

## Pagination and search

Unbounded list, search, and completion endpoints **MUST** use the common paging
convention:

1. Request DTO extends `PagingRequest`.
2. Controller parameter uses `@ValidatePaging(sortModel = <Xxx>Entity.class)`.
3. Application query extends `PagingQuery`.
4. `application/capability/<Aggregate>SearchRepository` exposes `count(query)`
   and `search(query)`.
5. Controller returns `PagingResponse<T>`.

`@ValidatePaging` builds its sort allow-list from `@Column` fields, so
`sortModel` must be the module's JPA entity. That annotation argument is the
**only** place `api` may name an entity — never as parameter, return type, or
field.

Do not force paging onto bounded catalogs, enum lists, JWKS, `/me`, or
current-user lists unless the product requires it.

## OpenAPI

- One `OpenApiConfiguration` holds global metadata and security schemes; reuse
  its bearer-scheme constant instead of string literals.
- Public endpoints explicitly opt out of the global bearer requirement.
- `@Schema` for descriptions and examples; Jakarta Validation for constraints
  (OpenAPI never replaces runtime validation).
- Never publish entities, internal error causes, secrets, or implementation
  details in the schema.
- Breaking contract changes require a version decision.
