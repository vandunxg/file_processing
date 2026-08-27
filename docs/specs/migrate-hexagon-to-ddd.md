# SPEC — Documentation Migration từ Hexagonal sang Pragmatic Modular DDD

## 1. Mục tiêu

Refactor toàn bộ architecture documentation của repository `file_processing`
từ convention Hexagonal Architecture hiện tại sang:

**Pragmatic Modular Domain-Driven Design (Pragmatic Modular DDD).**

Phase này chỉ thay đổi:

* architecture documentation;
* coding rules;
* package conventions;
* naming conventions;
* hướng dẫn dành cho AI agents;
* architecture examples trong Markdown.

Phase này **KHÔNG refactor production source code**.

Mục tiêu sau khi hoàn thành:

> Một AI agent mới đọc repository phải hiểu rằng kiến trúc chuẩn của project là
> Modular Monolith + DDD theo business module, không phải Hexagonal
> `ports-and-adapters`.

---

# 2. Architectural Decision

Target architecture:

```text
Modular Monolith
        +
Package by Business / Bounded Context
        +
Pragmatic DDD

<module>
├── api
├── application
├── domain
└── infrastructure
```

Không áp dụng strict Clean Architecture hoặc strict Hexagonal Architecture.

DDD được bảo vệ bằng:

1. Bounded Context / Module Boundary.
2. Aggregate Boundary.
3. Business Invariants.
4. Dependency Direction.
5. Explicit Transaction Boundary.
6. Ownership của business model.
7. Không để infrastructure quyết định domain model.

Không dùng số lượng interface/package/class để đánh giá một module "DDD đúng".

---

# 3. Nguyên tắc chống over-engineering

Architecture complexity phải xuất phát từ **business complexity**.

Không được tự động tạo:

```text
adapter/in
adapter/out

port/in
port/out

XxxUseCase
XxxUseCaseImpl

XxxRepositoryPort
XxxPersistenceAdapter

Command
Query
Result
Mapper
Factory
Specification
DomainEvent
```

chỉ vì architecture template có chúng.

Mỗi abstraction phải trả lời được:

> Boundary hoặc business problem nào khiến abstraction này cần tồn tại?

Nếu không trả lời được, abstraction không được tạo.

---

# 4. Scope

Agent phải scan **toàn bộ `*.md` trong repository**, bao gồm tối thiểu:

```text
AGENTS.md
RULE.md
RULE_vi.md
LIBRARY.md

docs/**/*.md
```

Đặc biệt:

```text
docs/specs/**
docs/superpowers/specs/**
docs/superpowers/plans/**
```

Không chỉ search từ khóa `Hexagonal`.

Phải phát hiện cả **Hexagonal semantics**, ví dụ:

```text
Hexagonal Architecture
Ports and Adapters
inbound port
outbound port

adapter/in
adapter/out
port/in
port/out

*UseCase
*RepositoryPort
*StoragePort
*PersistenceAdapter

inbound adapter
outbound adapter
```

Lưu ý:

`adapter`, `port`, `UseCase` có thể xuất hiện với nghĩa business hoặc networking.
Không được blind search-and-replace.

Agent phải đọc context trước khi sửa.

---

# 5. Out of scope

Phase này KHÔNG được:

* move Java source;
* rename Java package;
* rename Java class;
* sửa behavior;
* sửa API contract;
* sửa database schema;
* sửa Flyway migration;
* sửa security behavior;
* sửa business rule;
* sửa transaction behavior;
* thay dependency;
* thay Maven structure;
* refactor test source.

Code Hexagonal hiện tại được xem là:

```text
LEGACY IMPLEMENTATION
```

trong giai đoạn chuyển đổi.

Documentation mới định nghĩa:

```text
TARGET ARCHITECTURE
```

Code refactor sẽ được thực hiện ở phase riêng.

---

# 6. Source of truth

Giữ precedence:

```text
1. AGENTS.md
   Business behavior / business invariants / requirements

2. RULE.md + RULE_vi.md
   Engineering / architecture convention

3. LIBRARY.md
   Shared library contract

4. Feature specs
   Feature-specific requirements

5. Historical implementation plans
```

Architecture migration **không được thay đổi business behavior** trong
`AGENTS.md`.

Nếu architecture document mâu thuẫn business requirement:

```text
business requirement wins
```

---

# 7. Target module structure

Default business module:

```text
<module>/
├── api/
├── application/
├── domain/
└── infrastructure/
```

Đây là semantic boundary, không phải mandatory empty folder template.

Không tạo folder chưa cần sử dụng.

Một module thực tế có thể là:

