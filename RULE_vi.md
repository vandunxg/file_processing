# Quy tắc lập trình — `file_processing`

> Đây là hợp đồng lập trình áp dụng cho mọi AI agent và mọi thành viên đóng góp
> vào repository. Nghiệp vụ nằm trong [`AGENTS.md`](./AGENTS.md). Các thành phần
> dùng chung nằm trong [`LIBRARY.md`](./LIBRARY.md). Bản tiếng Anh là
> [`RULE.md`](./RULE.md).

Khi các quy tắc xung đột, áp dụng thứ tự ưu tiên sau:

1. `AGENTS.md` quyết định hành vi nghiệp vụ và yêu cầu sản phẩm.
2. File này quyết định cách thiết kế và triển khai code.
3. `LIBRARY.md` mô tả API dùng chung hiện có.
4. Thói quen hoặc sở thích cá nhân.

Không được tự diễn giải lại quy tắc trong im lặng. Hãy tạo change request khi
một quy tắc không còn phù hợp với codebase.

**Kiến trúc mục tiêu: Pragmatic Modular DDD** — modular monolith chia package
theo business module với các layer `api` / `application` / `domain` /
`infrastructure`. Các package Hexagonal `adapter/*` và `port/*` còn lại trong
`src/main/java` là legacy implementation đang chờ migrate, không phải guidance.
Đọc §4 trước khi viết hoặc review bất kỳ code nào.

---

## 1. Mức độ bắt buộc

Tài liệu sử dụng ba mức độ yêu cầu.

- **MUST — bắt buộc** bảo vệ tính đúng, bảo mật, ranh giới kiến trúc hoặc độ ổn
  định production. Không được merge code vi phạm nếu chưa có ngoại lệ được phê
  duyệt.
- **SHOULD — nên dùng** là cách triển khai mặc định. Có thể chọn cách khác nếu
  pull request giải thích rõ vì sao cách đó dễ hiểu hoặc an toàn hơn.
- **MAY — tùy chọn** là hướng dẫn có thể áp dụng khi phù hợp.

Luôn chọn thiết kế nhỏ nhất nhưng vẫn bảo vệ được ranh giới cần thiết. Không tạo
thêm abstraction, interface, package, framework hoặc hạ tầng chỉ cho một yêu cầu
có thể xuất hiện trong tương lai.

---

## 2. Đọc codebase trước khi thay đổi

Trước khi lên kế hoạch hoặc sửa code, phải xác định hành vi hiện tại và kiểm tra
khả năng tái sử dụng.

1. Dùng CodeGraph trước đối với thay đổi chưa quen, xuyên nhiều layer hoặc liên
   quan nhiều file.

- MCP: gọi `codegraph_explore` trước, sau đó dùng `codegraph_node` khi cần
  đọc toàn bộ source hoặc caller.
- Shell fallback: `codegraph explore "<question or symbols>"` và
  `codegraph node <symbol-or-file>`.

2. Đọc phần nghiệp vụ liên quan trong `AGENTS.md` trước khi thay đổi behavior.
3. Tìm trong `LIBRARY.md` trước khi tạo utility, base class, mapper, DTO,
   repository helper hoặc configuration dùng chung.
4. Đọc trực tiếp source và test bị ảnh hưởng. Source và test hiện tại là nguồn
   đúng nếu index sinh tự động đã cũ.
5. Dùng `grep`, `find` hoặc đọc file trực tiếp cho các chi tiết CodeGraph chưa
   trả về.

Đối với thay đổi cục bộ, rõ ràng và chỉ nằm trong một file, đọc trực tiếp source
là đủ. Không cần chạy discovery toàn repository chỉ để sửa typo hoặc hằng số cục
bộ.

---

## 3. Công nghệ nền tảng

Nền tảng của dự án được giữ nhỏ và ổn định.

| Khu vực     | Tiêu chuẩn                                                 |
|-------------|------------------------------------------------------------|
| Ngôn ngữ    | Java 21                                                    |
| Framework   | Spring Boot 4.1.x, version được pin bởi parent POM         |
| Build       | Maven wrapper, `./mvnw`                                    |
| Persistence | PostgreSQL, Spring Data JPA, Hibernate                     |
| Migration   | Flyway trong `src/main/resources/db/migration`             |
| Security    | Spring Security, JWT access token, rotating refresh token  |
| Cache       | `com.vandunxg.common:common-cache`                         |
| Messaging   | `com.vandunxg.common:common-amqp`, chỉ dùng khi có yêu cầu |
| Mapping     | MapStruct cho mapping không đơn giản                       |
| Logging     | SLF4J, version do Spring Boot BOM quản lý                  |
| Format      | Spotless và Google Java Format                             |
| i18n        | Spring `MessageSource` tại `classpath:i18n/messages`       |
| Testing     | JUnit 5, Mockito, AssertJ, Testcontainers                  |
| API docs    | Springdoc OpenAPI 3.x cho Spring Boot 4                    |

**MUST NOT — không được** thêm ORM, messaging platform, mapping framework,
CQRS framework, event-sourcing framework, feature-flag platform hoặc runtime
dependency lớn mới nếu chưa có change request rõ ràng.

Không override version dependency đã được Spring Boot BOM quản lý trừ khi có lý
do tương thích hoặc bảo mật đã được kiểm chứng.

---

## 4. Kiến trúc: Pragmatic Modular DDD

Kiến trúc mục tiêu của repository này là:

```text
Modular Monolith
        +
Chia package theo business module (bounded context)
        +
Pragmatic DDD
```

Đây **không phải** Hexagonal / ports-and-adapters và **không phải** strict
Clean Architecture.

<!-- prettier-ignore -->
> [!IMPORTANT]
> **Legacy so với target.** Một phần `src/main/java` vẫn dùng layout Hexagonal
> cũ: `adapter/in`, `adapter/out`, `application/port/in`,
> `application/port/out`, `*UseCase`, `*RepositoryPort`, `*PersistenceAdapter`.
> Phần code đó là **legacy implementation đang chờ migrate**, không phải
> architecture guidance. Code mới và refactor **phải** theo section này, kể cả
> bên trong module vẫn còn layout cũ. Không thêm Hexagonal ceremony ở bất kỳ đâu.

Mental model cho mọi thay đổi:

```text
api             "Caller muốn gì?"
      ↓
application     "Workflow nào phải xảy ra?"
      ↓
domain          "Business cho phép điều gì?"
      ↑
infrastructure  "Capability kỹ thuật được thực hiện thế nào?"
```

Độ phức tạp kiến trúc **phải** xuất phát từ độ phức tạp nghiệp vụ, không bao giờ
từ một template. Mọi abstraction phải trả lời được một câu hỏi:

> Boundary hoặc business problem nào khiến abstraction này cần tồn tại?

