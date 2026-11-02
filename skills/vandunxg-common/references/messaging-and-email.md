### common-amqp

RabbitMQ publish-side abstraction (artifact `common-amqp`, no internal `common-*` dependency). Gives consumers a consistent async event envelope (correlation/trace id, timestamp, auto UUID) instead of hand-rolling `RabbitTemplate` calls.

**Auto-configuration**: `com.vandunxg.common.amqp.config.AmqpAutoConfiguration` (`@AutoConfiguration`, `@ConditionalOnProperty(name = "spring.rabbitmq.enabled", havingValue = "true")` — **the whole module is a no-op unless this property is explicitly `true`**, even with the RabbitMQ starter on the classpath). Registers:

- `MessageConverter` → `JacksonJsonMessageConverter` scoped to trusted package `"com.vandunxg"`
- `AmqpTemplate` → `RabbitTemplate` wired with that converter
- `ExecutorService` (bean `amqpTaskExecutor`, `Executors.newVirtualThreadPerTaskExecutor()`, `destroyMethod="shutdown"`) — publishes run on virtual threads, off the caller's thread
- `AmqpEventPublisher` default impl → `DefaultAmqpEventPublisher`
- `MessageRouteRegistry` default impl → `InMemoryMessageRouteRegistry` (in-process `ConcurrentHashMap`, not distributed/persisted)

Enable with:

```yaml
spring:
  rabbitmq:
    enabled: true
```

**`AmqpEventPublisher`** (interface) — all methods return `CompletableFuture<Void>`:

- `<T> publish(MessageRoute route, T payload)` — wraps in a fresh `MessageEnvelope`, fire-and-forget
- `<T> publish(MessageRoute route, T payload, Duration delay)` — same, plus sets an `x-delay` header (**requires the broker/exchange to support delayed delivery**, e.g. the `rabbitmq-delayed-message-exchange` plugin — otherwise the header is ignored and it publishes immediately)
- `<T> publish(MessageRoute route, MessageEnvelope<T> envelope)` / `..., Duration delay)` — use when you need to control/propagate `correlationId`/`traceId` yourself rather than let it auto-generate

```java
MessageRoute userRoute = MessageRoute.of("user.events", "user.created");
publisher.

publish(userRoute, event);                          // fire-and-forget
publisher.

publish(userRoute, event, Duration.ofSeconds(5));    // delayed (needs broker support)
  publisher.

publish(userRoute, event).

exceptionally(ex ->{log.

error("Send failed",ex); return null;});

MessageEnvelope<UserCreatedEvent> envelope = MessageEnvelope.wrap(event, correlationId, traceId);
publisher.

publish(userRoute, envelope);
```

**`MessageEnvelope<T>`** (record) — `messageId: UUID`, `correlationId: @Nullable String`, `traceId: @Nullable String`, `payload: T`, `timestamp: Instant`. Static factories: `wrap(T payload)` and `wrap(T payload, String correlationId, String traceId)` (both auto-generate `messageId`/`timestamp`).

**`MessageRoute`** (record) — `exchange: String`, `routingKey: String`; static `MessageRoute.of(exchange, routingKey)`.

**`MessageRouteRegistry`** (interface) — optional convenience so call sites don't hardcode routes: `register(Class<?> messageType, MessageRoute route)`, `find(Class<?> messageType) → Optional<MessageRoute>`.

**`UUIDMessage`** (abstract class) — base for domain event payloads needing a stable identity: protected no-arg constructor auto-generates a random `UUID id` (or pass one explicitly via the protected `UUIDMessage(UUID id)` ctor); `equals`/`hashCode`/`toString` by `id`.

```java
public class UserCreatedEvent extends UUIDMessage {
  private final String username;

  public UserCreatedEvent(String username) {
    super();
    this.username = username;
  }
}
```

**`QueueOptions`** — RabbitMQ queue-argument key constants for use when declaring `Queue` beans: `MESSAGE_TTL` (`x-message-ttl`), `AUTO_EXPIRE` (`x-expires`), `MAX_LENGTH_BYTES` (`x-max-length-bytes`), `DEAD_LETTER_EXCHANGE` (`x-dead-letter-exchange`), `DEAD_LETTER_ROUTING_KEY` (`x-dead-letter-routing-key`).

