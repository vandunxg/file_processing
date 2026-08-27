# Acceptance Test & Developer Handover

# 23. Acceptance Test Scenarios tổng hợp

| ID | Scenario | Kết quả mong đợi |
|---|---|---|
| AT-01 | Upload CSV hợp lệ | 202, job QUEUED |
| AT-02 | Upload thiếu header | 422 |
| AT-03 | Upload 2 request trùng đồng thời | 1 job, 1 response 409 |
| AT-04 | Process file toàn bộ hợp lệ | COMPLETED |
| AT-05 | Process file có validation error | COMPLETED_WITH_ERRORS + report |
| AT-06 | Một row có nhiều lỗi | Một invalid row, nhiều issue record |
| AT-07 | Duplicate external_id trong file | Occurrence sau bị reject |
| AT-08 | Customer đã tồn tại | Update, không insert duplicate |
| AT-09 | DB lỗi giữa file | Batch hiện tại rollback, job FAILED |
| AT-10 | Retry job partial commit | Không duplicate customer |
| AT-11 | Cancel QUEUED | CANCELLED ngay |
| AT-12 | Cancel PROCESSING | Dừng tại safe point |
| AT-13 | Service kill giữa job | Scheduler mark FAILED WORKER_LOST |
| AT-14 | Operator truy cập job khác | 404 |
| AT-15 | Download report của COMPLETED | 409 |
| AT-16 | Report hết hạn | 410 |
| AT-17 | File 500 MB | Không load toàn file vào heap |
| AT-18 | Executor đầy | Backpressure, không unbounded |
| AT-19 | Storage timeout | Retry có giới hạn, metric tăng |
| AT-20 | Graceful shutdown | Không claim job mới, executor shutdown có timeout |

---

# 24. Definition of Ready cho Developer

Tính năng được xem là sẵn sàng implement vì:

- 5 feature đã xác định.
- Actor và authorization đã xác định.
- CSV schema và validation rule đã xác định.
- Duplicate policy đã xác định.
- Customer upsert policy đã xác định.
- State machine đã xác định.
- Partial commit và retry semantics đã xác định.
- Cancellation semantics đã xác định.
- Error/report retention đã xác định.
- NFR và observability đã xác định.
- Error codes chính đã xác định.

Developer chỉ cần làm rõ với Tech Lead nếu giải pháp kỹ thuật đề xuất làm thay đổi một rule trong tài liệu.

---

# 25. Deliverables từ Developer

## 25.1 Source code

- Maven project chạy được.
- Java 21+.
- Spring Boot 4.x.
- Database migrations.
- Docker Compose.
- MinIO bucket initialization.
- Redis configuration.
- JWT authentication.
- 5 feature đã mô tả.

## 25.2 Documentation

- README.
- OpenAPI.
- ERD.
- Sequence diagram upload.
- Sequence diagram processing.
- State machine.
- 6 ADR theo NFR Maintainability.
- Runbook xử lý:
  - job stale.
  - storage unavailable.
  - database unavailable.
  - report download lỗi.
- Benchmark report.

## 25.3 Test

- Unit test domain state transition.
- Unit test validation/normalization.
- Integration test PostgreSQL bằng Testcontainers.
- Integration test MinIO.
- Security test authorization.
- Concurrency test duplicate upload.
- Concurrency test job claim.
- Retry test.
- Cancel test.
- Recovery test.
- Large-file test.

---

# 26. Definition of Done

Project chỉ được xem là hoàn thành khi:

1. Toàn bộ Acceptance Criteria pass.
2. Không có endpoint nghiệp vụ bỏ qua JWT/RBAC.
3. Không có thao tác load toàn file vào memory.
4. Hai request trùng đồng thời chỉ tạo một file/job.
5. Job state transition không thể đi sai state.
6. Processing file lỗi validation vẫn hoàn tất và tạo report.
7. System error làm job FAILED với error code điều tra được.
8. Retry không tạo duplicate customer.
9. Cancel hoạt động ở QUEUED và PROCESSING.
10. Service restart không để job PROCESSING vĩnh viễn.
11. Log có traceId/jobId nhưng không lộ PII đầy đủ.
12. Metrics không có high-cardinality labels.
13. Health check phản ánh dependency.
14. Database schema được migration tự động.
15. Docker Compose khởi chạy được toàn bộ local environment.
16. Integration tests chạy lặp lại ổn định.
17. README cho người khác clone và chạy không cần sửa code.
18. Benchmark 1 triệu row được ghi lại.
19. Tech Lead review và chấp nhận ADR.
20. Không thêm Kafka, CQRS, Event Sourcing hoặc abstraction không cần thiết.