Nếu không trả lời được, không tạo abstraction đó.

### 4.1 Cấu trúc module

Business module nằm dưới:

```text
src/main/java/com/vandunxg/file_processing/<module>/
```

Một module có bốn layer mang nghĩa ngữ nghĩa. Đây là ranh giới dependency, không
phải template folder bắt buộc. Chỉ tạo package và type mà module thực sự cần.

```text
<module>/
├── api/                          # HTTP contract của module
│   ├── <Xxx>Controller.java
│   ├── dto/request/
│   ├── dto/response/
│   └── mapper/
├── application/                  # orchestration use case, transaction boundary
│   ├── <Xxx>Properties.java      # typed setting của module, cả 2 tầng đều thấy
│   ├── service/                  # <Capability>CommandService / <Capability>QueryService
│   ├── capability/               # contract cho infrastructure triển khai (EmailSender, ...)
│   ├── command/
│   ├── query/
│   ├── result/
│   └── exception/
├── domain/                       # aggregate, invariant, behavior, repository
│   ├── model/
│   ├── <Aggregate>Repository.java
│   ├── policy/
│   ├── event/
│   ├── service/
│   └── exception/
└── infrastructure/               # triển khai công nghệ
    ├── persistence/
    ├── cache/
    ├── messaging/
    ├── storage/
    ├── email/
    ├── security/
    ├── client/
    ├── scheduling/
    ├── bootstrap/
    └── config/
```

Quy tắc:

- **Không được** tạo `adapter/in`, `adapter/out`, `port/in` hoặc `port/out`.
- **Không được** tạo package rỗng hoặc placeholder type chỉ để giống sơ đồ.
- `infrastructure` chia theo **công nghệ hoặc capability**
  (`persistence`, `cache`, `messaging`, `storage`), không bao giờ chia theo
  hướng (`in`, `out`).
- Entry point kỹ thuật không phải HTTP — scheduler, AMQP listener, bootstrap khi
  khởi động — nằm trong `infrastructure/<technology>/` và gọi application
  service. Chúng là infrastructure, không phải một inbound layer riêng.
- `infrastructure/config` dùng để wire bean và cross-cutting concern. Giữ lớp
  này mỏng và không chứa business rule.

### 4.2 Quy tắc dependency

```text
api ──────────────► application ──────────────► domain
                                                   ▲
infrastructure ────────────────────────────────────┘
        └─────────► application
```

Được phép:

| Từ               | Đến                                                   |
|------------------|-------------------------------------------------------|
| `api`            | `application`                                         |
| `api`            | domain type, khi response mapping thực sự cần         |
| `application`    | `domain`                                              |
| `infrastructure` | `application`, `domain`                               |

Bị cấm (**không được**):

- `domain` → `api`, `application` hoặc `infrastructure`.
- `application` → `api`.
- `application` → JPA entity, Spring Data repository, HTTP client, broker
  client, object-storage client hoặc bất kỳ infrastructure type nào khác.
- module A → `infrastructure` của module B, hoặc persistence implementation của
  module B, hoặc persistence model của module B.
- `domain` import Spring, Jackson, servlet hoặc HTTP type. Mapping
  `jakarta.persistence` trực tiếp trên aggregate chỉ được phép theo §6.4.

`application` **có thể** dùng annotation transaction và component của Spring.

### 4.3 Mức độ phức tạp

Chọn mức mỏng nhất phù hợp với bài toán. Không nâng mức chỉ để kiến trúc trông
"enterprise".

**Level 1 — CRUD hoặc capability kỹ thuật mỏng**

```text
api → application → infrastructure
```

`domain` chỉ xuất hiện khi có domain behavior thật cần bảo vệ.

**Level 2 — core business capability (mặc định)**

```text
api → application → domain, được infrastructure triển khai
```

Có aggregate, business invariant, một repository cho mỗi aggregate root, và
value object khi chúng mang giá trị ngữ nghĩa.

**Level 3 — workflow phức tạp**

Chỉ thêm những gì workflow thực sự cần: domain event, policy, domain service,
gateway, outbox, specification, process manager, saga.

Một capability Level 1 **không được** xây theo Level 3.

### 4.4 Ranh giới giữa các module

Module sở hữu dữ liệu và behavior của chính nó. Module khác **không được** đọc
hoặc ghi dữ liệu đó trực tiếp.

Bị cấm:

```text
fileimport ──► Spring Data repository của auth
fileimport ──► persistence model của auth
```

Ưu tiên:

```text
fileimport application ──► auth application capability
```

hoặc, khi việc decouple bất đồng bộ thực sự có giá trị:

```text
fileimport ──► domain / application event ──► auth
```

Không tạo interface cross-module nếu gọi trực tiếp application service của
module kia đã đủ.

### 4.5 Interface không gây ceremony

**Concrete application service là mặc định.** Controller, listener và scheduler
gọi trực tiếp class cụ thể.

Chỉ tạo interface khi tồn tại boundary thật:

- repository contract của aggregate do `domain` sở hữu và `infrastructure`
  triển khai;
- external service, storage provider, email provider, distributed cache, event
  publisher hoặc cryptographic provider;
- thực tế đã có nhiều hơn một implementation;
- contract giữa các module được giữ ổn định có chủ đích, kèm lý do rõ ràng.

**Không** tạo interface vì lý do "DDD", "Clean Architecture", "Hexagonal", "để
mock dễ hơn" hoặc "sau này có thể thay implementation". Spring bean vẫn mock
được mà không cần interface.

**Không được** tạo:

- interface `<Capability>UseCase` mà caller duy nhất là một controller;
- một interface cho mỗi class, hoặc một interface cho mỗi method nhỏ.

Có thể nhóm các operation liên quan vào một contract nếu contract vẫn dễ hiểu.

### 4.6 Đặt tên theo ý nghĩa, không theo pattern

`Port` **không phải** suffix mặc định. Dùng tên business hoặc capability:

| Không dùng             | Dùng             |
|------------------------|------------------|
| `UserRepositoryPort`   | `UserRepository` |
| `EmailSenderPort`      | `EmailSender`    |
| `JwtIssuerPort`        | `TokenIssuer`    |
| `StoragePort`          | `FileStorage`    |

`Adapter` **không phải** suffix mặc định. Dùng tên công nghệ hoặc capability:

| Không dùng                      | Dùng                   |
|---------------------------------|------------------------|
| `UserPersistenceAdapter`        | `JpaUserRepository`    |
| `BcryptPasswordHasherAdapter`   | `BcryptPasswordHasher` |
| `RedisAuthThrottleAdapter`      | `RedisAuthThrottle`    |
| `MailServiceEmailSenderAdapter` | `SmtpEmailSender`      |
| `R2ObjectStorageAdapter`        | `R2FileStorage`        |

