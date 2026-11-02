# CSV and upload requirements

Read these authoritative sections before changing CSV parsing, row validation,
file validation, storage, checksum, or duplicate behavior:

- `docs/requirement/02-domain-model-and-business-rules.md`: CSV contract, field normalization, duplicate IDs, import-file invariants, and upload transaction semantics.
- `docs/requirement/03-functional-specification.md`: upload use case, API responses, authorization, and error catalog.
- `docs/requirement/04-non-functional-requirements.md`: streaming, size, memory, security, and timeout constraints.

The header, checksum, object-store-first, owner-plus-checksum uniqueness, and
database uniqueness boundary are non-negotiable.
