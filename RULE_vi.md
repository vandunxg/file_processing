# Quy tắc lập trình — file_processing

> **File này là contract code cho mọi AI agent (Claude, Gemini, Cursor,
> Copilot, …) và mọi người tham gia repo.**
> Business behaviour nằm ở [`AGENTS.md`](./AGENTS.md).
> Danh mục base class / util tái sử dụng nằm ở [`LIBRARY.md`](./LIBRARY.md).
> Bản tiếng Anh: [`RULE.md`](./RULE.md).

Thứ tự ưu tiên khi rule mâu thuẫn: `AGENTS.md` (business) > file này (how to
code) > thói quen cá nhân. Không được diễn giải lại rule một cách âm thầm.
Nếu thấy rule sai, mở change request.

---

## 1. Đọc codebase trước (workflow bắt buộc)

Trước khi viết, sửa, hoặc lên kế hoạch code, làm theo đúng thứ tự sau.

1. **CodeGraph** (nhanh, dùng index). Repo này đã index tại `.codegraph/`.
   - MCP tools: gọi `codegraph_explore` trước — 1 call trả source verbatim của
     các symbol liên quan + đường call giữa chúng. Fallback qua `codegraph_node`
     để đọc cả file hoặc 1 symbol kèm caller.
   - Shell fallback: `codegraph explore "<câu hỏi hoặc symbol>"` và
     `codegraph node <symbol-hoặc-file>`.
2. **`LIBRARY.md`** — scan xem base class / util cần dùng đã có trong common
   lib chưa. Có rồi thì reuse.
3. **`AGENTS.md`** — đọc section business tương ứng trước khi đổi hành vi.
4. Chỉ dùng `grep`, `find`, `Read` khi CodeGraph không đủ chi tiết.

✅ Nên: `codegraph_explore "LoginService AuthController User"` trước khi động
vào code auth.
❌ Không nên: mở đại vài file rồi đoán flow, hoặc viết lại 1 util đã có trong
`com.vandunxg.common.utils.*`.

---

## 2. Tech baseline (không đổi)

| Layer          | Công nghệ                                                       |
|----------------|-----------------------------------------------------------------|
| Ngôn ngữ       | Java 21                                                          |
| Framework      | Spring Boot 4.1.x (đã pin trong `pom.xml`)                       |
| Build          | Maven (wrapper `./mvnw`)                                         |
| Persistence    | PostgreSQL + Spring Data JPA + Hibernate                         |
| Migration      | Flyway (`src/main/resources/db/migration`)                       |
| Security       | Spring Security + JWT (access + rotating refresh)                |
| Cache          | `com.vandunxg.common:common-cache` (Redis)                       |
| Messaging      | `com.vandunxg.common:common-amqp` (chỉ khi spec yêu cầu)         |
| Mapping        | MapStruct (compile-time). ModelMapper **không** dùng cho code mới. |
| Logging        | SLF4J 2.0.17 qua Lombok `@Slf4j`                                 |
| Format         | Spotless + Google Java Format 1.27.0                             |
| i18n           | Spring `MessageSource` tại `classpath:i18n/messages`             |
| Test           | JUnit 5 + Mockito + AssertJ + Testcontainers                     |
| API docs       | Springdoc OpenAPI (`springdoc-openapi-starter-webmvc-ui`)        |

**Không tự ý thêm** framework, messaging, hay ORM mới nếu chưa có change
request. Cấm Kafka, CQRS framework, Event Sourcing, native image, viết lại
không dùng Lombok. Xem thêm `AGENTS.md` phần "Patterns to reject".

---

## 3. Cấu trúc package (Hexagonal, theo module)

Mỗi business module đặt ở
`src/main/java/com/vandunxg/file_processing/<module>/`. Module `auth` là layout
tham chiếu — copy nguyên shape cho module mới.

```
<module>/
├── adapter/
│   ├── in/
│   │   └── web/
│   │       ├── <Xxx>Controller.java          # REST controller mỏng
│   │       ├── dto/
│   │       │   ├── request/  <Xxx>Request.java
│   │       │   └── response/ <Xxx>Response.java
│   │       └── mapper/       <Xxx>WebMapper.java
│   ├── out/
│   │   └── persistence/
│   │       ├── entity/       <Xxx>Entity.java, Jpa<Xxx>Repository.java
│   │       ├── mapper/       <Xxx>PersistenceMapper.java
│   │       └── <Xxx>PersistenceAdapter.java  # implements *RepositoryPort
│   └── shared/                                # helper riêng của module
├── application/
│   ├── port/
│   │   ├── in/               <Xxx>UseCase.java   (interface)
│   │   └── out/              <Xxx>RepositoryPort.java, <Xxx>...Port.java
│   ├── service/              <Xxx>Service.java   (@Service, implements UseCase)
│   ├── command/              <Xxx>Command.java   (input write-side)
│   └── query/                <Xxx>Query.java     (input read-side, extends PagingQuery)
├── domain/
│   ├── model/                <Xxx>.java   (extends AuditableDomain, KHÔNG Spring/JPA)
│   └── exception/            <Xxx>ErrorCode.java (implements ResponseError)
└── configuration/            <Xxx>Configuration.java (@Configuration bean)
```