Tên class nói implementation **là gì**, không nói nó theo pattern nào.

### 4.7 Command, Query và Result không ceremony

Một use case **không bắt buộc** phải có đủ bộ ba `Command`, `Query`, `Result`.
Truyền trực tiếp value hoặc request record là đúng khi boundary đủ đơn giản.

Tạo `Command`, `Query` hoặc `Result` ở application khi:

- nhiều caller — controller, listener, scheduler — cùng gọi một use case;
- HTTP contract cần tiến hoá độc lập với use case;
- input mang business meaning riêng, ví dụ normalization hoặc value object;
- workflow đủ nhiều bước để một named input làm nó rõ hơn;
- type được reuse ngoài HTTP.

**Không được** áp CQRS ceremony cho CRUD thông thường. Một `Command` chỉ bọc một
`UUID` thì thêm một file mà không bỏ được gì.

Việc tách module thành `<Capability>CommandService` và
`<Capability>QueryService` là mặc định cho module có luồng đọc và ghi rõ rệt, và
là tuỳ chọn với module nhỏ. Đây là lựa chọn tổ chức code, không phải áp dụng
CQRS.

---

## 5. Naming và Java style

Tên phải thể hiện ý nghĩa nghiệp vụ.

| Khái niệm                                | Quy ước                                 |
|------------------------------------------|-----------------------------------------|
| Domain aggregate                         | `User`, `Role`, `ProcessingJob`         |
| Domain value object                      | `EmailAddress`, `FileChecksum`          |
| Domain enum                              | `UserStatus`, `JobStatus`               |
| Repository contract của aggregate        | `<Aggregate>Repository`                 |
| Domain policy                            | `<Rule>Policy`                          |
| Domain event                             | `<Aggregate><PastTenseFact>`            |
| Application service ghi                  | `<Capability>CommandService`            |
| Application service đọc                  | `<Capability>QueryService`              |
| Application service khi một class là đủ  | `<Capability>Service`                   |
| Contract của external capability         | `EmailSender`, `FileStorage`, `TokenIssuer` |
| Input ghi                                | `<Action>Command`                       |
| Input đọc                                | `<Action>Query`                         |
| Output application                       | `<Action>Result`                        |
| Controller                               | `<Xxx>Controller`                       |
| Request DTO                              | `<Xxx>Request`                          |
| Response DTO                             | `<Xxx>Response`                         |
| Persistence model tách riêng             | `<Xxx>Entity`                           |
| Triển khai JPA của domain contract       | `Jpa<Aggregate>Repository`              |
| Spring Data interface trên persistence model tách riêng | `<Xxx>EntityRepository` |
| Spring Data custom fragment              | `<Xxx>EntityRepositoryCustom`           |
| Infrastructure implementation khác       | `<Technology><Capability>`              |
| Configuration                            | `<Xxx>Configuration`                    |
| Unit test                                | `<ClassName>Test`                       |
| Integration test                         | `<ClassName>IT`                         |

Chi tiết về naming repository:

- `domain/<Aggregate>Repository` là contract. Nó nói bằng domain type.
- `Jpa<Aggregate>Repository` trong `infrastructure/persistence` **luôn** là
  triển khai JPA của contract đó. Theo §6.4, nó thường là một Spring Data
  interface extends cả `JpaRepository<...>` và domain contract, nên không có
  class nào phát sinh thêm. Khi persistence model tách riêng là hợp lý, nó là
  một class `@Repository` delegate sang `<Xxx>EntityRepository` và thực hiện
  mapping.
- `<Xxx>EntityRepository` là Spring Data interface tầng thấp trên persistence
  model tách riêng. Nó **không được** inject ra ngoài
  `infrastructure/persistence`.

Quy ước bổ sung:

- Method dùng động từ camel-case.
- Boolean method bắt đầu bằng `is`, `has` hoặc `can`.
- Enum constant và constant dùng `SCREAMING_SNAKE_CASE`.
- Tránh viết tắt nếu đó không phải thuật ngữ domain đã thống nhất.
- Method tập trung vào một mục đích. Ưu tiên early return thay vì lồng nhiều cấp.
- Ưu tiên record cho command, query, result và value object bất biến.
- Không dùng `Optional` làm field, DTO field, entity field hoặc parameter.
- Trả collection rỗng thay vì `null`.
- Chỉ query có phân trang mới extends `PagingQuery`. Point lookup dùng record
  bình thường hoặc identifier trực tiếp.

---

## 6. Domain model

Domain object biểu diễn trạng thái và behavior nghiệp vụ. Nó trả lời *business
cho phép điều gì*. Nó không bao giờ là model HTTP. Việc nó có đồng thời là
persistence model hay không được quyết định theo §6.4.

### 6.1 Khởi tạo và invariant

- Aggregate **phải** bảo vệ invariant qua constructor, factory và behavior
  method rõ ràng.
- Không bắt buộc một bộ Lombok annotation cố định cho mọi domain class.
- Không thêm no-argument constructor chỉ để tiện. Constructor phục vụ JPA nằm
  trên JPA entity, không nằm trên domain model.
- Builder **có thể** dùng trong test hoặc khi khởi tạo phức tạp, nhưng **không
  được** bỏ qua invariant bắt buộc.
- Equality **phải** theo identity của domain. Không generate equality từ mutable
  field nếu chưa xem xét hậu quả.
- Thay đổi trạng thái qua method nghiệp vụ như `activate()`, `delete()`,
  `changeName()`, không dùng public setter.

Ví dụ:

```java

@Getter
public final class Role extends AuditableDomain {

  private final UUID id;
  private String name;
  private RoleStatus status;
  private Instant deletedAt;

  private Role(UUID id, String name) {
    this.id = Objects.requireNonNull(id);
    this.name = requireValidName(name);
    this.status = RoleStatus.ACTIVE;
  }

  public static Role create(String name) {
    return new Role(IdUtils.nextId(), name);
  }

  public void rename(String newName) {
    this.name = requireValidName(newName);
  }

  public void delete(Instant now) {
    if (status == RoleStatus.ACTIVE) {
      throw new RoleRuleViolation(RoleRule.ROLE_MUST_BE_INACTIVE);
    }
    if (deletedAt == null) {
      deletedAt = Objects.requireNonNull(now);
    }
  }

  public boolean isDeleted() {
    return deletedAt != null;
  }
}
```

Pure domain exception **không được** chứa HTTP status hoặc biết response format.
Application exception chịu trách nhiệm chuyển domain failure sang response
contract chuẩn.

### 6.2 Thời gian

- Lưu timestamp bằng `Instant`.
- Inject `Clock` vào application code có behavior phụ thuộc thời gian.
- Không gọi trực tiếp `Instant.now()` trong business logic cần test xác định.
- Chỉ chuyển sang timezone người dùng tại API hoặc presentation boundary.

