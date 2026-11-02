### common-persistence

JPA hardening layer (artifact `common-persistence`, depends on `common-models`). Small, focused surface: SQL statement rewriting for safety, a cross-dialect sequence helper, and SQL string sanitizing. Does not pull in any specific JDBC driver — the consumer picks `postgresql`/`mysql`/`oracle`/etc.

**Auto-configuration**: `com.vandunxg.common.persistence.config.JpaDatabaseAutoConfiguration` (`@AutoConfiguration`, `@ConditionalOnClass(StatementInspector.class)`), registers:

- `SafeSqlInterceptor` bean — gated by `vandunxg.common.persistence.sql-inspector.enabled` (default `true`)
- `HibernatePropertiesCustomizer` that wires the interceptor into `hibernate.session_factory.statement_inspector` (only if the interceptor bean exists)
- `SeqRepository` bean → `SeqRepositoryImpl` (`@ConditionalOnMissingBean`)

**Config properties** (prefix `vandunxg.common.persistence.sql-inspector`):

| Property           | Default | Effect                                                                              |
|--------------------|---------|-------------------------------------------------------------------------------------|
| `enabled`          | `true`  | Toggles the `SafeSqlInterceptor` bean entirely                                      |
| `in-clause-limit`  | `1000`  | Max `?` placeholders per `IN (...)` before it's chunked into `OR`-joined blocks     |
| `rewrite-like`     | `true`  | Rewrites `field LIKE ?` → `lower(field) like lower(?)` (or `unaccent(...)` variant) |
| `rewrite-large-in` | `true`  | Enables the `IN`-clause chunking rewrite                                            |
| `use-unaccent`     | `false` | Uses `unaccent(lower(...))` instead of plain `lower(...)` for the `LIKE` rewrite    |