Rule dependency (hexagonal):

- `domain/` chỉ phụ thuộc: `com.vandunxg.common.models.domain`, `.exception`,
  `.error`, `common.utils`, và `java.*`. Không gì khác.
- `application/` phụ thuộc `domain/` + common models. **Không** phụ thuộc
  adapter, Spring Web, hay JPA annotation.
- `adapter/` phụ thuộc `application/` + `domain/`. Adapter là chỗ duy nhất
  được viết `@RestController`, `@Entity`, `@Repository`, `RestClient`, v.v.
- `configuration/` chỉ wire bean, giữ mỏng.

✅ Nên: đặt `AuthController` trong `auth/adapter/in/web/`.
❌ Không nên: gắn annotation JPA lên class trong `domain/model`.

---

## 4. Naming convention

| Khái niệm                       | Suffix / pattern                             | Vị trí                                |
|---------------------------------|----------------------------------------------|---------------------------------------|
| Domain aggregate / entity       | `User`, `AuditLog`                           | `domain/model/`                       |
| Enum domain                     | `UserStatus`, `OperationType`                | `domain/model/`                       |
| Bảng mã lỗi của module          | `<Module>ErrorCode`                          | `domain/exception/`                   |
| Use case inbound (interface)    | `<Xxx>UseCase`                               | `application/port/in/`                |
| Port outbound (interface)       | `<Xxx>RepositoryPort`, `<Xxx>NotifierPort`   | `application/port/out/`               |
| Impl use case                   | `<Xxx>Service` (`@Service`)                  | `application/service/`                |
| Input write-side                | `<Xxx>Command`                               | `application/command/`                |
| Input read-side                 | `<Xxx>Query` (extends `PagingQuery`)         | `application/query/`                  |
| REST controller                 | `<Xxx>Controller`                            | `adapter/in/web/`                     |
| HTTP request DTO                | `<Xxx>Request` (extends `Request`)           | `adapter/in/web/dto/request/`         |
| HTTP response DTO               | `<Xxx>Response` (extends `BaseResponse` nếu có audit) | `adapter/in/web/dto/response/` |
| Web mapper                      | `<Xxx>WebMapper`                             | `adapter/in/web/mapper/`              |
| JPA entity                      | `<Xxx>Entity` (extends `AuditableEntity`)    | `adapter/out/persistence/entity/`     |
| Spring Data repository          | `Jpa<Xxx>Repository`                         | `adapter/out/persistence/entity/`     |
| Persistence adapter             | `<Xxx>PersistenceAdapter`                    | `adapter/out/persistence/`            |
| Persistence mapper              | `<Xxx>PersistenceMapper` (implements `EntityMapper<D,E>`) | `adapter/out/persistence/mapper/` |
| Spring configuration            | `<Xxx>Configuration`                         | `configuration/`                      |
| Scheduled job                   | `<Xxx>Scheduler` / vd `SystemGC`             | `configuration/` hoặc feature package |
| Test class                      | `<ClassName>Test` (unit) / `<ClassName>IT` (integration) | `src/test/java` (mirror package) |

Method name là `camelCase` (verb). Method trả boolean bắt đầu bằng `is` /
`has` / `can`. Enum value viết `SCREAMING_SNAKE_CASE`. Constant khai
`static final` và `SCREAMING_SNAKE_CASE`.

---

## 5. Rule cho domain model

Mọi aggregate cần persist đều kế thừa `AuditableDomain` từ
`com.vandunxg.common.models.domain`. Combo Lombok cố định:

```java
package com.vandunxg.file_processing.auth.domain.model;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.models.domain.AuditableDomain;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Setter(AccessLevel.PRIVATE)
@EqualsAndHashCode(callSuper = false)
public class User extends AuditableDomain {

  private UUID id;
  private String username;
  private String password;
  private String email;
  private Instant deletedAt;
}
```

Quy tắc:

- `@Setter(AccessLevel.PRIVATE)` — muốn mutate phải qua method behaviour trong
  chính domain, không set từ ngoài.
- `@SuperBuilder` vì kế thừa `AuditableDomain`.
- ID là `UUID`, sinh bằng `IdUtils.nextId()` (xem `LIBRARY.md`).
- Soft-delete dùng `deletedAt` (`Instant`), không dùng boolean flag.

✅ Nên: thêm method behaviour (vd `user.deactivate()`) ngay trên aggregate.
❌ Không nên: import `jakarta.persistence.*`, `org.springframework.*`, hay
`javax.validation.*` trong `domain/`.

---

## 6. Xử lý exception và error ⭐

Common lib đã cung cấp full pipeline. **Không viết lại.**

### 6.1 Định nghĩa bảng mã lỗi của module

Mỗi module có đúng 1 enum implements
`com.vandunxg.common.models.error.ResponseError`. Enum đặt ở
`<module>/domain/exception/`.