### 6.3 Một repository cho mỗi aggregate root

Repository contract thuộc về **aggregate root**, và chỉ thuộc về aggregate root.
Nó nằm trong `domain` và có tên `<Aggregate>Repository`.

```text
domain
├── Order              # aggregate root
├── OrderItem          # nằm trong Order aggregate
├── ShippingAddress    # nằm trong Order aggregate
└── OrderRepository    # repository duy nhất của aggregate này
```

- **Không được** tạo repository cho entity hoặc value object nằm bên trong một
  aggregate khác — không có `OrderItemRepository`, không có
  `ShippingAddressRepository`.
- Contract nói bằng domain type và domain language. Nó **không được** expose
  `Pageable`, `Specification`, `EntityManager`, JPA entity hoặc bất kỳ Spring
  Data type nào.
- Load và save aggregate như một khối. Không cho caller sửa entity bên trong qua
  cửa sau.
- Read model là **query concern**, không phải repository: đặt nó sau một
  application query service và giữ SQL hoặc projection trong
  `infrastructure/persistence`.
- Search có phân trang là read model. `count(query)` và `search(query)` nói
  bằng `PagingQuery` — một application type — nên **không được** nằm trên
  contract ở `domain`, vì như vậy `domain` sẽ phụ thuộc `application`. Khai báo
  chúng ở `application/capability/<Aggregate>SearchRepository`. Một class
  `Jpa<Aggregate>Repository` thường implement cả hai contract nên không sinh
  thêm implementation:

```text
domain/UserRepository                              # aggregate: load, save, invariant
application/capability/UserSearchRepository        # read model: count(query), search(query)
infrastructure/persistence/JpaUserRepository       # implement cả hai
```

### 6.4 Pragmatic JPA mapping

Project này **không** yêu cầu domain object luôn phải tách khỏi JPA mapping.

Khi domain model và persistence model về cơ bản cùng một hình dạng, và JPA
annotation không làm méo domain behavior, aggregate **có thể** mang mapping
`jakarta.persistence` trực tiếp:

```text
domain/model/Order.java   có @Entity
```

Trong trường hợp đó, `Jpa<Aggregate>Repository` thường là một Spring Data
interface duy nhất extends cả `JpaRepository<...>` và domain contract. Không có
model tách riêng và không có mapper.

Chỉ tách thành `Order` + `OrderEntity` + mapper khi có lý do thật:

- schema legacy hoặc do hệ thống khác sở hữu;
- persistence model khác domain model một cách đáng kể;
- nhiều storage model cho cùng một aggregate;
- aggregate phức tạp mà nhu cầu persistence làm méo domain;
- read model hoặc query model đặc thù.

**Không được** tạo ba object và hai mapper cho một CRUD model đơn giản nếu không
có lý do nào ở trên.

Hai giới hạn luôn áp dụng:

- Concern HTTP và Jackson **không được** leak vào `domain`. Không
  `@JsonProperty`, `@JsonIgnore`, servlet hoặc Spring Web type trên aggregate.
- Aggregate có JPA mapping vẫn phải bảo vệ invariant (§6.1). Constructor
  no-argument và setter mà Hibernate yêu cầu phải là `protected` hoặc
  package-private, và mutation vẫn đi qua business method.

Ghi lại lựa chọn này trong mô tả pull request khi module tách hai model.

---

## 7. Application error và i18n

Common web library sở hữu format error response cuối cùng. Mỗi module sở hữu
error catalog và message đa ngôn ngữ của mình.

### 7.1 Error name có prefix module

Mọi enum constant của application error **phải** bắt đầu bằng prefix module viết
hoa:

```text
<MODULE>_<ERROR_NAME>
```

Ví dụ:

```text
AUTH_INVALID_CREDENTIALS
ROLE_IS_ACTIVE
FILE_UNSUPPORTED_MEDIA_TYPE
PROCESSING_JOB_NOT_FOUND
```

Prefix là bắt buộc vì `ResponseError.getName()` đồng thời là i18n key toàn cục.
Không được dùng tên chung chung như `NOT_FOUND`, `INVALID_STATUS` hoặc
`ACCESS_DENIED`.

### 7.2 Error enum

Mỗi module sở hữu một error enum trong `application/exception` và implements
`ResponseError`.

```java

@Getter
@RequiredArgsConstructor
public enum RoleErrorCode implements ResponseError {

  ROLE_NOT_FOUND(40411, "Role not found", 404),
  ROLE_IS_ACTIVE(40913, "Role must be inactive before deletion", 409);

  private final Integer code;
  private final String message;
  private final int status;

  @Override
  public String getName() {
    return name();
  }
}
```

Quy tắc:

- Tên enum constant **phải** chứa prefix module.
- i18n key **phải** giống chính xác tên enum constant.
- Numeric business code **phải** unique trên toàn repository.
- Giữ format integer hiện tại: `{httpStatus}{2-digit sequence}`.
- Sequence là toàn repository theo HTTP status; không reset sequence cho từng
  module nếu việc đó tạo code trùng.
- Numeric code đã public **không được** đổi số nếu chưa có quyết định tương thích
  API.
- Fallback message viết bằng tiếng Anh và **không được** chứa secret hoặc PII.
- Dùng HTTP status chuẩn gần đúng nhất về semantic.

Thêm unit test scan toàn bộ enum implements `ResponseError` và fail khi numeric
code, enum name hoặc i18n key bắt buộc bị trùng hoặc thiếu.

### 7.3 File i18n

Mọi error name **phải** tồn tại trong cả hai file:

```text
src/main/resources/i18n/messages.properties
src/main/resources/i18n/messages_vi.properties
```

Ví dụ:

```properties
# messages.properties
ROLE_NOT_FOUND=Role not found
ROLE_IS_ACTIVE=Role must be inactive before deletion
```

```properties
# messages_vi.properties
ROLE_NOT_FOUND=Không tìm thấy vai trò
ROLE_IS_ACTIVE=Vai trò phải ở trạng thái không hoạt động trước khi xóa
```

Không dùng dotted key khi common exception handler resolve bằng
`error.getName()`.

### 7.4 Exception theo từng layer

- Domain code chỉ throw pure domain rule violation khi chính domain object enforce
  rule đó. "Pure" nghĩa là nó chỉ nêu tên rule bị vi phạm: không HTTP status,
  không numeric code, không i18n key. Hình dạng chuẩn là enum `<Module>Rule` cộng
  `<Module>RuleViolation extends RuntimeException` trong `domain/exception`.
