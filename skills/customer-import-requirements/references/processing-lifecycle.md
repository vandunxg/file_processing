# Processing lifecycle requirements

Read before changing job state, attempt history, batch execution, progress,
retry, cancellation, or customer upsert:

- `docs/requirement/02-domain-model-and-business-rules.md`: aggregate invariants, legal state transitions, customer upsert semantics, and row-error counting.
- `docs/requirement/03-functional-specification.md`: processing, retry, cancel, and detail/progress use cases.
- `docs/requirement/04-non-functional-requirements.md`: bounded pipeline, backpressure, heartbeats, operation retries, and shutdown.

Validation errors are business outcomes; only system errors fail an attempt.