```java
package com.vandunxg.file_processing.auth.domain.exception;

import org.springframework.http.HttpStatus;
import com.vandunxg.common.models.error.ResponseError;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ResponseError {

  INVALID_CREDENTIALS(40101, "auth.error.invalid_credentials", HttpStatus.UNAUTHORIZED),
  ACCOUNT_LOCKED     (40301, "auth.error.account_locked",     HttpStatus.FORBIDDEN),
  USER_NOT_FOUND     (40401, "auth.error.user_not_found",     HttpStatus.NOT_FOUND),
  REFRESH_TOKEN_REUSED(40102,"auth.error.refresh_token_reused",HttpStatus.UNAUTHORIZED);

  private final Integer code;      // business code trả client
  private final String messageKey; // i18n key, resolve qua LocaleStringService
  private final HttpStatus http;

  @Override public String getName()    { return name(); }
  @Override public String getMessage() { return messageKey; }
  @Override public int getStatus()     { return http.value(); }
  @Override public Integer getCode()   { return code; }
}
```

Quy tắc mã code: `{httpStatus}{2 chữ số mã module}` (auth = `01`, file-import
= `02`, customer = `03`, …). Dành `xx000` cho lỗi "chưa phân loại".

### 6.2 Log context **trước khi** throw

Mọi `throw` mà kết thúc 1 request đều phải có 1 dòng log ngay trước đó, ghi
lại đầy đủ context nghiệp vụ. Không có dòng log này thì lúc trace sau này
phải chạy lại request mới hiểu. Rule này áp dụng ở **cả** domain và
application layer.

- **Level:** `warn` cho lỗi nghiệp vụ (sai mật khẩu, không đủ quyền, không
  tìm thấy resource), `error` cho lỗi hệ thống thực sự đi kèm throw.
- **Format** theo §8.2: `[methodName] <cái gì fail> key=value key=value`.
  Kèm ID cần thiết để trace (user id, resource id, số lần retry, IP client,
  trace id nếu chưa có trong MDC). Không log password, token, row khách hàng.
- Không log lại **cùng 1 lỗi** ở mọi layer khi re-throw — 1 log ở điểm quyết
  định là đủ. Layer trên có thể log thêm context nó biết (vd controller log
  thêm request path).

```java
// application/service/LoginService.java
public LoginResult login(LoginCommand cmd) {
  var user = userRepository.findByUsername(cmd.getUsername()).orElse(null);
  if (user == null) {
    log.warn("[login] user not found username={} ip={}", cmd.getUsername(), cmd.getIpAddress());
    throw new AuthDomainException(AuthErrorCode.INVALID_CREDENTIALS);
  }
  if (!passwordEncoder.matches(cmd.getPassword(), user.getPassword())) {
    log.warn("[login] invalid password userId={} ip={}", user.getId(), cmd.getIpAddress());
    throw new AuthDomainException(AuthErrorCode.INVALID_CREDENTIALS);
  }
  if (user.getStatus() == UserStatus.INACTIVE) {
    log.warn("[login] inactive account userId={} status={}", user.getId(), user.getStatus());
    throw new AuthDomainException(AuthErrorCode.ACCOUNT_LOCKED, user.getId());
  }
  ...
}
```

**Cũng log breadcrumb quanh code dễ bug** — gọi HTTP ngoài, I/O MinIO / S3,
điểm race DB, vòng retry, biên parser. Log input state *trước* call rủi ro
ở level `info` / `debug`, log kết quả (`success` / `retry` / `failed`) *sau*.
Nếu code chỗ đó chết, đọc log phải dựng lại được flow.

```java
log.debug("[claimJob] attempting atomic claim jobId={} workerId={}", jobId, workerId);
int updated = jobRepository.tryClaim(jobId, workerId);
if (updated == 0) {
  log.warn("[claimJob] already taken by another worker jobId={}", jobId);
  throw new JobDomainException(JobErrorCode.CONCURRENT_CLAIM);
}
log.info("[claimJob] claimed jobId={} workerId={}", jobId, workerId);
```

### 6.3 Domain exception thuộc về domain

Layer `domain/` không được depend vào symbol của
`com.vandunxg.common.models.exception` với tên gốc. Mỗi module có 1 **exception
class riêng thuộc domain**, extend `ResponseException` để cùng format
wire-level với `ExceptionHandleAdvice`, nhưng đọc trong code domain lại là
1 khái niệm domain.

```java
// auth/domain/exception/AuthDomainException.java
package com.vandunxg.file_processing.auth.domain.exception;

import com.vandunxg.common.models.error.ResponseError;
import com.vandunxg.common.models.exception.ResponseException;

public class AuthDomainException extends ResponseException {

  public AuthDomainException(ResponseError error) {
    super(error);
  }

  public AuthDomainException(ResponseError error, Object... params) {
    super(error, params);
  }

  public AuthDomainException(String message, Throwable cause, ResponseError error,
                             Object... params) {
    super(message, cause, error, params);
  }
}
```

Phân bổ throw theo layer:

| Layer          | Throw cái gì                                                                                            |
|----------------|---------------------------------------------------------------------------------------------------------|
| `domain/`      | `<Module>DomainException(<Module>ErrorCode.XXX, …)` — dùng đúng từ vựng domain.                          |
| `application/` | Cũng dùng domain exception khi lỗi thuộc domain rule. Dùng `ResponseException` common lib khi lỗi cross-cutting không thuộc domain nào (vd `BadRequestError.INVALID_INPUT`). |
| `adapter/`     | Không tự throw `ResponseException`; hoặc để domain throw, hoặc wrap lỗi upstream rồi throw lại bằng domain exception của module. |