- Application service chuyển domain violation hoặc application failure thành
  module-specific exception extends `ResponseException`. Giữ mapping
  rule → error ở một chỗ cạnh error enum, và translate ngay tại call site để
  mapping luôn nhìn thấy được:

```java
// application/exception/AuthErrorCode.java
public static AuthErrorCode from(AuthRule rule) {
  return switch (rule) {
    case ROLE_NOT_ASSIGNABLE -> ROLE_INVALID;
    case USER_ALREADY_VERIFIED -> USER_ALREADY_VERIFIED;
    // ...
  };
}

// application/service/RegistrationCommandService.java
try {
  user.verifyEmail(now);
} catch (AuthRuleViolation violation) {
  throw AuthException.of(violation);
}
```
- Infrastructure implementation chuyển technology exception thành
  application/module error có ý nghĩa khi caller có thể xử lý. Phải giữ original
  cause.
- Controller thông thường không tạo hoặc translate business exception.
- Không throw `IllegalArgumentException`, `RuntimeException` hoặc
  `NullPointerException` để biểu diễn business failure.

Ví dụ:

```java
public final class RoleException extends ResponseException {

  public RoleException(RoleErrorCode error, Object... params) {
    super(error, params);
  }

  public RoleException(
    String message, Throwable cause, RoleErrorCode error, Object... params) {
    super(message, cause, error, params);
  }
}
```

### 7.5 HTTP status

Các status ưu tiên cho application-defined error:

- `200`, `201`, `202`, `204` cho success;
- `400`, `401`, `403`, `404`, `409`, `413`, `415`, `429` cho client failure;
- `500`, `502`, `503`, `504` cho server hoặc dependency failure.

Status chuẩn do framework sinh như `405`, `406` vẫn hợp lệ. Khi dùng status khác
phải giải thích rõ semantic trong review; không được ép một tình huống vào status
sai chỉ để khớp allowlist.

---

## 8. Logging và observability

Log phục vụ điều tra sự cố. Log không phải error transport thứ hai và không được
trở thành nguồn lộ dữ liệu nhạy cảm.

### 8.1 Khai báo và format

Class cần log dùng SLF4J, thông thường qua Lombok:

```java

@Slf4j(topic = "ROLE-SERVICE")
@Service
public class RoleCommandService {
  // ...
}
```

Topic ổn định, dùng `UPPER-KEBAB-CASE`, thông thường theo
`<MODULE>-<FEATURE>`.

Message dùng format:

```text
[methodName] lowercase description key={} key={}
```

Ví dụ:

```java
log.info("[deactivate] role deactivated roleId={}",roleId);
log.

error("[store] object storage write failed fileId={}",fileId, exception);
```

### 8.2 Chỉ log một lần tại boundary có đủ context

- Không bắt buộc log trước mọi `throw`.
- Domain model **không được** log.
- Validation, not-found và business conflict dự kiến trước không bắt buộc log
  mặc định.
- Unexpected technical failure được log một lần tại layer có đủ operational
  context.
- Không log cùng một exception ở mọi layer khi chỉ rethrow.
- Security-sensitive event có thể dùng audit event hoặc metric riêng thay vì
  application warning log.
- Boundary dễ lỗi như external HTTP, object storage, retry, parser hoặc atomic
  claim **nên** có breadcrumb ngắn ở thời điểm bắt đầu và kết thúc bằng `debug`,
  `info` hoặc `warn` tùy giá trị vận hành.

### 8.3 Log level

| Level   | Khi sử dụng                                                          |
|---------|----------------------------------------------------------------------|
| `error` | Lỗi hệ thống bất ngờ cần operator xử lý; phải có cause               |
| `warn`  | Bất thường có thể phục hồi, retry, conflict hoặc dependency degraded |
| `info`  | Lifecycle event nghiệp vụ có giá trị vận hành                        |
| `debug` | Chi tiết điều tra, mặc định tắt ở production                         |
| `trace` | Chỉ dùng khi debug local                                             |

### 8.4 Dữ liệu nhạy cảm

Không bao giờ log:

- password, password hash, JWT, refresh token, reset token;
- storage credential, secret, authorization header;
- toàn bộ customer record, request body hoặc nội dung file;
- email đầy đủ hoặc số điện thoại đầy đủ.

Phải mask email và phone. UUID có thể log đầy đủ nếu đó là internal identifier
opaque.

Không dùng `System.out.println`, `printStackTrace`, nối chuỗi trong log hoặc
`log.info("entity={}", entity)` khi `toString()` có thể làm lộ dữ liệu.

---

## 9. Mapping

MapStruct là lựa chọn mặc định cho mapping đi qua layer boundary và có nhiều
field hoặc transformation rule.

### 9.1 Khi nào cần mapper

Dùng mapper riêng khi:

- persistence model tách riêng và aggregate phải được map sang nó (xem §6.4 —
  phần lớn aggregate không cần điều này);
- mapping có nhiều field, nested value, conversion hoặc ignored field;
- mapping được tái sử dụng;
- API model và application model cần thay đổi độc lập.

Controller có thể tạo trực tiếp command chỉ có một hoặc hai field khi mapping rõ
ràng và không chứa business transformation. Không tạo mapper chỉ để copy một
UUID.

### 9.2 Quy ước mapper

```java

@Mapper(
  componentModel = MappingConstants.ComponentModel.SPRING,
  unmappedTargetPolicy = ReportingPolicy.ERROR,
  unmappedSourcePolicy = ReportingPolicy.WARN)
public interface RolePersistenceMapper {

  Role toDomain(RoleEntity entity);

  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "lastModifiedAt", ignore = true)
  RoleEntity toNewEntity(Role domain);

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "lastModifiedAt", ignore = true)
  void updateEntity(Role domain, @MappingTarget RoleEntity entity);
}
```

Quy tắc:

- Dùng `componentModel = SPRING`.
- Dùng `unmappedTargetPolicy = ERROR`.
- Dùng `toNewEntity` cho insert và `@MappingTarget` cho update.
- Không thay thế managed JPA entity chỉ để apply update.
- Inject mapper interface, không khởi tạo bằng `new`.
- Giữ `lombok-mapstruct-binding` khi Lombok và MapStruct xử lý cùng class.

Không dùng ModelMapper, BeanUtils, Dozer, Orika, reflection mapping hoặc copy
field thủ công bên trong application service.

---

## 10. Configuration và secret

Configuration phải typed, được validate và fail-fast.

- Dùng record `@ConfigurationProperties` trong `configuration`.
- Thêm `@Validated` và Jakarta Validation constraint cho giá trị bắt buộc.
- Không rải `@Value` trong service.
- Dùng namespace `app.<module>.<key>`.
- Externalize giá trị phụ thuộc môi trường hoặc cần điều chỉnh khi vận hành.
  Không biến mọi constant thành configuration nếu không có lý do.
