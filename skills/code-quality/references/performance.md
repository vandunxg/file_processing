# Performance signals

Fixes are in `jpa-persistence` and `async-processing`.

- Repository call or lazy collection access inside a loop (N+1).
- `findAll()` without pagination on a growing table.
- Filter or sort on a column without an index (index belongs in the migration).
- Whole-file or whole-result-set loads into memory.
- Unbounded executor, queue, or retry loop.