Vì `AuthDomainException extends ResponseException`, `ExceptionHandleAdvice`
đang có sẵn tự bắt qua parent type — **không cần viết advice mới** và format
`ErrorResponse` trên dây giữ nguyên. Đó chính là ý "cùng 1 chuẩn format".

Nếu 1 module có 2 catalog lỗi tách biệt (vd `file-import` chia thành
`ImportFileErrorCode` và `ProcessingJobErrorCode`), có thể thêm domain
exception thứ 2 (`ImportFileDomainException`, `ProcessingJobDomainException`)
— cả 2 vẫn extend `ResponseException`.

### 6.4 Message i18n

Mọi `messageKey` phải tồn tại ở **cả** `messages.properties` (English) và
`messages_vi.properties` (Việt):

```properties
# src/main/resources/i18n/messages.properties
auth.error.invalid_credentials=Invalid username or password
auth.error.account_locked=Account {0} is locked
```

```properties
# src/main/resources/i18n/messages_vi.properties
auth.error.invalid_credentials=Sai tên đăng nhập hoặc mật khẩu
auth.error.account_locked=Tài khoản {0} đang bị khoá
```

### 6.5 Quy tắc

✅ Nên:
- Định nghĩa 1 `<Module>ErrorCode implements ResponseError` cho mỗi module.
- Định nghĩa 1 `<Module>DomainException extends ResponseException` cho mỗi
  module.
- **Log context ở `warn`/`error` ngay trước `throw`** — 1 dòng cho mỗi điểm
  quyết định, đủ ID để trace sau này.
- Thêm breadcrumb log ở `info`/`debug` quanh code path dễ bug (I/O ngoài,
  retry, race point) để lỗi có thể dựng lại chỉ từ log.
- Mỗi mã lỗi mới đưa vào enum của **chính module đó**, không nhét sang module
  khác.
- Để `ExceptionHandleAdvice` (auto-config bởi `common-web`) format response.

❌ Không nên:
- Throw thẳng `ResponseException` từ `domain/` — dùng `<Module>DomainException`.
- Throw `IllegalArgumentException`, `RuntimeException`, `NullPointerException`
  để báo lỗi nghiệp vụ.
- `throw new AuthDomainException(...)` **mà không có dòng log ngay trước đó**.
- Log lại cùng lỗi ở mỗi layer khi re-throw — 1 log ở điểm quyết định là đủ.
- Viết `@RestControllerAdvice` riêng nếu chưa extend `ExceptionHandleAdvice`.
- Catch `Exception e` trong service để `return null` hoặc giá trị sentinel.
- Hard-code message tiếng Anh trong Java — luôn dùng i18n key.
- Truyền token / password / row khách hàng vào error params hoặc log.

---

## 7. Convention MapStruct (mapping)

**Dùng MapStruct. Không dùng ModelMapper, BeanUtils, hay reflection.** MapStruct
sinh code mapper tại compile time nên không có reflection runtime, sai lệch
field lộ ngay lúc compile, và code generated đọc được ở
`target/generated-sources/annotations/`.

### 7.1 Cấu hình Maven

Thêm MapStruct song song Lombok trong `pom.xml`. Vì cả 2 đều là annotation
processor, phải khai báo cùng lúc trong `annotationProcessorPaths` **đúng
thứ tự** (`lombok-mapstruct-binding` là cầu nối).

```xml
<properties>
  <mapstruct.version>1.6.3</mapstruct.version>
  <lombok-mapstruct-binding.version>0.2.0</lombok-mapstruct-binding.version>
</properties>

<dependencies>
  <dependency>
    <groupId>org.mapstruct</groupId>
    <artifactId>mapstruct</artifactId>
    <version>${mapstruct.version}</version>
  </dependency>
</dependencies>

<!-- trong maven-compiler-plugin executions/default-compile/configuration -->
<annotationProcessorPaths>
  <path>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
  </path>
  <path>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok-mapstruct-binding</artifactId>
    <version>${lombok-mapstruct-binding.version}</version>
  </path>
  <path>
    <groupId>org.mapstruct</groupId>
    <artifactId>mapstruct-processor</artifactId>
    <version>${mapstruct.version}</version>
  </path>
</annotationProcessorPaths>
```

Cùng commit này bỏ dependency `org.modelmapper:modelmapper` và property
`modelmapper.version`.

### 7.2 Shape của mapper

Mọi mapper là 1 **interface** annotate `@Mapper(componentModel = "spring")`
— MapStruct sinh impl là Spring bean; inject interface ở chỗ cần dùng.

Mapper domain ↔ JPA entity implement `EntityMapper<D, E>` từ `common-models`
để 4 method chuẩn thống nhất toàn codebase:

```java
// adapter/out/persistence/mapper/UserPersistenceMapper.java
package com.vandunxg.file_processing.auth.adapter.out.persistence.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import com.vandunxg.common.models.mapper.EntityMapper;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.UserEntity;
import com.vandunxg.file_processing.auth.domain.model.User;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR,
    unmappedSourcePolicy = ReportingPolicy.WARN)
public interface UserPersistenceMapper extends EntityMapper<User, UserEntity> {

  @Override User toDomain(UserEntity entity);

  @Override
  @Mapping(target = "createdAt",  ignore = true)   // audit do JPA lifecycle set
  @Mapping(target = "lastModifiedAt", ignore = true)
  UserEntity toEntity(User domain);

  @Override List<User> toDomain(List<UserEntity> entities);
  @Override List<UserEntity> toEntity(List<User> domains);
}
```