- Secret **không được** có default không an toàn.
- `.env.example` mô tả biến bắt buộc nhưng không chứa secret thật.
- Feature toggle dùng typed property đơn giản trừ khi có yêu cầu thật về flag
  platform.

Ví dụ:

```java

@Validated
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(
  @NotBlank String issuer,
  @NotBlank String audience,
  @NotBlank String secret,
  @NotNull Duration accessTokenExpiration,
  @NotNull Duration refreshTokenExpiration) {
}
```

```yaml
app:
  security:
    jwt:
      secret: ${JWT_SECRET}
      issuer: ${JWT_ISSUER:file-processing}
```

---

## 11. Persistence và transaction

`infrastructure/persistence` sở hữu toàn bộ database concern của một module:
JPA mapping, các Spring Data interface, và phần triển khai các domain repository
contract.

### 11.1 Quy tắc entity

Đọc §6.4 trước để quyết định module có cần persistence model tách riêng hay
không. Các quy tắc dưới đây áp dụng cho class nào mang JPA mapping.

- Persistence model tách riêng chỉ được tạo khi có lý do liệt kê ở §6.4.
- Mọi class có JPA mapping extends `AuditableEntity` khi common base phù hợp.
- Mọi class có JPA mapping **bắt buộc** kế thừa hoặc khai báo trạng thái soft
  delete bằng `Instant deletedAt`, map tới cột SQL `deleted_at`.
- Nếu `AuditableEntity` đã khai báo `deletedAt`, không được khai báo lại field
  này trong entity con.
- Tên Java chuẩn là `deletedAt`; tên cột SQL là `deleted_at`.
- Không dùng `deleted`, `isDeleted`, `deleteAt` hoặc boolean deletion column.
- Tên bảng và cột dùng `snake_case`.
- Dùng `@Version` cho aggregate có thể bị concurrent update.
- Database constraint là boundary cuối cùng bảo vệ tính đúng. Dùng `NOT NULL`,
  `UNIQUE`, `CHECK`, foreign key khi phù hợp.

### 11.2 Soft delete bắt buộc

Mọi thao tác delete thông thường của application đều là soft delete.

- Gán `deletedAt` bằng `Instant` hiện tại từ `Clock` đã inject.
- Delete lại object đã bị delete thông thường phải idempotent.
- Mọi business read và existence check **phải** có
  `deleted_at IS NULL`.
- Mọi uniqueness rule chỉ áp dụng cho dữ liệu đang sống **phải** dùng partial
  unique index.
- Repository thông thường **không được** expose `delete`, `deleteById` hoặc bulk
  hard delete cho application service.
- Physical delete chỉ được thực hiện trong retention/maintenance job rõ ràng sau
  khi hết retention period.
- Khi hỗ trợ restore, gán `deletedAt` về `null` và kiểm tra lại uniqueness cùng
  business invariant.

Ví dụ migration:

```sql
CREATE TABLE roles
(
  id               UUID PRIMARY KEY,
  code             VARCHAR(100) NOT NULL,
  name             VARCHAR(255) NOT NULL,
  status           VARCHAR(30)  NOT NULL,
  version          BIGINT       NOT NULL DEFAULT 0,
  created_at       TIMESTAMPTZ  NOT NULL,
  last_modified_at TIMESTAMPTZ  NOT NULL,
  deleted_at       TIMESTAMPTZ NULL
);

CREATE UNIQUE INDEX roles_active_code_uidx
  ON roles (code) WHERE deleted_at IS NULL;

CREATE INDEX roles_deleted_at_idx
  ON roles (deleted_at) WHERE deleted_at IS NOT NULL;
```

### 11.3 Flyway

- `spring.jpa.hibernate.ddl-auto` **phải** là `validate`.
- Mọi schema change phải có Flyway migration.
- Tên migration dùng
  `V{yyyyMMddHHmm}__{snake_case_description}.sql`.
- Migration là append-only. Không sửa migration sau khi đã merge hoặc đã apply
  ngoài local disposable database.
- Sửa lỗi bằng migration mới.

### 11.4 Transaction boundary

- Đặt transaction boundary trên application service method, không đặt trên
  controller và không đặt trên domain object. `domain` không bao giờ mở, commit
  hoặc rollback transaction.
- Dùng `@Transactional(readOnly = true)` cho read-only use case.
- Transaction phải ngắn.
- Không gọi HTTP, email, object storage hoặc message broker bên trong database
  transaction trừ khi có consistency requirement rõ ràng.
- Publish sau commit hoặc dùng outbox khi cần atomic consistency giữa database
  và message.
- Map optimistic-lock conflict thành error `409 Conflict` có ý nghĩa.
- Không retry mù một transaction không idempotent.

### 11.5 Hiệu năng JPA

- Association mặc định là `LAZY`.
- Không dùng `EAGER` để sửa `LazyInitializationException`.
- Tắt Open Session in View.
- Giải quyết N+1 rõ ràng bằng fetch join, `EntityGraph`, projection hoặc batch
  fetching.
- Mọi list endpoint có page size giới hạn và sort xác định.
- Chỉ thêm index cho query pattern đã được xác minh, không index mọi column.
- Review generated SQL cho repository change không đơn giản.

---

## 12. API layer

Controller chuyển HTTP request thành application use case. Controller nằm trong
`<module>/api` và gọi trực tiếp một concrete application service (§4.5).

- Controller chỉ chứa request validation, mapping, gọi application service và
  tạo response.
- Business decision và transaction boundary không nằm trong controller.
- Request/response contract dùng DTO, không dùng JPA entity hoặc domain
  aggregate.
- Wrap response bằng common `Response<T>` hoặc `PagingResponse<T>`.
- User-facing error message phải qua i18n.
- Dùng API prefix và version từ configuration.
- Giới hạn pagination ngay tại request boundary.
- Endpoint list, search và completion có dữ liệu không giới hạn **MUST** theo
  convention paging chung: request DTO extends `PagingRequest`, controller
  parameter dùng `@ValidatePaging(sortModel = <Xxx>Entity.class)`, application
  query extends `PagingQuery`,
  `application/capability/<Aggregate>SearchRepository` có `count(query)` và
  `search(query)` (§6.3), và
  controller trả `PagingResponse<T>`.
- `@ValidatePaging` dựng allow-list sort bằng reflection trên các field có
  `@jakarta.persistence.Column`, nên `sortModel` **MUST** là class JPA của cùng
  module. Đây là chỗ duy nhất `api` được phép gọi tên persistence model, và chỉ
  như validation metadata — không dùng làm parameter, return type hay field.
- Không ép paging cho bounded catalog, enum list, JWKS, `/me`, hoặc list chỉ
  trong phạm vi current-user nếu product behavior chưa yêu cầu rõ.
