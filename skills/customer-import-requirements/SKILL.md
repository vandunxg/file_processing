---
name: customer-import-requirements
description: Use before planning, implementing, reviewing, or testing customer CSV upload, CSV validation, import jobs, retry/cancel, reports, report authorization, recovery, retention, or operations in this service.
metadata:
  version: "0.0.1"
  author: vandunxg
  scope: project
  category: requirements
  stack: java-21, spring-boot-4
---
# Customer import requirements

The focused documents in `docs/requirement/` are authoritative; use
`99-master-BA-SRS.md` to resolve apparent conflicts. Do not reinterpret
business behavior silently.

| Task | Read |
|---|---|
| CSV template, validation, upload, duplicate control | `references/csv-and-upload.md` |
| Job states, attempts, batching, counters, cancellation | `references/processing-lifecycle.md` |
| APIs, ownership, RBAC, reports, errors, audit | `references/api-auth-and-reports.md` |
| Timeouts, recovery, retention, shutdown, metrics, deliverables | `references/reliability-and-operations.md` |

Also consult `05-test-and-developer-handover.md` and the traceability matrix
when changing observable behavior or tests.