Web mapper (DTO ↔ command / response) là interface `@Mapper` bình thường —
không cần implement `EntityMapper` vì không dính entity:

```java
// adapter/in/web/mapper/AuthWebMapper.java
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AuthWebMapper {

  @Mapping(target = "ipAddress", source = "ipAddress")
  LoginCommand toCommand(LoginRequest request, String ipAddress);

  LoginResponse toResponse(LoginResult result);
}
```

### 7.3 Quy tắc

✅ Nên:
- 1 mapper interface / phía adapter: `<Xxx>PersistenceMapper` trong
  `adapter/out/persistence/mapper/`, `<Xxx>WebMapper` trong
  `adapter/in/web/mapper/`.
- Luôn `componentModel = "spring"`.
- Đặt `unmappedTargetPolicy = ReportingPolicy.ERROR` để quên field là build
  fail, không phải prod fail.
- Implement `EntityMapper<D, E>` cho mapper domain↔entity.
- Nếu 2 class Lombok cần map lẫn nhau, giữ processor path
  `lombok-mapstruct-binding`.

❌ Không nên:
- `new ModelMapper()`, bean `ModelMapper`, hay config `TypeMap` — xoá dần khi
  đụng tới file.
- `BeanUtils.copyProperties`, `Apache BeanUtils`, hay tự viết reflection.
- Nói chung: mapping library dùng reflection lúc runtime.
- Copy field bằng tay trong service (`domain.setX(entity.getX())`) — dồn vào
  mapper.
- Khởi tạo mapper bằng `new` — luôn inject Spring bean.

---

## 8. Logging (SLF4J)

### 8.1 Khai báo

Mọi class có log dùng Lombok:

```java
@Slf4j(topic = "AUTH-LOGIN")   // UPPER-KEBAB-CASE, mô tả class/feature
@Service
public class LoginService implements LoginUseCase {
  ...
}
```

Topic đang có trong repo: `SYSTEM-UTIL`, `SYSTEM-GC`. Pattern:
`<MODULE>[-<FEATURE>]`. Một class một topic, giữ ổn định.

### 8.2 Format message — **`[methodName] description key={} key={}`**

Luôn mở đầu message bằng `[methodName]` (tên method Java đang log). Description
tiếng Anh lowercase, tham số kiểu `key={}` với placeholder `{}`. Giá trị đưa
vào varargs cuối.

```java
public void runSystemGC() {
  log.info("[runSystemGC] starting gc trigger");
  long start = System.currentTimeMillis();
  SystemUtil.gc();
  log.info("[runSystemGC] finished gc trigger durationMs={}", System.currentTimeMillis() - start);
}

public LoginResponse login(LoginCommand cmd) {
  log.info("[login] attempt username={}", cmd.getUsername());
  var user = userRepository.findByUsername(cmd.getUsername())
      .orElseThrow(() -> new ResponseException(AuthErrorCode.INVALID_CREDENTIALS));
  ...
  log.info("[login] success userId={} ip={}", user.getId(), cmd.getIpAddress());
  return response;
}
```

### 8.3 Level

| Level   | Dùng khi                                                                              |
|---------|---------------------------------------------------------------------------------------|
| `error` | Lỗi hệ thống bất thường cần vận hành xử lý; kèm cause.                                |
| `warn`  | Bất thường có thể recover, retry, cancel hợp tác.                                     |
| `info`  | Sự kiện nghiệp vụ nhìn thấy được (login success, job started).                        |
| `debug` | Flow chi tiết dùng khi debug. Mặc định tắt ở prod.                                    |
| `trace` | Chỉ dùng local; tuyệt đối không sinh volume log ở prod.                               |

### 8.4 Dữ liệu nhạy cảm

**Không bao giờ** log: JWT, password, refresh token, storage credential, full
row khách hàng, email đầy đủ, phone đầy đủ, body của request upload.
**Luôn mask**: email `a***@domain`, phone `+84********99`. UUID có thể log
đầy đủ vì opaque.

### 8.5 Quy tắc

✅ Nên: `log.error("[claimJob] failed to claim jobId={} attempt={}", jobId, attempt, e);`
❌ Không nên:
- `System.out.println(...)`
- `e.printStackTrace()`
- `log.info("something happened: " + var)` (nối chuỗi thay `{}`)
- `log.info("user: {}", user)` khi `toString()` lộ password

---

## 9. Format code — Spotless (bắt buộc trước khi commit)

Spotless đã bind vào `mvn verify`. Nếu 1 file lệch format, build fail.

```
mvn spotless:apply    # reformat toàn bộ Java
mvn spotless:check    # chỉ verify (CI chạy cái này)
```

Rule đã cấu hình trong `pom.xml`:

- Google Java Format 1.27.0
- Thứ tự import: `java, javax, jakarta, org, com`
- Xoá import không dùng
- Trim trailing whitespace
- Kết file bằng newline