- Không tin proxy forwarding header nếu deployment chưa cấu hình trusted proxy.

Ví dụ:

```java

@RestController
@RequestMapping("${app.api.prefix}/${app.api.version}/roles")
@RequiredArgsConstructor
public class RoleController {

  private final RoleCommandService roleCommandService;
  private final RoleWebMapper roleWebMapper;

  @DeleteMapping("/{roleId}")
  public Response<Void> delete(@PathVariable UUID roleId) {
    roleCommandService.delete(roleId);
    return Response.noContent();
  }
}
```

Dùng `201 Created` kèm `Location` header khi common response contract hỗ trợ.
Chỉ dùng `202 Accepted` khi xử lý tiếp tục bất đồng bộ sau khi response đã trả.

---

## 13. OpenAPI

OpenAPI mô tả contract thật; nó không thay thế runtime validation.

- Metadata và security scheme toàn cục nằm trong một
  `OpenApiConfiguration`.
- Dùng Springdoc OpenAPI 3.x với Spring Boot 4.
- Public endpoint phải opt out rõ ràng khỏi bearer requirement toàn cục.
- Dùng `@Schema` cho description và example.
- Dùng Jakarta Validation cho required field, size, range và format.
- Không expose entity, internal cause, secret hoặc implementation detail trong
  schema.
- Tái sử dụng bearer scheme constant đã cấu hình thay vì lặp string literal.

---

## 14. File processing và công việc bất đồng bộ

File operation phải bounded, streaming, idempotent khi cần và có observability.

### 14.1 File I/O

- Stream upload và download. Không dùng `MultipartFile.getBytes()`,
  `Files.readAllBytes()` hoặc load toàn file không giới hạn vào memory.
- Enforce maximum file size trước bước xử lý tốn tài nguyên.
- Validate content bằng parser hoặc signature tin cậy; không chỉ tin extension
  hoặc media type do client gửi.
- Tạo storage name phía server. Không dùng raw user filename làm object key hoặc
  filesystem path.
- Normalize và validate path để chống path traversal.
- Tính checksum trong lúc stream khi cần integrity hoặc deduplication.
- Xóa temporary file trong `finally` hoặc managed lifecycle.

### 14.2 Executor và job

- Không tạo unbounded executor hoặc queue.
- Tính concurrency theo CPU, memory, database và downstream limit.
- Persist job state nếu công việc phải sống qua process restart.
- Định nghĩa legal state transition và test đầy đủ.
- Operation có thể bị external retry phải idempotent bằng idempotency key,
  unique constraint hoặc atomic claim.
- Mọi external call phải có timeout.
- Chỉ retry transient failure, số lần hữu hạn và có backoff.
- Không retry validation failure, authorization failure hoặc permanent `4xx`.
- Chống retry storm bằng backoff, jitter, circuit breaker và concurrency limit
  khi phù hợp.

---

## 15. Security

Security rule là MUST requirement.

- Deny mặc định và chỉ cấp permission tối thiểu cần thiết.
- Enforce authorization tại application/security boundary, không chỉ ẩn action
  trên UI.
- Kiểm tra ownership và tenant boundary cho mọi resource access.
- Không tin role hoặc permission từ request payload.
- JWT role/permission mapping phải rõ ràng và có test.
- Refresh-token rotation phải phát hiện token reuse và revoke cả token family.
- Password dùng adaptive password hash được Spring Security hỗ trợ.
- Không làm lộ username/email có tồn tại nếu gây account enumeration.
- File name, media type, archive entry và parser input đều là dữ liệu không tin
  cậy.
- Dùng rate limiting cho authentication và endpoint dễ bị abuse.
- Security event cần audit trail hoặc metric với identifier đã mask.
- Không commit secret hoặc log authentication material.

---

## 16. Tái sử dụng common library

Tái sử dụng các thành phần cross-cutting ổn định từ common library.

Các thành phần thường bắt buộc tái sử dụng:

| Nhu cầu               | Thành phần dùng chung                          |
|-----------------------|------------------------------------------------|
| Auditable domain base | `AuditableDomain`                              |
| Auditable JPA base    | `AuditableEntity`                              |
| Response wrapper      | `Response<T>`, `PagingResponse<T>`             |
| Page result           | `PageDTO<T>`                                   |
| Paging query          | `PagingQuery`                                  |
| Current user          | `SecurityUtils`                                |
| Sinh UUID             | `IdUtils.nextId()`                             |
| Hashing               | `HashUtils`                                    |
| Date helper           | `DateUtils`                                    |
| String helper         | `StrUtils`                                     |
| Jackson configuration | `MapperFactoryUtils.jacksonMapper()`           |
| Cache                 | `CacheService`, `@CacheAction`, `@CacheUpdate` |
| AMQP publishing       | `AmqpEventPublisher`                           |

Trước khi dùng shared API, kiểm tra signature hiện tại trong `LIBRARY.md` hoặc
source. Không được đoán API.

Helper riêng của module nên để local. Chỉ đưa code vào common library khi có ít
nhất hai consumer thật cần cùng một abstraction ổn định. Không bump common
library chỉ để chia sẻ một helper mang tính dự đoán.

---

## 17. Testing

Test bảo vệ behavior quan sát được và rủi ro production.

### 17.1 Test level

- JUnit 5, Mockito, AssertJ thuần cho application/domain unit test.
- `@WebMvcTest` cho MVC controller slice khi hữu ích.
- `@DataJpaTest` hoặc Testcontainers test tập trung cho repository behavior.
- Chỉ dùng `@SpringBootTest` cho behavior cần toàn bộ application context.
- Dùng PostgreSQL Testcontainers cho SQL, migration, locking, index và JPA
  behavior khác in-memory database.

### 17.2 Khu vực bắt buộc test

Behavior mới hoặc thay đổi **phải** test các path quan trọng, đặc biệt:

- authorization và ownership;
- state transition;
- soft delete và loại bỏ row đã delete;
- partial uniqueness sau soft delete;
- idempotency và duplicate delivery;
- optimistic locking và atomic claim;
- file limit, malformed input và cleanup;
- transaction rollback và after-commit behavior;
- timeout, retry classification và fallback;
- error-code uniqueness và i18n key completeness.

Refactor không thay đổi observable behavior không cần tạo test giả tạo. Coverage
là diagnostic metric, không phải acceptance criterion chính. Không viết test vô
nghĩa chỉ để đạt một con số.

Tên test mô tả scenario:

```text
delete_throwsRoleIsActive_whenRoleIsStillActive()
delete_setsDeletedAt_whenRoleIsInactive()
findById_returnsEmpty_whenRoleWasSoftDeleted()
```

Test phải deterministic. Không dùng hidden sleep. Inject `Clock`, kiểm soát
executor completion và chờ trên observable condition.