---

# 27. Hướng dẫn implement không phải lời giải

Developer nên triển khai theo thứ tự:

## Phase 1 — Happy path tuần tự

- Auth.
- Upload stream.
- MinIO.
- Metadata/job.
- CSV parser.
- Validation.
- Batch upsert.
- Job result.

Mục tiêu: xử lý đúng file nhỏ bằng một worker, chưa tối ưu concurrency.

## Phase 2 — Error flow

- Error report.
- Error code.
- Retry operation.
- Job FAILED.
- Audit.
- Authorization đầy đủ.

## Phase 3 — Concurrency

- Duplicate race test.
- Atomic job claim.
- Logical batch pipeline.
- I/O executor.
- CPU executor.
- Bounded in-flight tasks.
- Timeout.

## Phase 4 — Production readiness

- Graceful shutdown.
- Stale recovery.
- Retention cleanup.
- Metrics.
- Health.
- Structured log.
- Benchmark.
- Runbook.

Developer không được bắt đầu bằng hệ thống concurrency phức tạp trước khi happy path tuần tự đúng và có test.

---

# 28. Những câu hỏi Tech Lead sẽ dùng để review

1. Vì sao không dùng `MultipartFile.getBytes()`?
2. File đã ghi MinIO nhưng transaction DB fail thì cleanup thế nào?
3. Redis lock chết giữa request thì database ngăn duplicate ra sao?
4. Vì sao duplicate key gồm ownerId?
5. Vì sao CSV không được chia theo byte?
6. Backpressure trong pipeline được thực hiện ở đâu?
7. Vì sao virtual thread không dùng cho CPU-bound validation?
8. Khi `CompletableFuture.orTimeout()` xảy ra, task nền có thật sự dừng không?
9. Khi batch 5 fail, dữ liệu batch 1–4 có trạng thái gì?
10. Retry từ đầu có làm counters sai hoặc customer duplicate không?
11. Hai job khác file cùng update một external_id thì kết quả gì?
12. Duplicate external_id trong file được phát hiện mà không OOM thế nào?
13. Vì sao validation error không làm job FAILED?
14. Tại sao report chỉ publish khi job hoàn tất?
15. Cancellation safe point nằm ở đâu?
16. SIGTERM đến lúc transaction đang commit thì xử lý thế nào?
17. Stale job được phát hiện bằng dữ liệu nào?
18. Vì sao không tự động retry toàn job vô hạn?
19. Metrics nào có nguy cơ cardinality explosion?
20. Làm sao chứng minh service xử lý 1 triệu row trong heap 512 MB?
21. Domain rule nào nằm trong ProcessingJob thay vì service?
22. Tại sao không cần microservice/Kafka ở phiên bản đầu?
23. Test nào chứng minh chỉ một worker claim job?
24. Làm sao để API không lộ sự tồn tại job của user khác?
25. Tình huống nào trả 409, 422, 503 và 410?

---

# 29. Kết luận

Đây là bài toán backend production ở mức junior nâng cao:

- Không chỉ là CRUD.
- Có file I/O thực tế.
- Có stream, batch và concurrency có giới hạn.
- Có race condition.
- Có transaction và partial failure.
- Có state machine.
- Có retry, timeout, cancellation và recovery.
- Có security, audit và observability.
- Có đủ phạm vi để học sâu nhưng chưa cần microservice hoặc kiến trúc quá phức tạp.

Developer được quyền lựa chọn chi tiết code và library phù hợp, nhưng kết quả cuối phải tuân thủ toàn bộ business rule và Acceptance Criteria trong tài liệu này.