```text
order/
├── api/
│   ├── OrderController.java
│   ├── CreateOrderRequest.java
│   └── OrderResponse.java
│
├── application/
│   ├── OrderCommandService.java
│   ├── OrderQueryService.java
│   └── exception/
│
├── domain/
│   ├── Order.java
│   ├── OrderItem.java
│   ├── OrderId.java
│   ├── OrderStatus.java
│   ├── OrderRepository.java
│   ├── policy/
│   └── event/
│
└── infrastructure/
    ├── persistence/
    ├── client/
    ├── messaging/
    ├── cache/
    ├── scheduling/
    ├── security/
    └── config/
```

Subpackage chỉ tạo khi module thực sự cần.

---

# 8. Responsibility của từng layer

## API

Trả lời:

> Consumer muốn làm gì?

Bao gồm chủ yếu:

* REST Controller;
* HTTP request;
* HTTP response;
* HTTP validation;
* API mapping;
* OpenAPI concerns.

API không chứa business rule.

Flow:

```text
HTTP
 ↓
Controller
 ↓
Application Service
```

---

## Application

Trả lời:

> Use case cần thực hiện những bước nào?

Application chịu trách nhiệm:

* orchestration;
* transaction boundary;
* authorization orchestration khi phù hợp;
* load aggregate;
* gọi domain behavior;
* gọi repository/gateway;
* phối hợp external dependency;
* publish event nếu cần.

Application không được trở thành nơi chứa toàn bộ business rule.

Default:

```text
Controller
    ↓
Concrete Application Service
```

Không cần `UseCase interface` chỉ để Controller gọi Service.

---

# 9. Domain

Domain trả lời:

> Business cho phép điều gì?

Domain chứa:

* Aggregate Root;
* Entity;
* Value Object;
* business invariant;
* domain behavior;
* domain policy;
* domain service khi thực sự cần;
* domain event khi thực sự cần;
* repository abstraction của Aggregate Root.

Ví dụ:

```text
Order
 ├── OrderItem
 └── ShippingAddress

OrderRepository
```

Không tạo:

```text
OrderItemRepository
ShippingAddressRepository
```

nếu chúng nằm trong `Order` Aggregate.

---

# 10. Infrastructure

Infrastructure trả lời:

> Hệ thống kỹ thuật thực hiện capability đó như thế nào?

Ví dụ:

```text
PostgreSQL
Redis
RabbitMQ
Kafka
S3 / MinIO
SMTP
JWT
HTTP client
Scheduler
Spring Security
Metrics
```

Target:

```text
infrastructure/
├── persistence/
├── cache/
├── messaging/
├── storage/
├── email/
├── security/
├── client/
├── scheduling/
└── config/
```

Không chia theo:

```text
adapter/in
adapter/out
```

---

# 11. Dependency direction

Target dependency:

```text
api
 │
 ▼
application
 │
 ▼
domain

infrastructure
 ├────────► application
 └────────► domain
```

### Allowed

```text
api             -> application
api             -> domain types khi response mapping thực sự cần

application     -> domain

infrastructure  -> application
infrastructure  -> domain
```

### Forbidden

```text
domain -> api
domain -> application
domain -> infrastructure

application -> api

module A -> module B.infrastructure
module A -> module B persistence implementation
```

---

# 12. Cross-module communication

Module phải sở hữu dữ liệu và behavior của chính nó.

Forbidden:

```text
Order
  ↓
PaymentJpaRepository
```

hoặc:

```text
Order
  ↓
PaymentEntity
```

Preferred:

```text
Order Application
       ↓
Payment capability
       ↓
Payment Application
```

hoặc khi asynchronous coupling thực sự có giá trị:

```text
Order
 ↓
Domain/Application Event
 ↓
Payment / Inventory
```

Không tạo interface cross-module nếu direct application capability đã đủ.

---

# 13. Interface rule mới

Interface không phải default.

Interface được tạo khi tồn tại boundary thật, ví dụ:

* external service;
* storage provider;
* email provider;
* payment provider;
* distributed cache;
* event publisher;
* cryptographic provider;
* nhiều implementation thực tế;
* stable contract giữa module có lý do rõ ràng.

Không tạo interface chỉ vì:

```text
"DDD"
"Clean Architecture"
"Hexagonal"
"để mock dễ hơn"
"có thể tương lai sẽ thay implementation"
```

Concrete application service là default.

---

# 14. Không dùng `Port` làm naming convention mặc định

Legacy:

```text
UserRepositoryPort
EmailSenderPort
JwtIssuerPort
StoragePort
```