- `SafeSqlInterceptor implements org.hibernate.resource.jdbc.spi.StatementInspector` — regex-rewrites Hibernate-generated SQL before execution. No manual wiring needed once the auto-config is active.
- `SeqRepository` interface — `nextValue(@Nullable String prefix, String seqName) → String`, `nextValue(String seqName) → BigInteger`. Default impl `SeqRepositoryImpl` tries `CREATE SEQUENCE IF NOT EXISTS %s` then `CREATE SEQUENCE %s` (swallowing "already exists"/`ORA-00955`/"duplicate" errors), then tries three `nextval` SQL dialect templates in order (`select nextval('%s')`, `select next value for %s`, `select %s.nextval from dual`) until one succeeds — this is how it stays portable across Postgres/SQL Server/Oracle-style sequence syntax without per-dialect config.
- `SqlUtils` — `encodeKeyword(@Nullable String) → @Nullable String` (wraps in `%...%` with `%`/`_`/`\` escaped, for safe `LIKE` search terms), `replaceSpecialCharacter(String) → String`, `sanitizeIdentifier(String) → String` (throws `IllegalArgumentException` if the identifier doesn't match `[A-Za-z_][A-Za-z0-9_$.]*` — use before interpolating any identifier, e.g. a sequence name, into native SQL).
- `QueryUtils` — `createOrderQuery(@Nullable PagingQuery, String alias) → StringBuilder` (JPQL `order by alias.field`, defaults to `createdAt desc` if no `sortBy`), `createOrderNativeQuery(...)` (same but native SQL, snake_cased field via `camelToSnake`), `camelToSnake(@Nullable String) → @Nullable String`.
- `PagingQuery` (extends `Query`, builder pattern) — base class for paged/sorted/keyword search request objects: `pageIndex` (default `1`), `pageSize` (default `30`), `sortBy`, `keyword`. Constants `ASC_SYMBOL="asc"`, `DESC_SYMBOL="desc"`, `DEFAULT_PAGE_INDEX=1`, `DEFAULT_PAGE_SIZE=30`.
- `BaseEntityRepositoryCustom<E extends AuditableEntity, Q extends PagingQuery>` (`@NoRepositoryBean`) — extend this for a repository that needs dynamic JPQL search+count. Provides `search(Q request) → List<E>` and `count(Q request) → Long`; you implement `protected abstract String createWhereQuery(Q query, Map<String,Object> values, StringBuilder joinClause)` to build the dynamic `WHERE` clause and bind values — the base class handles paging/ordering/parameter binding/query execution.

**Gotchas**: no JDBC driver is bundled — add your own. `SafeSqlInterceptor`'s `LIKE`/`unaccent` rewrite assumes Postgres-style `unaccent()` availability if `use-unaccent=true` (needs the Postgres `unaccent` extension installed). This module also ships its own `README.md` (`modules/common-persistence/README.md`) with the same content in narrative form.

---

### common-cache

Redis-backed caching (artifact `common-cache`, depends on `common-utils`). Layers custom AOP annotations on top of Spring's standard `@Cacheable`/`@CacheEvict` for the specific case of updating or evicting **one item inside** an already-cached `Collection` or `Map` — without invalidating (and re-fetching) the whole cache entry.

**Auto-configuration**: `com.vandunxg.common.cache.RedisCacheAutoConfiguration` (`@EnableCaching`, `@AutoConfiguration(after = DataRedisAutoConfiguration.class)`, `@ConditionalOnClass({CacheProperties.Redis.class, RedisCacheConfiguration.class})`), registers:

- `RedisSerializer<Object> redisValueSerializer` — `JsonRedisSerializer` wrapping the app's Jackson `JsonMapper` (bean name `"redisValueSerializer"`, override by defining your own bean of that name)
- `RedisCacheManager` — per-cache TTL/null-caching/key-prefix overrides via `spring.cache.custom-cache.<name>.*` (same shape as Spring Boot's own `spring.cache.redis.*`)
- `CacheAspect` — the AOP advice that powers `@CacheCollection`/`@CacheMap`/`@CacheUpdate`
- `CacheService<K,V>` default impl → `RedisCacheServiceImpl` (programmatic access)
- `KeyGenerator` bean named `"customKeyGenerator"` → `CustomKeyGenerator`
- `CacheErrorHandler` → `RedisCacheErrorHandler` — **logs and swallows** all cache get/put/evict/clear errors instead of throwing, so a Redis outage degrades to "always miss", never breaks the calling method.

**Config properties**: standard Spring Boot `spring.cache.type=redis`, `spring.data.redis.host/port`, `spring.cache.redis.time-to-live`/`cache-null-values`/`key-prefix`/`use-key-prefix` (all honored as defaults), plus per-cache overrides:

```yaml
spring:
  cache:
    custom-cache:
      users:
        time-to-live: 30m
      sessions:
        time-to-live: 1h
        cache-null-values: false
```

(`spring.cache.custom-cache` → `Map<String, CacheProperties.Redis>`, backed by `CustomCacheProperties`, prefix `spring.cache`.)

**Annotations** (package `com.vandunxg.common.cache.annotation`, all `@Target({METHOD,TYPE})`, `RUNTIME` retention, apply `@AfterReturning` — they only fire on successful method return):

- `@CacheCollection(cacheNames, key, compareProperties, condition="", action=CacheAction.PUT)` — loads the cached `Collection` at `key`; for each existing item, compares it to the method's return value using `compareProperties` (field names, reflective equality) or full `equals()` if `compareProperties` is empty. On match: `PUT` replaces that item with the return value, `EVICT` drops it from the collection. No match + `action=PUT` → appends the return value. Rewrites the whole collection back into the cache.
- `@CacheMap(cacheNames, key, keyMap, condition="", action=CacheAction.PUT)` — loads the cached `Map` at `key`; `PUT` does `map.put(keyMap, returnValue)`, `EVICT` does `map.remove(keyMap)`.
- `@CacheUpdate(collection = {@CacheCollection...}, map = {@CacheMap...})` — composes multiple `@CacheCollection`/`@CacheMap` updates off one method's return value (e.g. a `save()` that must update both an id-keyed map cache and a list cache in one shot).
- `CacheAction` enum: `PUT`, `EVICT`.
- `condition`/`key`/`keyMap` are SpEL expressions evaluated against the method's return value (`#result`/root object), args (by parameter name), `#target`, and `#method`.

Usage example (from the library's `README.md`):

```java

@CacheCollection(cacheNames = "users", key = "#userId", compareProperties = {"id"}, action = CacheAction.PUT)
public UserDTO updateUser(Long userId, UserDTO dto) { ...}

@CacheUpdate(collection = {
  @CacheCollection(cacheNames = "users", key = "#result.id", compareProperties = {"id"}, action = CacheAction.PUT)
})
public UserDTO saveUser(UserDTO dto) { ...}
```

**`CacheService<K,V>`** (interface, package `service`) — programmatic alternative to annotations: `get(cacheName,key)`, `put(cacheName,key,value)`, `containsKey(cacheName,key)`, `evict(cacheName,key)`, `clear(cacheName)`. Inject as `CacheService<String, YourType>`.

**`CustomKeyGenerator`** — Spring `KeyGenerator` bean (`"customKeyGenerator"`), builds a stable key by reflecting over method parameters (handles `Collection`/`Map`/plain-object fields specially instead of relying on `toString()`/`hashCode()`). Use via `@Cacheable(keyGenerator = "customKeyGenerator")` when the default `SimpleKeyGenerator` doesn't produce stable/useful keys for complex parameter objects.

**`JsonRedisSerializer`** — stores cache values as JSON **with polymorphic type metadata** (`activateDefaultTyping(..., NON_FINAL)`), rebuilt from the app's own `JsonMapper` so custom modules/serializers still apply. **Gotcha**: this replaced a pre-2.0.0 ProtoStuff binary format — Redis entries cached before upgrading to 2.0.0+ are incompatible; flush the cache after upgrading across that boundary.

**`PageSerializer`** — a `tools.jackson.databind.ser.std.StdSerializer<PageImpl<?>>` that serializes Spring Data `Page` objects to `{number, numberOfElements, totalElements, totalPages, size, content}` — needed because `PageImpl` isn't reliably Jackson-serializable on its own; register/rely on it when caching or returning `Page<T>` results as JSON.

**`CacheConstants`**: `KEY="key"`, `DEFAULT="default"`.

---
