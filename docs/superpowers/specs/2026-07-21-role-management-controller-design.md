# Role Management Controller Refactor Design

## Scope

Refactor `RoleManagementController` to follow the module and API boundaries in
`RULE.md`. Keep every existing URI, HTTP method, authorization rule,
validation rule, and JSON field unchanged. The create endpoint is the sole
contract change: `POST /api/v1/roles` returns `201 Created`.

## Design

- Move nested request records from the controller to
  `auth/adapter/in/web/dto/request`.
- Keep `RoleRequest` reusable for create and update; name the inheritance
  request `RoleInheritanceRequest`.
- Convert resource catalog output to `List<String>` and add a permission
  catalog response DTO under `auth/adapter/in/web/dto/response` so HTTP
  responses keep the same JSON shape without exposing domain enums or nested
  application-service records.
- Add cohesive inbound ports for role management and the permission catalog.
  `RoleManagementService` and `PermissionCatalogService` implement them.
- Move role write inputs into application commands. The controller maps request
  DTOs plus the authenticated actor ID into these commands.
- Move the catalog output into an application result. `RoleWebMapper` maps
  commands and application/domain output to web DTOs.
- Use `SecurityUtils.authentication().getUserId()` instead of accepting a JWT
  in every mutating controller method.
- Return the common `Response<RoleResponse>` envelope from create and use
  `@ResponseStatus(HttpStatus.CREATED)` for the HTTP status, matching the
  existing controller convention.

## Error Handling

The controller does not translate business exceptions or add logging. Existing
application errors and the common exception handler remain responsible for
error responses.

## Verification

Keep the existing role-list integration tests. Add an integration test proving
that role creation returns `201 Created` and retains the standard response
envelope.