Target ưu tiên business/capability name:

```text
UserRepository
EmailSender
TokenIssuer
FileStorage
PaymentGateway
NotificationPublisher
```

Không thêm suffix `Port` nếu nó không làm business meaning rõ hơn.

---

# 15. Không dùng `Adapter` làm suffix mặc định

Legacy:

```text
UserPersistenceAdapter
BcryptPasswordHasherAdapter
RedisAuthThrottleAdapter
MailServiceEmailSenderAdapter
```

Target dùng technology/capability name:

```text
JpaUserRepository
BcryptPasswordHasher
RedisAuthThrottle
SmtpEmailSender
RabbitAuditPublisher
S3FileStorage
```

Tên class phải nói implementation là gì, không nói pattern gì.

---

# 16. Repository convention

Repository theo Aggregate Root.

Domain:

```text
UserRepository
OrderRepository
ProcessingJobRepository
CustomerRepository
```

Infrastructure triển khai repository.

Ví dụ conceptual:

```text
domain
└── UserRepository

infrastructure/persistence
├── JpaUserRepository
└── UserJpaEntity        # nếu persistence model tách riêng
```

Không dùng suffix:

```text
RepositoryPort
PersistenceAdapter
```

---

# 17. Pragmatic JPA rule

Project không áp dụng rule:

> Domain object luôn phải tách khỏi JPA Entity.

Nếu:

```text
domain model ≈ persistence model
```

và JPA annotation không làm méo domain behavior, aggregate **MAY** dùng
`jakarta.persistence` mapping trực tiếp.

Ví dụ được phép về mặt architecture:

```text
domain/Order.java
@Entity
```

Nhưng HTTP/Jackson concerns không được leak vào domain chỉ vì tiện.

Tách:

```text
Order
OrderJpaEntity
Mapper
```

khi có lý do thật:

* legacy schema;
* persistence model khác domain đáng kể;
* multiple storage models;
* aggregate phức tạp;
* persistence requirement làm biến dạng domain;
* query/read model đặc biệt.

Không tạo 3 object + 2 mapper cho một CRUD model đơn giản nếu không có giá trị.

---

# 18. Command / Query / Result

Không bắt buộc mọi use case phải có:

```text
Command
Query
Result
```

Dùng trực tiếp request/value nếu boundary đủ đơn giản.

Tạo application Command/Query khi:

* nhiều inbound source gọi cùng một use case;
* cần decouple HTTP contract;
* input có business meaning riêng;
* workflow phức tạp;
* object được reuse ngoài HTTP.

Không áp dụng CQRS ceremony cho CRUD thông thường.

---

# 19. Transaction convention

Documentation phải quy định:

```text
@Transactional
```

chủ yếu nằm tại Application Service/use-case orchestration.

Domain không quản lý transaction.

Controller không mở transaction dài.

Không giữ DB transaction trong lúc thực hiện external I/O dài nếu không có
business requirement đặc biệt.

---

# 20. Complexity levels

Documentation phải thể hiện ba mức độ.

### Level 1 — CRUD/simple capability

Có thể rất mỏng:

```text
api
application
infrastructure
```

Domain chỉ xuất hiện khi thật sự có domain behavior.

### Level 2 — Core business

Default:

```text
api
application
domain
infrastructure
```

Có:

* Aggregate;
* invariant;
* repository;
* Value Object khi có semantic value.

### Level 3 — Complex workflow

Chỉ bổ sung khi cần:

* Domain Event;
* Policy;
* Domain Service;
* Gateway;
* Outbox;
* Specification;
* Process Manager;
* Saga.

Không nâng Level 1 thành Level 3 để kiến trúc trông "enterprise".

---

# 21. Mapping Hexagonal → Pragmatic DDD

Agent dùng mapping conceptual sau.

```text
adapter/in/web
    -> api

adapter/in/scheduling
    -> infrastructure/scheduling

adapter/in/amqp
    -> infrastructure/messaging

adapter/in/bootstrap
    -> infrastructure/bootstrap

adapter/out/persistence
    -> infrastructure/persistence

adapter/out/cache
    -> infrastructure/cache

adapter/out/email
    -> infrastructure/email

adapter/out/security
    -> infrastructure/security

adapter/out/amqp
    -> infrastructure/messaging

application/port/in
    -> thường REMOVE interface
       Controller/listener gọi Application Service

application/port/out/*RepositoryPort
    -> domain/*Repository

application/port/out external dependency
    -> application capability/gateway abstraction nếu thực sự cần

*PersistenceAdapter
    -> technology-specific infrastructure implementation

*UseCase
    -> concrete Application Service mặc định
```