Toàn repo (`.editorconfig`): UTF-8, LF, indent 4 space cho `.java`, 2 space
cho YAML.

✅ Nên: chạy `mvn spotless:apply` trước `git add`.
❌ Không nên: commit với `--no-verify`. Nếu không đồng ý format là change
request lên `pom.xml`, không được exempt từng file.

---

## 10. Reuse từ common library

**Trước khi tự viết util, base class, mapper, DTO, config mới: grep
[`LIBRARY.md`](./LIBRARY.md).** Nếu capability đã có trong
`com.vandunxg.common:2.0.5`, dùng lại. Nếu thật sự thiếu, đề xuất bump version
common lib chứ đừng duplicate ở module.

Danh sách bắt buộc reuse (chưa đầy đủ):

| Nhu cầu                          | Dùng cái này                                                                          |
|----------------------------------|---------------------------------------------------------------------------------------|
| Auditable domain aggregate        | extend `com.vandunxg.common.models.domain.AuditableDomain`                            |
| Auditable JPA entity              | extend `com.vandunxg.common.models.entities.AuditableEntity`                          |
| Base HTTP request                 | extend `com.vandunxg.common.models.dto.request.Request`                               |
| Paged HTTP request                | extend `com.vandunxg.common.models.dto.request.PagingRequest`                         |
| Response wrapper                  | trả `com.vandunxg.common.models.dto.response.Response<T>` / `PagingResponse<T>`       |
| Response body có audit            | extend `com.vandunxg.common.models.dto.response.BaseResponse`                         |
| Page result                       | `com.vandunxg.common.models.dto.PageDTO<T>`                                           |
| Domain↔entity mapper contract     | implement `com.vandunxg.common.models.mapper.EntityMapper<D, E>`                      |
| Search/paging query base          | extend `com.vandunxg.common.persistence.query.PagingQuery`                            |
| Base custom JPA repository        | extend `com.vandunxg.common.persistence.repository.custom.BaseEntityRepositoryCustom` |
| Lỗi nghiệp vụ                     | throw `com.vandunxg.common.models.exception.ResponseException` + enum `ResponseError` |
| User hiện tại                     | `com.vandunxg.common.web.support.SecurityUtils.getCurrentUserLoginId()`               |
| i18n lookup                       | `com.vandunxg.common.web.i18n.LocaleStringService.getMessage(...)`                    |
| Sinh UUID                         | `com.vandunxg.common.utils.IdUtils.nextId()`                                          |
| Hash SHA-256                      | `com.vandunxg.common.utils.HashUtils.sha256(...)`                                     |
| Format/parse date                 | `com.vandunxg.common.utils.DateUtils`                                                 |
| String / email / phone helper     | `com.vandunxg.common.utils.StrUtils`                                                  |
| Jackson mapper                    | `com.vandunxg.common.utils.MapperFactoryUtils.jacksonMapper()`                        |
| Cache                             | inject `com.vandunxg.common.cache.service.CacheService` hoặc dùng `@CacheAction` / `@CacheUpdate` |
| AMQP publisher                    | `com.vandunxg.common.amqp.publisher.AmqpEventPublisher`                               |

Xem [`LIBRARY.md`](./LIBRARY.md) để có danh mục đầy đủ kèm signature.

---

## 11. Config và property

- Mọi giá trị cấu hình đặt trong `application.yaml` (hoặc profile file), đọc
  qua `${ENV_VAR:default}`.
- Namespace: `app.<module>.<key>` — vd `app.security.jwt.secret`,
  `app.gc.cron-time`.
- Không commit secret thật. `.env.example` liệt kê biến cần có.
- Feature toggle dùng property thường (`app.<module>.<feature>.enabled`),
  không dùng framework flag phức tạp.

Bind property qua `@ConfigurationProperties` record đặt trong `configuration/`
— không rải `@Value` khắp service.

```java
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(String issuer, String audience, String secret,
                            Duration accessTokenExpiration,
                            Duration refreshTokenExpiration,
                            Duration clockSkew) {}
```

---

## 12. Persistence

- JPA entity là class **tách riêng** khỏi domain model, đặt ở
  `adapter/out/persistence/entity/`, extend `AuditableEntity`.
- Tên bảng / cột dùng `snake_case`.
- Dùng `@Version` cho aggregate có nguy cơ race (optimistic locking).
- `application.yaml` đặt Hibernate `ddl-auto: validate` — **mọi** thay đổi
  schema đều đi qua Flyway migration.
- Tên file migration: `V{yyyyMMddHHmm}__{snake_case_description}.sql` trong
  `src/main/resources/db/migration/`. Prefix timestamp giúp tránh conflict
  version giữa dev.
- Migration append-only; không sửa migration đã merge. Có sai thì viết file
  `V…__` mới.
- Ưu tiên constraint DB (`UNIQUE`, `NOT NULL`, `CHECK`, foreign key) làm
  correctness boundary; validation và lock chỉ là tối ưu.

✅ Nên: `V202607170930__create_users_table.sql`.
❌ Không nên: sửa `V202607170900__…sql` sau khi đã apply.

---

## 13. Tầng API + i18n

Controller **mỏng**: nhận `Request`, build `Command`/`Query`, gọi use case,
bọc kết quả trong `Response<T>`.

