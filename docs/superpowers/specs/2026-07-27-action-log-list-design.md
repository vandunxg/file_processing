# Action Log List API Design

## Scope

Add a privileged, paginated action-log listing API for the existing `ActionLog`
records. It follows the auth module's controller, paging, MapStruct, port, and
custom repository conventions.

## Authorization

Add `ACTION_LOG("IDENTITY")` to `ResourceCode`. The permission catalog will
therefore expose `action_log:read`. The endpoint requires that permission;
`all:manage` continues to grant access through the existing permission
evaluator.

## HTTP Contract

Expose `GET ${app.api.prefix}/${app.api.version}/admin/action-logs`.

The request extends `PagingRequest` and accepts these additional optional
filters:

- `username`: case-insensitive contains match.
- `apiDoc`: case-insensitive contains match.
- `errorMessage`: case-insensitive contains match.
- `requestMethod`: exact match.
- `startTimeFrom`: inclusive request-start lower bound.
- `startTimeTo`: inclusive request-start upper bound.

The inherited `keyword` filter searches username, API documentation, and error
content. Supplied filters combine with `AND`. Default ordering is
`startTime DESC`; client sorts remain bounded and validated against
`ActionLogEntity`.

The response is a `PagingResponse` containing every persisted `ActionLog`
field, including `requestData`, `requestParam`, and `errorMessage`.

This is an explicit approved exception to the standard prohibition on exposing
raw request content and stack traces: callers with `action_log:read` may view
those fields through this endpoint.

## Application Flow

`ActionLogController` maps the request to `ActionLogSearchQuery` and delegates
to `ActionLogReadService`. The read service performs a count first and only
executes the page search when results exist. It extends the existing
`ActionLogPort` with `count` and `search`, keeping action-log persistence behind
the current outbound boundary.

`ActionLogPersistenceAdapter` delegates to a custom Spring Data repository and
maps entities through `ActionLogPersistenceMapper`. The custom repository
extends `BaseEntityRepositoryCustom`, excludes `deleteAt IS NOT NULL`, applies
the filters, and uses `startTime DESC` when no client sort is supplied.

## Persistence

Add one append-only Flyway migration for a descending `start_time` index. It
supports the default ordering and the requested time-range filter. No text
search index is added without measured need.

## Verification

Add an `ActionLogReadServiceTest` for empty and populated paging behavior.
Add a controller integration test that proves permission enforcement, combined
filters, default ordering, paging metadata, and the full-field response.
Run Spotless, focused tests, and Maven verification.