Đây là semantic mapping.

Không được dùng mapping này để refactor Java trong phase hiện tại.

---

# 22. `RULE.md` và `RULE_vi.md`

Hai file phải được refactor như normative architecture contract.

Bắt buộc:

1. Thay architecture structure trong Section 4.
2. Xóa mandatory `adapter/in`, `adapter/out`.
3. Xóa mandatory `port/in`, `port/out`.
4. Update dependency direction.
5. Update naming table.
6. Xóa `UseCase`/`RepositoryPort`/`PersistenceAdapter` khỏi default convention.
7. Thêm Aggregate Repository rule.
8. Thêm Pragmatic JPA rule.
9. Thêm complexity-level rule.
10. Thêm cross-module boundary rule.
11. Update code examples còn implements `XxxUseCase`.
12. Giữ nguyên security, logging, error, testing, i18n, migration và production
    rules nếu chúng không phụ thuộc Hexagonal.
13. `RULE.md` và `RULE_vi.md` phải tương đương semantic.

Không rewrite toàn bộ file nếu section hiện tại vẫn đúng.

---

# 23. `AGENTS.md`

`AGENTS.md` vẫn là business source of truth.

Giữ:

```text
Modular Monolith
Moderate DDD boundaries
File Import context
Customer context
Aggregates
Invariants
State machine
CSV contract
Security requirements
Production requirements
```

Refactor các đoạn architecture nói:

```text
ports
adapters
port boundary
adapter implementation
```

sang terminology mới.

Không thay đổi behavior.

Bổ sung rõ:

```text
Architecture target:
Pragmatic Modular DDD

Existing Hexagonal package structure:
legacy implementation being migrated.
```

---

# 24. Auth specification

`docs/specs/auth-module-requirements.md` hiện là tài liệu cần refactor lớn.

Phải thay:

```text
Kiến trúc:
Hexagonal Architecture trong modular monolith
```

thành:

```text
Kiến trúc:
Pragmatic Modular DDD trong Modular Monolith
```

Các requirement dạng:

```text
"cho phép thay adapter JWT/persistence/cache/email..."
```

phải chuyển thành capability-oriented requirement, ví dụ:

```text
"tách implementation JWT, persistence, cache và email khỏi business workflow
khi boundary đó thực sự cần thay thế hoặc cô lập."
```

Không thay đổi authentication/security contract.

---

# 25. Historical plans/specs

`docs/superpowers/**` cần được phân loại trước khi sửa.

### Nếu document vẫn được dùng làm implementation guide

Refactor:

* package path;
* naming;
* architecture instruction;
* diagrams;
* examples;

sang target DDD.

### Nếu document mô tả công việc lịch sử đã hoàn thành

Không được giả mạo lịch sử bằng cách nói code cũ đã từng là DDD.

Thêm đầu document:

