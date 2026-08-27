# BRD — Business Context, Goal, Scope và Actor

# 2. Bối cảnh nghiệp vụ

Công ty thường xuyên nhận file CSV chứa dữ liệu khách hàng từ các đối tác và bộ phận vận hành. Mỗi file có thể chứa từ vài trăm đến một triệu dòng.

Quy trình hiện tại được thực hiện thủ công:

1. Nhân viên nhận file qua email hoặc công cụ chat.
2. Nhân viên gửi file cho bộ phận kỹ thuật.
3. Kỹ thuật chạy script để kiểm tra dữ liệu.
4. Dữ liệu hợp lệ được nhập vào database.
5. Dữ liệu lỗi được tổng hợp thủ công rồi gửi lại cho nhân viên.
6. Không có màn hình hoặc API để theo dõi tiến độ.
7. Cùng một file có thể bị gửi và xử lý nhiều lần.
8. Khi script dừng giữa chừng, không xác định được dữ liệu nào đã được ghi.
9. Không có audit log để biết ai upload, ai retry hoặc ai hủy job.

Client cần một backend service tập trung để quản lý toàn bộ vòng đời:

```text
Nhận file -> lưu file -> phát hiện trùng -> tạo job -> xử lý
-> theo dõi tiến độ -> lưu kết quả -> tải báo cáo lỗi -> retry/cancel
```

---

# 3. Business Goal

## 3.1 Mục tiêu chính

1. Tự động hóa việc tiếp nhận và import file khách hàng.
2. Giảm nguy cơ nhập trùng do cùng một file được upload nhiều lần.
3. Cho phép người dùng theo dõi trạng thái và kết quả xử lý.
4. Tách rõ lỗi dữ liệu và lỗi hệ thống.
5. Xử lý được file lớn mà không gây tràn bộ nhớ.
6. Có đủ log, metric và audit để điều tra lỗi production.

## 3.2 Chỉ số thành công của phiên bản đầu

| Mã | Chỉ số | Mục tiêu |
|---|---|---|
| BG-01 | Tỷ lệ file được xử lý mà không cần kỹ thuật chạy script thủ công | 100% đối với file đúng template |
| BG-02 | File trùng của cùng người dùng tạo thêm job xử lý | 0 |
| BG-03 | Job có thể tra cứu được trạng thái sau khi upload | 100% |
| BG-04 | Dòng lỗi nghiệp vụ xuất hiện trong error report | 100% |
| BG-05 | Job bị crash hoặc service restart bị treo ở PROCESSING vĩnh viễn | 0 |
| BG-06 | Dữ liệu nhạy cảm bị ghi đầy đủ vào application log | 0 |

Các mục tiêu hiệu năng chi tiết được quy định trong phần Non-functional Requirements.

---

# 4. Phạm vi

## 4.1 In scope

Phiên bản đầu gồm đúng 5 nhóm tính năng:

1. Upload và đăng ký file CSV.
2. Phát hiện file trùng và tạo processing job an toàn.
3. Xử lý file bất đồng bộ: parse, normalize, validate và upsert customer.
4. Theo dõi danh sách, tiến độ và kết quả job.
5. Quản lý kết quả lỗi: download error report, retry và cancel job.

Các chức năng hỗ trợ bắt buộc:

- JWT authentication.
- Role-based authorization.
- Lưu file trên MinIO.
- PostgreSQL lưu metadata, job và customer.
- Redis/Redisson phục vụ distributed coordination.
- Audit log.
- Metrics, health check và structured logging.
- Database migration.
- Docker Compose.
- Automated tests.

## 4.2 Out of scope

- Frontend hoàn chỉnh.
- Import Excel, JSON, XML hoặc PDF.
- Upload nhiều file trong một request.
- Tự động nhận file từ email, SFTP hoặc cloud drive.
- Xử lý ảnh.
- OCR.
- Kafka hoặc RabbitMQ.
- Event Sourcing.
- CQRS framework.
- Workflow engine.
- Multi-tenant billing.
- Antivirus enterprise.
- Resume chính xác từ byte cuối cùng sau khi process crash.
- Sửa nội dung CSV trực tiếp trên hệ thống.
- Tự động gửi email thông báo.

---

# 5. Stakeholder và actor

## 5.1 Stakeholder

| Stakeholder | Mối quan tâm |
|---|---|
| Client/Product Owner | Quy trình import rõ ràng, giảm thao tác thủ công |
| Operation Team | Upload nhanh, xem tiến độ, nhận report dễ hiểu |
| Admin/Support | Điều tra lỗi, retry, cancel và xem toàn bộ job |
| Tech Lead | Tính đúng, khả năng vận hành, giới hạn concurrency |
| Backend Developer | Đặc tả rõ ràng để thiết kế và implement |
| QA | Acceptance Criteria đủ để tạo test case |
| DevOps | Health check, shutdown, configuration và monitoring |

## 5.2 Actor

### ACT-01 — Operator

Người dùng nghiệp vụ thực hiện import.

Quyền:

- Upload file.
- Xem các job do chính mình tạo.
- Xem tiến độ và kết quả job của mình.
- Tải error report của mình.
- Retry job FAILED của mình.
- Yêu cầu cancel job QUEUED hoặc PROCESSING của mình.

Không có quyền:

- Xem job của người khác.
- Xem technical stack trace.
- Xem dashboard toàn hệ thống.
- Retry hoặc cancel job của người khác.

### ACT-02 — Admin

Người quản trị hoặc support.

Có toàn bộ quyền của Operator và thêm:

- Xem mọi job.
- Lọc job theo owner, trạng thái và thời gian.
- Xem technical error code và error summary đã được làm sạch.
- Retry/cancel mọi job.
- Xem audit history.
- Xem metrics/dashboard tổng hợp thông qua công cụ monitoring.

### ACT-03 — Processing Worker

Actor hệ thống nội bộ.

Trách nhiệm:

- Claim job đang QUEUED.
- Đọc file gốc.
- Xử lý từng logical batch.
- Cập nhật progress.
- Tạo error report.
- Hoàn tất, fail hoặc dừng job tại safe point.

Processing Worker không có API public và không sử dụng JWT của người dùng.

---