---

## 18. Formatting, build và Git

Spotless là bắt buộc.

```bash
./mvnw spotless:apply
./mvnw spotless:check
./mvnw verify
```

Trước khi commit, chạy:

```bash
./mvnw spotless:apply && ./mvnw verify
```

Không bypass hook bằng `--no-verify` nếu user hoặc repository maintainer chưa
phê duyệt rõ ràng.

Dùng Conventional Commits:

```text
type(scope): imperative lowercase subject
```

Các type thường dùng: `feat`, `fix`, `refactor`, `perf`, `docs`, `test`,
`chore`, `style`, `ci`, `build`.

Subject dưới 72 ký tự, không có dấu chấm cuối và mô tả intent. Body giải thích
lý do khi lý do không hiển nhiên.

Không amend hoặc force-push shared branch nếu chưa được phê duyệt.

---

## 19. Pattern bị từ chối

Reject hoặc sửa mọi change có các pattern sau nếu chưa có ngoại lệ được ghi rõ:

1. Business logic hoặc transaction trong controller.
2. Một interface cho mọi class hoặc một interface cho mỗi method nhỏ.
3. Package rỗng hoặc placeholder type chỉ để giống sơ đồ.
4. Package `adapter/in`, `adapter/out`, `port/in` hoặc `port/out` mới.
5. Interface `<Capability>UseCase` mới, hoặc type `*RepositoryPort`,
   `*StoragePort`, `*PersistenceAdapter` mới.
6. Dùng suffix `Port` hoặc `Adapter` làm naming convention mặc định.
7. Repository cho entity hoặc value object nằm bên trong một aggregate khác.
8. Expose Spring Data type (`Pageable`, `Specification`, `EntityManager`,
   entity) trên domain repository contract.
9. Application service phụ thuộc JPA entity, Spring Data repository, HTTP
   client, broker client hoặc object-storage client.
10. Module đọc hoặc ghi persistence model hoặc persistence implementation của
    module khác.
11. Persistence model tách riêng kèm mapper cho một CRUD aggregate đơn giản mà
    không có lý do theo §6.4.
12. Tạo `Command`, `Query` hoặc `Result` cho boundary chỉ có một giá trị đơn
    giản, trái §4.7.
13. Domain code import Spring, Jackson, servlet hoặc HTTP type, hoặc thêm JPA
    mapping vào aggregate ngoài các điều kiện ở §6.4.
14. Domain exception chứa HTTP status hoặc response-format detail.
15. Controller advice mới trùng chức năng common exception handler.
16. Business failure dùng bare runtime exception.
17. Log trước mọi `throw` hoặc log cùng exception ở mọi layer.
18. Nuốt exception rồi trả `null` hoặc sentinel value.
19. ModelMapper, BeanUtils, Dozer, Orika hoặc reflection field copy.
19a. Copy field-by-field viết tay — builder chain hoặc constructor đọc getter
    từ một source object — nằm trong service, repository, controller hoặc
    factory `from(...)` trên DTO, trong khi §9.1 yêu cầu MapStruct mapper.
    Dựng object từ nhiều nguồn hoặc từ các giá trị scalar không phải mapping
    và vẫn để ở caller.
20. Thay managed JPA entity thay vì update có chủ đích.
21. Field injection bằng `@Autowired`.
22. `System.out.println`, `printStackTrace` hoặc log nối chuỗi.
23. Log token, password, secret, full PII, request body hoặc file data.
24. User-facing message hard-code ngoài i18n.
25. Error enum name không có prefix module.
26. Numeric business error code bị trùng.
27. Thiếu error key ở file tiếng Anh hoặc tiếng Việt.
28. Controller trả JPA entity hoặc domain aggregate.
29. Sửa Flyway migration đã apply.
30. `ddl-auto` đặt thành `create`, `update`, `create-drop` ngoài local experiment
    có thể xóa bỏ.
31. JPA entity không có `deletedAt` và `deleted_at`.
32. Business read không loại row soft-deleted.
33. Application service gọi repository hard-delete method.
34. Dùng `EAGER` để che N+1 hoặc session-boundary problem.
35. Gọi network hoặc object storage trong database transaction dài.
36. Page size, executor, queue, retry hoặc file memory load không giới hạn.
37. `MultipartFile.getBytes()`, `Files.readAllBytes()` hoặc tương đương trên
    content không giới hạn.
38. Secret có default configuration không an toàn.
39. Thêm framework hoặc infrastructure mới khi chưa có requirement được duyệt.

---

## 20. Checklist trước commit

- [ ] Đã đọc source, test, `AGENTS.md`, `LIBRARY.md` liên quan.
- [ ] Change dùng thiết kế nhỏ nhất nhưng vẫn bảo vệ boundary có thật.
- [ ] Domain code không phụ thuộc Spring, Jackson, servlet hoặc HTTP; mọi JPA
  mapping trên aggregate thoả §6.4.
- [ ] Interface mới bảo vệ một boundary thật liệt kê ở §4.5.
- [ ] Không có `adapter/in|out`, `port/in|out`, `*UseCase`, `*RepositoryPort`
  hoặc `*PersistenceAdapter` mới được thêm vào.
- [ ] Hướng dependency theo §4.2, và không module nào truy cập infrastructure
  của module khác.
- [ ] Mỗi aggregate root có tối đa một repository contract trong `domain`.
- [ ] Mọi entity mới kế thừa hoặc khai báo `deletedAt` map tới `deleted_at`.
- [ ] Business read dùng `deleted_at IS NULL`, trừ màn hình thùng rác rõ ràng.
- [ ] Uniqueness của live data dùng partial unique index phù hợp.
- [ ] Schema change dùng Flyway migration mới, append-only.
- [ ] Transaction ngắn và nằm trên application service.
- [ ] External call có timeout và nằm ngoài DB transaction nếu không có lý do
  rõ ràng.
- [ ] JPA list query đã kiểm tra N+1, pagination giới hạn và sort ổn định.
- [ ] Error enum name bắt đầu bằng prefix module.
- [ ] Numeric business error code là unique.
- [ ] i18n key tiếng Anh và tiếng Việt giống chính xác error enum name.
- [ ] Error chỉ được log một lần tại boundary phù hợp và không có dữ liệu nhạy
  cảm.
- [ ] Mapping không đơn giản qua boundary dùng MapStruct; update dùng
  `@MappingTarget`.
- [ ] Secret không có default không an toàn.
- [ ] File và async operation bounded, streaming và idempotent khi cần.
- [ ] Behavior mới có deterministic test theo mức rủi ro.
- [ ] `./mvnw spotless:apply && ./mvnw verify` chạy thành công.
- [ ] Commit tuân theo Conventional Commits.