```text
LEGACY ARCHITECTURE NOTICE

Tài liệu này được tạo trước quyết định chuyển sang Pragmatic Modular DDD.
Các package `adapter/*`, `port/*`, `*UseCase`, `*Port` và `*Adapter` mô tả
legacy implementation và không còn là architecture guidance.

RULE.md là source of truth cho code mới và refactor hiện tại.
```

Các instruction còn có thể được AI thực thi phải được update hoặc đánh dấu
`SUPERSEDED`.

Mục tiêu:

> AI không bao giờ đọc historical plan rồi sinh code Hexagonal mới.

---

# 26. `LIBRARY.md`

Không thay đổi factual API contract của `vandunxg-common`.

Chỉ refactor nội dung nếu nó hướng dẫn consumer project phải dùng:

```text
adapter
port/in
port/out
PersistenceAdapter
```

Không rename API thật của external/shared library chỉ để phù hợp DDD.

Library documentation phải mô tả đúng code library hiện có.

---

# 27. Transitional architecture rule

Do documentation được migrate trước source code, mọi architecture document phải
phân biệt:

```text
CURRENT / LEGACY IMPLEMENTATION
```

với:

```text
TARGET / REQUIRED ARCHITECTURE
```

Không được ghi rằng repository đã hoàn toàn conform DDD khi source vẫn còn:

```text
auth/adapter
auth/application/port
```

Từ thời điểm docs migration hoàn thành:

> Không được mở rộng thêm Hexagonal ceremony trong code mới.

Code mới/refactor phải hướng về target DDD.

---

# 28. Agent execution procedure

Agent thực hiện theo thứ tự:

```text
1. Inventory toàn bộ Markdown
        ↓
2. Detect Hexagonal semantics
        ↓
3. Classify document
        ↓
4. Preserve business requirements
        ↓
5. Rewrite active architecture guidance
        ↓
6. Mark/update historical guidance
        ↓
7. Synchronize RULE.md / RULE_vi.md
        ↓
8. Validate terminology
        ↓
9. Validate links
        ↓
10. Produce migration report
```

Không sửa file ngay khi thấy một keyword.

Phải hiểu document đang là:

```text
normative rule
active spec
implementation plan
historical record
library contract
business requirement
```

trước khi thay đổi.

---

# 29. Repository scan

Agent phải thực hiện repo-wide scan tương đương:

```bash
find . -name '*.md' -print0 \
  | xargs -0 grep -nEi \
  'hexagonal|ports? and adapters?|adapter/(in|out)|port/(in|out)|inbound port|outbound port|RepositoryPort|StoragePort|PersistenceAdapter|UseCase'
```

Sau refactor phải scan lại.

Không coi mọi match còn lại là lỗi.

Mỗi match phải được classify:

```text
A. prohibited active architecture guidance
B. legitimate historical reference
C. legitimate non-architecture use
```

Category A phải bằng `0`.

---

# 30. Documentation architecture gates

Sau migration, active/normative docs không được yêu cầu:

```text
adapter/in
adapter/out
port/in
port/out
one UseCase interface per operation
one Port per dependency
one Adapter per implementation
separate JPA model for every aggregate
Command/Query/Result cho mọi API
```

Active docs phải thể hiện:

```text
business module first
api/application/domain/infrastructure
aggregate boundary
domain behavior
repository per aggregate
application transaction boundary
infrastructure isolation
pragmatic abstraction
```

---

# 31. Không được thay đổi business semantics

Đặc biệt phải preserve nguyên trạng semantic của:

* authentication;
* authorization;
* RBAC;
* ownership;
* JWT;
* refresh token;
* rate limiting;
* audit;
* file duplicate detection;
* processing state machine;
* retry;
* cancellation;
* recovery;
* CSV validation;
* concurrency;
* observability;
* security.

Architecture migration không phải business redesign.

---

# 32. Definition of Done

Phase được coi là hoàn thành khi:

* [ ] Tất cả `*.md` đã được inventory.
* [ ] Tất cả Hexagonal semantic references đã được review.
* [ ] `RULE.md` sử dụng Pragmatic Modular DDD.
* [ ] `RULE_vi.md` sử dụng Pragmatic Modular DDD.
* [ ] Hai RULE tương đương semantic.
* [ ] `AGENTS.md` không còn prescriptive Hexagonal guidance.
* [ ] Auth requirement spec không còn định nghĩa Hexagonal là target.
* [ ] Active docs không còn yêu cầu `adapter/in|out`.
* [ ] Active docs không còn yêu cầu `port/in|out`.
* [ ] Active docs không còn bắt buộc `UseCase` interface.
* [ ] Active docs không còn bắt buộc `*RepositoryPort`.
* [ ] Active docs không còn bắt buộc `*PersistenceAdapter`.
* [ ] Historical docs không thể bị hiểu nhầm là current architecture guidance.
* [ ] Business requirements không thay đổi.
* [ ] Public API requirements không thay đổi.
* [ ] Security requirements không thay đổi.
* [ ] Documentation links vẫn hợp lệ.
* [ ] Không Java source nào bị sửa.
* [ ] Không DB migration nào bị sửa.
* [ ] Agent tạo migration report cuối cùng.

---

# 33. Required migration report

Agent phải kết thúc bằng report:

```text
Documentation Architecture Migration Report

Files scanned:
Files modified:
Files unchanged:

Normative docs migrated:
Active specs migrated:
Historical docs marked superseded:
Historical docs rewritten:

Remaining Hexagonal-related matches:
- path
- line
- reason kept

Business behavior changed:
NONE

Java source changed:
NONE

Architecture target:
Pragmatic Modular DDD

Unresolved architecture conflicts:
...
```

Nếu `Business behavior changed != NONE`, migration phải dừng và review lại.

---

# 34. Final architecture principle

Mọi AI agent làm việc trong repository phải dùng mental model:

```text
API
"What does the caller want?"

        ↓

Application
"What workflow must happen?"

        ↓

Domain
"What does the business allow?"

        ↑

Infrastructure
"How is the technical capability implemented?"
```

Và nguyên tắc cuối:

> Preserve domain boundaries strictly enough to protect the business.
> Keep implementation structure as simple as the current problem allows.

Architecture không được trở thành mục tiêu tự thân.
