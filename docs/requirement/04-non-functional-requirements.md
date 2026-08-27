# Non-functional Requirements

# 19. Non-functional Requirements

## 19.1 Performance và capacity

| Mã | Yêu cầu |
|---|---|
| NFR-P01 | Hỗ trợ file tối đa 500 MB và 1.000.000 data row |
| NFR-P02 | Upload phải stream; không giữ toàn file trong heap |
| NFR-P03 | Processing phải stream; không dùng readAllLines hoặc collect toàn bộ row |
| NFR-P04 | Batch size và max in-flight batch cấu hình được |
| NFR-P05 | Hệ thống phải benchmark và công bố rows/second, MB/second |
| NFR-P06 | API list/detail p95 dưới 500 ms trong local test data hợp lý, không tính network ngoài |
| NFR-P07 | Upload API trả response sau khi file lưu và job tạo, không chờ processing |
| NFR-P08 | Không update progress mỗi row |

Mục tiêu benchmark không dùng làm SLA production chính thức. Developer phải tạo báo cáo benchmark với cấu hình máy, file size, row count, heap size và kết quả.

## 19.2 Memory safety

- Heap mục tiêu demo: 512 MB.
- File 500 MB không được gây OutOfMemoryError chỉ vì upload hoặc parse.
- Error report được ghi stream.
- Không lưu toàn bộ external_id trong `HashSet` nếu thiết kế đó làm memory vượt giới hạn với 1 triệu row.

Để phát hiện duplicate external_id trong file, developer phải chọn giải pháp có giới hạn tài nguyên, ví dụ:

- temporary database table có unique constraint; hoặc
- disk-backed structure; hoặc
- cấu trúc memory có benchmark chứng minh phù hợp với giới hạn.

Một `HashSet<String>` cho 1 triệu giá trị chỉ được chấp nhận khi có benchmark memory và giải thích trade-off.

## 19.3 Reliability

- Metadata và job không mất khi service restart.
- Job PROCESSING có heartbeat.
- Recovery scheduler kiểm tra job PROCESSING/CANCELLATION_REQUESTED có `heartbeatAt` quá 2 phút.
- Stale job được chuyển FAILED với `WORKER_LOST` hoặc tạo recovery attempt theo policy dưới đây.
- Phiên bản đầu chọn policy: **mark FAILED và cho phép user retry**, không tự resume im lặng.
- Recovery action phải audit.
- Storage cleanup chạy idempotent.
- Operation retry không vô hạn.
- Không có unbounded thread pool hoặc queue.

## 19.4 Graceful shutdown

Khi nhận SIGTERM:

1. Application chuyển readiness về không sẵn sàng.
2. Không claim job mới.
3. Từ chối hoặc không schedule processing mới.
4. Gửi cancellation/shutdown signal nội bộ cho worker.
5. Chờ task đang chạy tối đa 30 giây.
6. Task hoàn thành batch hiện tại rồi dừng tại safe point nếu có thể.
7. Job chưa terminal phải giữ heartbeat/status đủ để recovery phát hiện.
8. Executor shutdown và await termination.
9. Sau timeout, process có thể dừng; recovery sẽ mark stale FAILED.

Không cam kết mọi job hoàn thành trong shutdown window.

## 19.5 Security

- JWT ký bằng secret/key từ environment hoặc secret manager.
- Password phải hash bằng BCrypt/Argon2 phù hợp; không lưu plaintext.
- RBAC bắt buộc ở backend.
- Upload size limit ở reverse proxy và application.
- Chống path traversal.
- Không trust filename hoặc MIME từ client.
- MinIO bucket không public.
- Download dùng authenticated stream hoặc signed URL hết hạn tối đa 5 phút.
- Không log JWT, password, MinIO credential.
- Email và phone trong log phải mask.
- Error report chứa PII nên chỉ owner/Admin được truy cập.
- Không trả raw stack trace.
- Dependency scan và secret scan được khuyến nghị trong CI.

## 19.6 Observability

### Logging

Mọi log liên quan processing nên có:

- traceId.
- jobId.
- fileId.
- attemptNumber.
- status.
- errorCode nếu có.

Không log nguyên row customer.

Structured log event tối thiểu:

- upload_started.
- upload_completed.
- duplicate_detected.
- job_claimed.
- batch_completed ở mức DEBUG hoặc sampled.
- job_completed.
- job_failed.
- cancellation_requested.
- job_cancelled.
- retry_requested.
- stale_job_detected.

### Metrics

Counters:

- `file_upload_total{result}`.
- `file_upload_bytes_total`.
- `processing_job_total{status}`.
- `processing_rows_total{result}`.
- `processing_retry_total{operation}`.
- `processing_timeout_total{operation}`.
- `duplicate_upload_total`.
- `job_cancel_total{result}`.

Timers:

- `file_upload_duration`.
- `job_processing_duration`.
- `batch_processing_duration`.
- `storage_operation_duration{operation}`.
- `database_batch_duration`.

Gauges:

- queued jobs.
- processing jobs.
- executor active tasks.
- executor queue depth nếu có.
- current in-flight batches.

Không dùng `jobId`, `fileId`, `userId`, filename hoặc error message làm metric label.

### Health

- Liveness: process sống, không phụ thuộc MinIO/DB.
- Readiness: database, Redis và MinIO cần thiết cho request mới.
- Health detail nhạy cảm chỉ hiển thị cho môi trường/quyền phù hợp.

## 19.7 Maintainability

- DDD tactical vừa đủ.
- Không tạo interface cho mọi class.
- Controller không chứa business logic.
- Domain state transition phải được kiểm soát.
- Configuration externalized.
- Database schema quản lý bằng Flyway hoặc Liquibase.
- Public API có OpenAPI.
- Có README run local.
- Có ADR cho các quyết định:
  1. MVC multipart hay WebFlux streaming.
  2. Duplicate coordination.
  3. Logical batching/concurrency.
  4. Partial commit + idempotent retry.
  5. Duplicate external ID tracking.
  6. Graceful shutdown/recovery.

## 19.8 Compatibility

- Java 21+.
- Spring Boot 4.x.
- PostgreSQL.
- MinIO S3-compatible.
- Redis/Redisson.
- Maven.
- Docker Compose.

Không yêu cầu native image trong phiên bản đầu.

---

# 22. Recovery và scheduled maintenance

## 22.1 Stale job recovery

Scheduler chạy mỗi phút.

Điều kiện stale:

- status PROCESSING hoặc CANCELLATION_REQUESTED.
- heartbeatAt cũ hơn 2 phút.

Action:

1. Lock job.
2. Kiểm tra lại heartbeat/status.
3. Attempt RUNNING -> FAILED.
4. Job -> FAILED.
5. errorCode = `WORKER_LOST`.
6. Ghi audit.
7. Không tự động queue lại.
8. Owner/Admin có thể retry.

## 22.2 Temporary object cleanup

- Object upload tạm quá 24 giờ được xóa.
- Cleanup idempotent.
- Cleanup failure có metric và log nhưng không dừng service.

## 22.3 Retention cleanup

- Xóa original file và error report sau 30 ngày.
- Không xóa job metadata/audit trước 180 ngày.
- Không xóa file của job đang QUEUED, PROCESSING hoặc CANCELLATION_REQUESTED.
- Nếu object đã mất trước retention, job detail vẫn tồn tại và action retry/download phản hồi phù hợp.

---