```java
@RestController
@RequestMapping("${app.api.prefix}/${app.api.version}/auth")
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-CONTROLLER")
public class AuthController {

  private final LoginUseCase loginUseCase;
  private final AuthWebMapper webMapper;

  @PostMapping("/login")
  public Response<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                       HttpServletRequest http) {
    log.info("[login] username={} ip={}", request.getUsername(), http.getRemoteAddr());
    var command = webMapper.toCommand(request, http.getRemoteAddr());
    var result  = loginUseCase.login(command);
    return Response.of(webMapper.toResponse(result));
  }
}
```

Rule:

- Không đặt business logic trong controller. Chỉ annotation validation.
- Base path lấy từ config `app.api.prefix` + `app.api.version`.
- Message người dùng thấy đều resolve qua `i18n/messages*.properties`. Key
  dạng `snake.dotted`, group theo module (`auth.error.*`, `file.info.*`).
- Response body luôn là `Response<T>` (hoặc `PagingResponse<T>`) — không trả
  raw domain / entity.

---

## 14. OpenAPI / Springdoc + Schema DTO

### 14.1 Một class config trung tâm

Thêm `springdoc-openapi-starter-webmvc-ui` vào `pom.xml`. Mọi metadata OpenAPI
global và security scheme đặt trong **duy nhất 1** `@Configuration` bean,
không rải trên controller.

```java
// configuration/OpenApiConfiguration.java
@Configuration
@OpenAPIDefinition(
    info = @Info(title = "File Processing API", version = "v1"),
    security = { @SecurityRequirement(name = OpenApiConfiguration.BEARER_AUTH) }
)
@SecurityScheme(
    name = OpenApiConfiguration.BEARER_AUTH,
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT"
)
public class OpenApiConfiguration {
  public static final String BEARER_AUTH = "bearerAuth";
}
```

### 14.2 Annotation trên endpoint

Vì `@OpenAPIDefinition` phía trên đã đặt Bearer auth làm default, những
endpoint **public** (login, refresh, health) opt-out bằng
`@SecurityRequirements` rỗng:

```java
@Operation(summary = "Đăng nhập bằng username và password")
@SecurityRequirements                     // public — không cần bearer
@PostMapping("/login")
public Response<LoginResponse> login(@Valid @RequestBody LoginRequest req) { ... }

@Operation(
    summary = "Lấy thông tin người dùng hiện tại",
    security = @SecurityRequirement(name = OpenApiConfiguration.BEARER_AUTH))
@GetMapping("/me")
public Response<UserResponse> me() { ... }
```

Luôn dùng hằng `OpenApiConfiguration.BEARER_AUTH`, không viết lại string
literal trong controller.

### 14.3 DTO tự mô tả schema

Validation thật vẫn dùng Jakarta Validation (`@NotBlank`, `@Size`, `@Email`,
`@Min`, `@Max`, …). `@Schema` **chỉ** thêm mô tả và example — không phải
validator.

```java
public record LoginRequest(

    @Schema(description = "Tên đăng nhập", example = "operator01",
            minLength = 4, maxLength = 100)
    @NotBlank
    @Size(min = 4, max = 100)
    String username,

    @Schema(description = "Mật khẩu", example = "StrongPassword@123",
            format = "password")
    @NotBlank
    String password) {}
```

Nếu DTO cần kế thừa các field audit / correlation dùng chung, dùng class
extends `com.vandunxg.common.models.dto.request.Request` thay vì record —
cùng bộ annotation.

### 14.4 Quy tắc

✅ Nên:
- Đặt `@OpenAPIDefinition` / `@SecurityScheme` trên **1 Spring-managed bean**
  duy nhất.
- Dùng `@Schema` chỉ để mô tả + example; ràng buộc thực tế dùng Jakarta
  Validation.
- Trả `Response<XxxResponse>` (DTO) để schema ổn định.

❌ Không nên:
- Rải `@SecurityScheme` trên nhiều controller.
- Dùng `@Schema(required = true)` **thay thế** `@NotNull` / `@NotBlank` —
  Springdoc tự suy `required` từ validation annotation.
- Expose JPA `@Entity` làm OpenAPI response type. Nó lộ column, lazy proxy,
  và field audit không nên public.

---

## 15. Testing

- Framework: JUnit 5, Mockito, AssertJ. Integration: Testcontainers PostgreSQL.
- Vị trí mirror production:
  `src/test/java/.../auth/application/service/LoginServiceTest.java`.
- Naming:
  - `LoginServiceTest` — unit test, không cần Spring context.
  - `LoginServiceIT` — integration test, có Spring context + Testcontainers.
- Tên method mô tả scenario:
  `login_returnsAccessToken_whenCredentialsValid()`,
  `login_throwsInvalidCredentials_whenPasswordWrong()`.
- Mỗi behaviour mới đi kèm test. Refactor thuần không đổi behaviour thì không
  cần test mới; format-only tuyệt đối không cần.
- Dùng fixture / builder, tránh JSON file trừ khi chính test đó verify parse.

Ngưỡng coverage khuyến nghị theo module: 80% dòng, 100% cho state machine và
nhánh phân quyền. Con số là guidance, không phải hard gate — reviewer quyết.

