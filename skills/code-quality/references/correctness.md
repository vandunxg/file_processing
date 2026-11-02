# Correctness signals

## Null safety
- Chained calls without null checks (`user.getName().toUpperCase()`).
- `Optional.get()` without a check; prefer `orElseThrow(...)`.
- Returning `null` instead of an empty collection.

## Exception handling
- Empty catch blocks; catching `Exception`/`Throwable` too broadly.
- Lost cause: `throw new RuntimeException(e.getMessage())`.
- Exception neither handled, logged, nor rethrown.

```java
// ❌ cause lost
catch (IOException e) { throw new RuntimeException(e.getMessage()); }
// ✅ cause kept, module exception
catch (IOException e) { throw new StorageException(StorageErrorCode.STORAGE_WRITE_FAILED, e); }
```

## Resources
- Manual `close()` instead of try-with-resources.

## Transactions and concurrency
- Multi-step writes without `@Transactional`; reads without `readOnly = true`.
- `@Transactional`/`@Async` self-invocation through `this` (bypasses the proxy).
- Catching `OptimisticLockingFailureException` around `save()` inside the same
  transaction (it is thrown at commit).
- Updates without comparing the client's `version`.
- Lazy relations touched after the transaction with Open Session in View off.