```java
args.put(QueueOptions.DEAD_LETTER_EXCHANGE, "dlx.exchange");
args.

put(QueueOptions.MESSAGE_TTL, 60_000);
```

---

### common-email

SMTP send + IMAP scan-and-extract (artifact `common-email`, no internal `common-*` dependency).

**Auto-configuration**: `com.vandunxg.common.email.config.EmailAutoConfiguration` (`@AutoConfiguration`, `@ConditionalOnClass(JavaMailSender.class)`), registers:

- `MailSenderFactory` (always, if `JavaMailSender` is on the classpath)
- `MailService` default impl → `MailServiceImpl` — **only if a `JavaMailSender` bean already exists** (`@ConditionalOnBean(JavaMailSender.class)`), i.e. you must configure `spring.mail.*` so Spring Boot's own mail auto-configuration creates that bean first.

**Config**: `spring.mail.host/port/username/password/from/protocol` (Spring Boot standard prefix; `MailProperties` here is a slim internal read of the same prefix used by `MailServiceImpl` to resolve a default "from" address — not a separate property namespace to set). Plus scanner-specific `@Value` properties on `MailServiceImpl` (not `@ConfigurationProperties`, so not in `spring-configuration-metadata.json` — set as plain properties):

| Property                       | Default |
|--------------------------------|---------|
| `mail-scanner.protocol`        | `imap`  |
| `mail-scanner.imap.ssl.enable` | `true`  |
| `mail-scanner.imap.auth`       | `true`  |
| `mail-scanner.folder`          | `INBOX` |

**`MailService`** (interface) — all send methods throw `MessagingException` except `sendSimpleMail`:

- `sendSimpleMail(to, subject, content, String... cc)` — plain text
- `sendHtmlMail(to, subject, content, String... cc)` — HTML, via the injected default `JavaMailSender`
- `sendHtmlMail(host, port, username, password, to, subject, content, String... cc)` — builds a **one-off** `JavaMailSender` per call via `MailSenderFactory` (different SMTP account than the default, e.g. per-tenant sending)
- `sendAttachmentsMail(to, subject, content, filePath, String... cc)` — single file attachment from local disk
- `sendAttachmentsMailBcc(bcc: List<String>, subject, content, filePaths: List<String>, fileNames: List<String>, logoPath, logoContentId, String... cc)` — multi-attachment + optional inline image (e.g. a logo embedded via `cid:`) + BCC-only send (no `to` recipient)
- `sendResourceMail(to, subject, content, rscPath, rscId, String... cc)` — inline embedded classpath resource (reference in HTML as `<img src="cid:rscId">`)
- `scanMessage(mailHost, mailPort, username, password, totalMessageScan, subjectMail, cssQuery) → List<MessageMail>` — connects over IMAP(S), reads the last `totalMessageScan` messages, keeps only ones whose subject **exactly equals** `subjectMail`, and for each runs a jsoup CSS-selector (`cssQuery`, default `"b"` if blank) over the HTML/plain/multipart body to pull out matching element text — built for parsing structured notification emails (e.g. bank transaction alerts).
- `getAttributes(@Nullable MimeMessage message, cssQuery) → List<String>` — the same jsoup extraction, standalone (returns `[]` for a null message).

**`MessageMail`** — builder-style value object: `contentAttributes: List<String>` (the jsoup-extracted values), `recipients`, `sendDate`, `receivedDate`.

```java
mailService.sendHtmlMail("to@example.com","Subject","<h1>Hello</h1>");
mailService.

sendAttachmentsMail("to@example.com","Subject","<p>See attachment</p>","/path/to/file.pdf");
mailService.

sendHtmlMail("smtp.custom.com",587,"user","pass","to@example.com","Subject","<p>Content</p>");
```

**Gotchas**: `scanMessage` matches subjects by **exact equality**, not substring/regex — mismatches are silently skipped (logged at `info`). All failures inside `scanMessage`/`getAttributes` are caught and logged, returning an empty list rather than propagating — don't expect exceptions from these two methods, check for an empty result instead.

---
