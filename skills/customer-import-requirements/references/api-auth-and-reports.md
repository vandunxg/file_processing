# API, authorization, reports, and audit requirements

Read before exposing or changing any endpoint, error response, report, audit
event, or data-access authorization:

- `docs/requirement/03-functional-specification.md`: API capabilities, authorization behavior, pagination, and public errors.
- `docs/requirement/02-domain-model-and-business-rules.md`: report shape and access rules.
- `docs/requirement/04-non-functional-requirements.md`: PII protection and signed-download limits.

Operators must receive `404`, not `403`, for another owner's resource.