---

## 16. Convention commit git

Theo Conventional Commits: `type(scope): subject`.

| Type       | Dùng cho                                            |
|------------|-----------------------------------------------------|
| `feat`     | Chức năng mới nhìn thấy được từ ngoài               |
| `fix`      | Sửa bug                                             |
| `refactor` | Đổi code mà không đổi behaviour                     |
| `perf`     | Tối ưu hiệu năng                                    |
| `docs`     | Chỉ tài liệu (file này, `AGENTS.md`, `LIBRARY.md`, README) |
| `test`     | Thêm hoặc sửa test                                  |
| `chore`    | Build config, bump dependency, tooling              |
| `style`    | Chỉ format (thường là `spotless apply`)             |
| `ci`       | Cấu hình CI                                         |
| `build`    | Maven, Docker, packaging                            |

- Subject dạng imperative, viết thường, không dấu chấm cuối. < 72 ký tự.
- Scope không bắt buộc nhưng có ích: `feat(auth): add refresh token rotation`.
- Body giải thích **vì sao**, không phải "làm cái gì". Tham chiếu requirement
  ID trong `AGENTS.md` khi áp dụng.
- Trước mọi commit: `mvn spotless:apply && mvn verify`. Chỉ skip khi user chỉ
  định rõ.
- Không amend hay force-push nhánh đã share mà chưa hỏi.

---

## 17. Anti-pattern (review sẽ reject)

Từ chối hoặc yêu cầu sửa lại mọi PR có 1 trong các pattern sau, trừ khi PR
description ghi rõ lý do được duyệt.

1. Business logic trong controller.
2. Interface cho mọi class (xem `AGENTS.md` — "do not create an interface for
   every class").
3. Domain model import Spring / JPA / Jackson annotation.
4. `@RestControllerAdvice` riêng duplicate `ExceptionHandleAdvice`.
5. Throw `IllegalArgumentException` / `RuntimeException` / `NullPointerException`
   để báo lỗi nghiệp vụ.
6. `catch (Exception e) { log.error(...); return null; }` — nuốt lỗi.
7. `BeanUtils.copyProperties(...)`, tự viết copy reflection, hoặc bất kỳ
   library mapping runtime-reflection nào (`ModelMapper`, Dozer, Orika).
8. Mapper viết dạng class `@Component` thay vì interface MapStruct
   `@Mapper(componentModel = "spring")`.
9. `@Autowired` trên field. Dùng constructor injection
   (`@RequiredArgsConstructor`).
10. `System.out.println` / `e.printStackTrace()`.
11. Nối chuỗi trong log message (`"user " + id`), lộ PII, hoặc log full JWT /
    password / row khách hàng.
12. Hard-code string tiếng Anh trả về client — phải đi qua i18n.
13. Duplicate util đã có trong `com.vandunxg.common.utils.*` — reuse hoặc
    bump version common lib.
14. Sửa Flyway migration đã merge.
15. Đặt `spring.jpa.hibernate.ddl-auto` khác `validate`.
16. Commit với `--no-verify` hoặc skip Spotless.
17. Dùng `@Schema(required = true)` thay cho `@NotNull` / `@NotBlank`.
18. Trả JPA `@Entity` trực tiếp từ controller.
19. Đặt `@OpenAPIDefinition` hoặc `@SecurityScheme` trên controller thay vì
    `OpenApiConfiguration`.
20. `MultipartFile.getBytes()`, `Files.readAllBytes()`, `readAllLines()`,
    executor không bound, và các pattern đã liệt kê ở `AGENTS.md` phần
    "Patterns to reject".

---

## Checklist nhanh trước khi commit

- [ ] Đã tra `codegraph_explore` / `LIBRARY.md` — không duplicate.
- [ ] Package layout đúng §3 cho mọi class mới.
- [ ] Domain không có Spring/JPA import.
- [ ] Lỗi mới → `<Module>ErrorCode`, throw qua `<Module>DomainException`
      (từ domain) hoặc `ResponseException` (từ application cross-cutting),
      key i18n đã có ở cả `messages.properties` và `messages_vi.properties`.
- [ ] Có 1 dòng `log.warn` / `log.error` **ngay trước** mỗi `throw` mới mà
      kết thúc request, đủ ID để trace.
- [ ] Code dễ bug (I/O ngoài, retry, race point) có breadcrumb log trước
      và sau call rủi ro.
- [ ] `@Slf4j(topic = "…")` trên class; message log bắt đầu bằng `[methodName]`
      và dùng `{}` placeholder; không lộ PII / token.
- [ ] Mapper là interface MapStruct `@Mapper(componentModel = "spring")`;
      không `ModelMapper`, `BeanUtils`, hay reflection.
- [ ] DTO có Jakarta Validation + `@Schema`. Không trả JPA entity làm
      response.
- [ ] Endpoint mới: security default (bearer) hoặc opt-out bằng
      `@SecurityRequirements` cho endpoint public.
- [ ] Schema đổi → Flyway migration mới `V{yyyyMMddHHmm}__*.sql`.
- [ ] Behaviour mới có test.
- [ ] `mvn spotless:apply && mvn verify` xanh.
- [ ] Commit theo `type(scope): subject`.
