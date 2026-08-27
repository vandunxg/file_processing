# Functional Specification — 5 Features

# 10. Feature 1 — Upload và đăng ký file CSV

## 10.1 Mục tiêu

Cho phép Operator hoặc Admin gửi một file CSV lên hệ thống. API chỉ trả thành công khi file đã được lưu an toàn, checksum đã được tính và metadata/job đã được tạo.

## 10.2 Use Case UC-01 — Upload customer CSV

| Thuộc tính | Nội dung |
|---|---|
| Primary actor | Operator, Admin |
| Trigger | Actor gửi HTTP multipart request có file CSV |
| Preconditions | JWT hợp lệ; actor có quyền upload |
| Success result | Import File và Processing Job QUEUED được tạo |
| Failure result | Không tạo job hợp lệ và trả error response rõ ràng |

## 10.3 Main flow

1. Actor gửi một file qua multipart upload.
2. Hệ thống xác thực JWT.
3. Hệ thống kiểm tra quyền.
4. Hệ thống kiểm tra request có đúng một file.
5. Hệ thống kiểm tra filename, extension và size.
6. Hệ thống stream file vào storage tạm, đồng thời tính SHA-256.
7. Hệ thống kiểm tra file có thể mở như CSV UTF-8 và có header hợp lệ.
8. Hệ thống thực hiện duplicate control theo Feature 2.
9. Nếu không trùng, hệ thống chuyển object tạm sang storage key chính thức.
10. Hệ thống tạo Import File.
11. Hệ thống tạo Processing Job trạng thái QUEUED.
12. Hệ thống ghi audit event `FILE_UPLOADED`.
13. API trả `202 Accepted` cùng job summary.

## 10.4 Alternate và exception flow

### AF-01 — Không có file

- Trả `400`.
- Error code: `FILE_REQUIRED`.
- Không tạo object hoặc metadata.

### AF-02 — Nhiều hơn một file

- Trả `400`.
- Error code: `ONLY_ONE_FILE_ALLOWED`.

### AF-03 — File rỗng

- Trả `400`.
- Error code: `EMPTY_FILE`.

### AF-04 — File quá lớn

- Trả `413`.
- Error code: `FILE_SIZE_EXCEEDED`.

### AF-05 — Extension hoặc nội dung không phù hợp

- Trả `415`.
- Error code: `UNSUPPORTED_FILE_TYPE`.

### AF-06 — Header không hợp lệ

- Trả `422`.
- Error code: `INVALID_CSV_HEADER`.
- Response trả danh sách `missingColumns` và `unexpectedColumns`.

### AF-07 — Storage lỗi

- Operation storage được retry theo policy kỹ thuật.
- Nếu vẫn lỗi, trả `503`.
- Error code: `STORAGE_UNAVAILABLE`.
- Object tạm phải được cleanup bằng best effort.
- Không tạo Import File hoặc Job.

### AF-08 — Client ngắt kết nối giữa upload

- Không tạo Import File hoặc Job.
- Object tạm được cleanup ngay hoặc bởi scheduled cleanup.

## 10.5 Business rules

- API upload không chờ xử lý toàn bộ dữ liệu.
- Không gọi API thành công trước khi file gốc lưu xong.
- Không sử dụng filename làm storage path trực tiếp.
- Original filename chỉ dùng để hiển thị.
- Storage key phải do hệ thống sinh.
- File tạm cũ hơn 24 giờ phải được cleanup.
- File gốc được giữ 30 ngày kể từ lúc upload.
- Header validation được thực hiện trước khi tạo job.
- Data row validation chỉ thực hiện trong processing job.

## 10.6 Output tối thiểu

```json
{
  "jobId": "uuid",
  "fileId": "uuid",
  "originalFilename": "customers.csv",
  "sizeBytes": 10485760,
  "checksumSha256": "64-char-hex",
  "status": "QUEUED",
  "createdAt": "ISO-8601"
}
```

## 10.7 Acceptance Criteria

### AC-01.1

**Given** Operator có JWT hợp lệ và file CSV đúng template  
**When** Operator upload file  
**Then** hệ thống lưu file, tạo metadata, tạo job QUEUED và trả `202`.

### AC-01.2

**Given** file có kích thước lớn hơn giới hạn cấu hình  
**When** actor upload file  
**Then** hệ thống trả `413 FILE_SIZE_EXCEEDED` và không tạo job.

### AC-01.3

**Given** file có header thiếu `email`  
**When** actor upload file  
**Then** hệ thống trả `422 INVALID_CSV_HEADER` và chỉ rõ cột bị thiếu.

### AC-01.4

**Given** storage không khả dụng sau tất cả operation retry  
**When** actor upload file  
**Then** hệ thống trả `503`, không có job mới và không còn object chính thức.

### AC-01.5

**Given** file 500 MB hợp lệ  
**When** actor upload  
**Then** memory không tăng tương ứng toàn bộ kích thước file và hệ thống không gọi thao tác đọc toàn file vào byte array.

---

# 11. Feature 2 — Phát hiện file trùng và tạo job duy nhất

## 11.1 Mục tiêu

Ngăn cùng một user tạo nhiều job cho cùng một nội dung file, kể cả khi hai request đến gần như đồng thời hoặc đi vào hai application instance khác nhau.

## 11.2 Use Case UC-02 — Duplicate upload control

| Thuộc tính | Nội dung |
|---|---|
| Primary actor | Operator, Admin |
| Trigger | Upload flow đã tính xong checksum |
| Preconditions | Có `ownerId` và checksum hợp lệ |
| Success result | Chỉ một canonical Import File và Job được tạo |
| Duplicate result | Request sau nhận thông tin job đã tồn tại |

## 11.3 Duplicate identity

File trùng khi:

```text
same ownerId AND same SHA-256 checksum
```

Hai user khác nhau upload cùng nội dung không bị coi là trùng nghiệp vụ.

## 11.4 Main flow

1. Upload flow tính checksum.
2. Hệ thống lấy distributed lock theo `ownerId + checksum`.
3. Hệ thống kiểm tra Import File hiện có.
4. Nếu chưa có, hệ thống tạo Import File và Job trong transaction phù hợp.
5. Database unique constraint bảo vệ lần cuối.
6. Hệ thống release lock.
7. Request nhận job mới.

## 11.5 Duplicate flow

Nếu Import File đã tồn tại:

- Không tạo file metadata mới.
- Không tạo processing job mới.
- Object tạm của request hiện tại được xóa.
- Trả `409 Conflict`.
- Error code: `DUPLICATE_FILE`.
- Response trả:
  - existingFileId.
  - existingJobId.
  - existingJobStatus.
  - uploadedAt.

Chính sách áp dụng cho mọi trạng thái job, gồm FAILED và CANCELLED. Người dùng phải dùng chức năng Retry thay vì upload lại.

## 11.6 Race condition requirement

Distributed lock là coordination mechanism, không phải nguồn bảo đảm duy nhất.

Bắt buộc có database unique constraint logic trên:

```text
(owner_id, checksum_sha256)
```

Nếu hai request vẫn race và một transaction bị unique violation:

- Request thắng tạo job.
- Request thua được chuyển thành response `409 DUPLICATE_FILE`.
- Không trả `500`.
- Không để object tạm rác.

## 11.7 Acceptance Criteria

### AC-02.1

**Given** cùng một Operator upload lại đúng file đã upload  
**When** checksum trùng  
**Then** hệ thống trả `409 DUPLICATE_FILE` và existing job ID.

### AC-02.2

**Given** hai request cùng user và cùng file đến đồng thời tại hai instance  
**When** cả hai hoàn tất upload  
**Then** database chỉ có một Import File và một initial Job.

### AC-02.3

**Given** hai user khác nhau upload cùng file  
**When** request được xử lý  
**Then** mỗi user có Import File và Job riêng.

### AC-02.4

**Given** job cũ đang FAILED  
**When** owner upload lại file giống hệt  
**Then** hệ thống vẫn trả `409` và hướng client sử dụng Retry.

### AC-02.5

**Given** distributed lock hết hạn hoặc Redis gián đoạn  
**When** hai request race  
**Then** database unique constraint vẫn ngăn duplicate record.

---

# 12. Feature 3 — Xử lý file bất đồng bộ

## 12.1 Mục tiêu

Đọc và xử lý file CSV lớn mà không load toàn bộ file vào memory; phân loại lỗi dữ liệu và lỗi hệ thống; ghi customer hợp lệ theo batch; tạo error report cho dòng lỗi.

## 12.2 Use Case UC-03 — Process import job

| Thuộc tính | Nội dung |
|---|---|
| Primary actor | Processing Worker |
| Trigger | Có job QUEUED |
| Preconditions | File tồn tại; không có attempt RUNNING |
| Success result | Job COMPLETED hoặc COMPLETED_WITH_ERRORS |
| Failure result | Job FAILED hoặc CANCELLED |

## 12.3 Job claim

1. Worker tìm job QUEUED theo thứ tự `createdAt` tăng dần.
2. Worker claim job bằng cơ chế atomic.
3. Chỉ một worker claim thành công.
4. Job chuyển PROCESSING.
5. Attempt mới được tạo trạng thái RUNNING.
6. `startedAt` và `heartbeatAt` được thiết lập.
7. Audit event `JOB_STARTED` được ghi.

## 12.4 Processing flow

1. Worker mở stream file từ MinIO.
2. CSV parser đọc header.
3. Parser đọc tuần tự từng row.
4. Blank line được bỏ qua.
5. Row được map thành `CustomerImportRow`.
6. Dữ liệu được normalize.
7. Dữ liệu được validate.
8. Hệ thống phát hiện duplicate `external_id` trong cùng file.
9. Các row được gom thành logical batch, mặc định 1.000 row.
10. Mỗi batch tạo processing task có giới hạn concurrency.
11. Batch validation/transform chạy ở CPU executor.
12. Batch upsert và report I/O chạy ở I/O executor.
13. Valid rows được upsert trong một database transaction.
14. Invalid rows được ghi vào error report stream.
15. Kết quả batch được merge theo thứ tự batch để report giữ thứ tự row tăng dần.
16. Progress và heartbeat được cập nhật định kỳ.
17. Worker kiểm tra cancellation tại safe point.
18. Sau EOF, worker flush batch và đóng report.
19. Nếu không có invalid row: COMPLETED.
20. Nếu có invalid row: COMPLETED_WITH_ERRORS.
21. Attempt chuyển SUCCEEDED.
22. `finishedAt`, counters và report key được lưu.
23. Audit event `JOB_COMPLETED` được ghi.

## 12.5 Logical batch và concurrency

- Không chia CSV theo byte offset.
- Logical batch được tạo sau khi CSV parser đọc hoàn chỉnh từng record.
- Batch size mặc định: 1.000 row, cấu hình được.
- Số batch in-flight mặc định: 4, cấu hình được.
- Không sử dụng unbounded queue.
- Khi CPU executor queue đầy, producer phải chờ hoặc áp dụng backpressure; không tạo thêm task vô hạn.
- CPU executor sử dụng platform thread, kích thước mặc định bằng số processor khả dụng.
- I/O executor sử dụng virtual thread nhưng vẫn phải giới hạn số operation đồng thời bằng semaphore hoặc cấu hình tương đương.
- Không sử dụng `ForkJoinPool.commonPool()`.

## 12.6 Progress

Progress được cập nhật khi thỏa một trong hai điều kiện:

- Đã xử lý thêm ít nhất 5.000 row; hoặc
- Đã qua 2 giây từ lần cập nhật trước.

Không update database sau mỗi row.

API progress trả:

- status.
- processedRows.
- validRows.
- invalidRows.
- insertedRows.
- updatedRows.
- totalRows nếu đã biết.
- progressPercent nếu tính được.
- startedAt.
- heartbeatAt.

Trong lúc stream chưa biết total rows, `progressPercent` có thể null.
Sau EOF, `totalRows` được xác định và kết quả cuối phải 100%.

## 12.7 Validation error

Validation error:

- Không dừng job.
- Không được retry.
- Dòng không hợp lệ không được ghi vào customer.
- Error report phải chứa mọi ValidationIssue.
- Job cuối cùng là COMPLETED_WITH_ERRORS nếu có ít nhất một invalid row.

## 12.8 System error

System error gồm:

- Không đọc được object.
- Storage timeout.
- Database unavailable.
- Database batch failure.
- Executor rejected task.
- Processing timeout.
- Unexpected parser/runtime error.
- Không ghi được error report.

Khi lỗi hệ thống không thể phục hồi:

1. Dừng tạo batch mới.
2. Chờ hoặc cancel task liên quan theo policy.
3. Rollback batch hiện tại.
4. Đóng resource.
5. Attempt chuyển FAILED.
6. Job chuyển FAILED.
7. Ghi error code và sanitized summary.
8. Các batch đã commit trước đó không rollback.
9. Audit event `JOB_FAILED`.
10. File gốc được giữ để retry.

## 12.9 Timeout và retry operation

- Read storage timeout mặc định: 30 giây mỗi operation.
- Database batch timeout mặc định: 30 giây.
- Write report timeout mặc định: 30 giây mỗi operation.
- Retry tối đa 3 lần cho lỗi tạm thời.
- Backoff: 1 giây, 2 giây, 4 giây có jitter.
- Không retry:
  - Validation error.
  - Authentication/authorization storage error.
  - Invalid CSV structure.
  - Unique business rule error đã được phân loại.
- Timeout của CompletableFuture phải được kết hợp với cơ chế hủy/đóng underlying resource; chỉ complete future bằng exception là chưa đủ.

## 12.10 Invalid CSV trong lúc processing

Các lỗi cấu trúc không phát hiện được ở header nhưng xuất hiện giữa file, ví dụ quote không đóng hoặc record hỏng khiến parser không thể tiếp tục:

- Job FAILED.
- Error code: `MALFORMED_CSV`.
- Không coi đây là validation error của một row nếu parser không thể xác định ranh giới record.
- Các batch đã commit trước đó giữ nguyên.
- User có thể tải file lỗi gốc từ nguồn của mình, sửa và upload file mới; vì nội dung thay đổi nên checksum khác.

## 12.11 Acceptance Criteria

### AC-03.1

**Given** file có 1.000.000 row hợp lệ  
**When** job được xử lý  
**Then** job COMPLETED, processedRows = validRows = 1.000.000 và memory nằm trong giới hạn cấu hình.

### AC-03.2

**Given** file có 100 row, 10 row lỗi validation  
**When** job kết thúc  
**Then** status là COMPLETED_WITH_ERRORS, validRows = 90, invalidRows = 10.

### AC-03.3

**Given** một row có email và phone cùng lỗi  
**When** report được tạo  
**Then** report có hai error record nhưng invalidRows chỉ tăng một.

### AC-03.4

**Given** cùng external_id xuất hiện tại row 10 và row 50  
**When** job xử lý  
**Then** row 10 được xử lý nếu hợp lệ, row 50 bị reject `DUPLICATE_EXTERNAL_ID_IN_FILE`.

### AC-03.5

**Given** database lỗi ở batch thứ 5 sau khi 4 batch đã commit  
**When** retry operation không thành công  
**Then** batch 5 rollback, job FAILED và dữ liệu 4 batch trước vẫn tồn tại.

### AC-03.6

**Given** job FAILED sau partial commit  
**When** user retry  
**Then** job đọc lại từ đầu, upsert không tạo customer duplicate và tạo attempt mới.

### AC-03.7

**Given** executor đã đạt concurrency limit  
**When** parser tạo thêm batch  
**Then** pipeline áp dụng backpressure thay vì tạo task/thread không giới hạn.

### AC-03.8

**Given** một storage operation vượt timeout  
**When** operation không thể hủy hoặc đóng  
**Then** hệ thống vẫn ghi metric timeout và không đánh dấu job COMPLETED.

---

# 13. Feature 4 — Theo dõi job, tiến độ và kết quả

## 13.1 Mục tiêu

Cho phép actor tìm, lọc và xem chi tiết job mà không truy cập trực tiếp database hoặc log server.

## 13.2 Use Case UC-04 — List jobs

| Thuộc tính | Nội dung |
|---|---|
| Actor | Operator, Admin |
| Preconditions | JWT hợp lệ |
| Result | Danh sách job có phân trang và đúng quyền |

Bộ lọc:

- status.
- originalFilename keyword.
- createdFrom.
- createdTo.
- ownerId chỉ dành cho Admin.
- sort mặc định `createdAt DESC`.

Pagination:

- Page size mặc định 20.
- Page size tối đa 100.
- Response có totalElements, totalPages, page, size.

Operator chỉ thấy job có `ownerId` bằng user hiện tại.
Admin thấy toàn bộ job.

## 13.3 Use Case UC-05 — View job detail

Chi tiết tối thiểu:

- jobId.
- fileId.
- owner summary.
- originalFilename.
- sizeBytes.
- checksum.
- status.
- progress.
- counters.
- currentAttempt.
- attempt history.
- createdAt.
- startedAt.
- finishedAt.
- errorReportAvailable.
- business error summary.
- technical error code chỉ hiển thị cho Admin.
- availableActions: RETRY, CANCEL, DOWNLOAD_REPORT.

Không trả:

- Storage credential.
- Internal filesystem path.
- Raw exception stack trace.
- JWT.
- Full original data của customer.

## 13.4 Use Case UC-06 — View progress

Client có thể polling endpoint detail/progress.

Yêu cầu:

- Endpoint read-only.
- Không trigger xử lý.
- Data có thể eventual consistent trong tối đa 2 giây.
- Nếu job terminal, response phải chứa final counters.
- Nếu job không tồn tại hoặc không thuộc quyền actor, trả `404` để tránh lộ tài nguyên.

## 13.5 Job summary rules

- COMPLETED: errorReportAvailable = false.
- COMPLETED_WITH_ERRORS: errorReportAvailable = true.
- FAILED: report có thể tồn tại một phần nhưng không được cho download như final report.
- CANCELLED: report tạm không được xem là final report.
- Error report chỉ được publish khi job hoàn tất COMPLETED_WITH_ERRORS.
- `durationMs = finishedAt - startedAt` khi có đủ thời gian.
- Throughput hiển thị chỉ là thông tin, không phải business counter.

## 13.6 Acceptance Criteria

### AC-04.1

**Given** Operator A và Operator B có các job riêng  
**When** Operator A list job  
**Then** response không chứa job của Operator B.

### AC-04.2

**Given** Admin list job với ownerId của Operator B  
**When** filter hợp lệ  
**Then** response chỉ chứa job của Operator B.

### AC-04.3

**Given** job đang PROCESSING  
**When** actor xem progress  
**Then** processedRows và heartbeatAt phản ánh update gần nhất, độ trễ tối đa 2 giây theo thiết kế.

### AC-04.4

**Given** Operator đoán job ID của user khác  
**When** gọi detail  
**Then** hệ thống trả `404`, không trả `403` kèm thông tin tồn tại.

### AC-04.5

**Given** job COMPLETED_WITH_ERRORS  
**When** actor xem detail  
**Then** response hiển thị final counters và action DOWNLOAD_REPORT.

---

# 14. Feature 5 — Error report, retry và cancellation

## 14.1 Mục tiêu

Cho phép người dùng nhận dữ liệu lỗi có thể xử lý được, chạy lại job bị lỗi hệ thống và dừng job không còn cần thiết.

---

## 14.2 Use Case UC-07 — Download error report

### Preconditions

- Job thuộc quyền actor hoặc actor là Admin.
- Job status = COMPLETED_WITH_ERRORS.
- `errorReportKey` tồn tại.
- Report chưa hết retention.

### Main flow

1. Actor gọi download report.
2. Hệ thống kiểm tra quyền.
3. Hệ thống kiểm tra status.
4. Hệ thống tạo response stream hoặc signed URL ngắn hạn.
5. Actor tải report.
6. Audit event `ERROR_REPORT_DOWNLOADED`.

### Report format

```csv
row_number,external_id,error_code,field,error_message,original_data
```

Quy định:

- Encoding UTF-8 BOM để dễ mở bằng spreadsheet software.
- Record sắp tăng dần theo row_number.
- Nếu một row nhiều lỗi, các error record của row đó nằm cạnh nhau.
- `original_data` là JSON string hoặc CSV-safe serialized value.
- Password/token nếu vô tình xuất hiện trong cột lạ không áp dụng vì cột lạ đã bị từ chối.
- Email được giữ trong report vì report là output nghiệp vụ có kiểm soát.
- Application log vẫn phải mask email/phone.

Retention:

- File gốc: 30 ngày.
- Error report: 30 ngày.
- Metadata và audit: giữ tối thiểu 180 ngày.
- Khi report hết hạn, endpoint trả `410 REPORT_EXPIRED`.

### Acceptance Criteria

**AC-05.1**  
Given job COMPLETED_WITH_ERRORS và actor có quyền  
When download report  
Then actor nhận file đúng format và đủ validation issues.

**AC-05.2**  
Given job COMPLETED  
When download report  
Then hệ thống trả `409 REPORT_NOT_AVAILABLE`.

**AC-05.3**  
Given report đã hết retention  
When actor download  
Then hệ thống trả `410 REPORT_EXPIRED`.

---

## 14.3 Use Case UC-08 — Retry failed or cancelled job

### Actor

- Owner của job.
- Admin.

### Preconditions

- Job status là FAILED hoặc CANCELLED.
- File gốc chưa hết retention và vẫn tồn tại.
- Không có attempt RUNNING.
- Tổng số user-triggered attempt chưa vượt 3.

### Main flow

1. Actor gửi retry request.
2. Hệ thống kiểm tra quyền.
3. Hệ thống lock job.
4. Hệ thống kiểm tra trạng thái và attempt count.
5. Hệ thống reset current progress/counter về 0 cho attempt mới.
6. Không xóa attempt history.
7. Job chuyển QUEUED.
8. `errorCode`, `errorSummary`, `startedAt`, `finishedAt`, `heartbeatAt` hiện tại được clear theo model triển khai; history vẫn còn trong attempt cũ.
9. Audit event `JOB_RETRY_REQUESTED`.
10. API trả `202`.

### Business rules

- Retry không tạo Job ID mới.
- Retry tạo ProcessingAttempt mới khi worker claim.
- Không retry COMPLETED hoặc COMPLETED_WITH_ERRORS.
- Không retry khi file gốc đã bị xóa.
- Retry sau partial commit được phép vì customer upsert idempotent theo external_id.
- Error report tạm của attempt cũ không được publish.
- Nếu attempt limit vượt quá, trả `409 RETRY_LIMIT_EXCEEDED`.
- Admin không được bỏ qua attempt limit trong phiên bản đầu.

### Acceptance Criteria

**AC-05.4**  
Given job FAILED và file còn tồn tại  
When owner retry  
Then job chuyển QUEUED và giữ lịch sử attempt cũ.

**AC-05.5**  
Given job COMPLETED  
When actor retry  
Then hệ thống trả `409 JOB_NOT_RETRYABLE`.

**AC-05.6**  
Given job đã có 3 user-triggered attempt  
When actor retry lần nữa  
Then hệ thống trả `409 RETRY_LIMIT_EXCEEDED`.

**AC-05.7**  
Given job FAILED sau partial commit  
When retry hoàn tất  
Then customer không bị duplicate theo external_id.

---

## 14.4 Use Case UC-09 — Cancel job

### Actor

- Owner của job.
- Admin.

### Preconditions

- Job status là QUEUED hoặc PROCESSING.
- Job thuộc quyền actor hoặc actor là Admin.

### Flow khi QUEUED

1. Actor gửi cancel.
2. Hệ thống atomic update QUEUED -> CANCELLED.
3. Tạo/cập nhật attempt phù hợp nếu đã có.
4. Audit event `JOB_CANCELLED`.
5. API trả status CANCELLED.

### Flow khi PROCESSING

1. Actor gửi cancel.
2. Hệ thống atomic update PROCESSING -> CANCELLATION_REQUESTED.
3. API trả `202`.
4. Worker đọc cờ cancel tại safe point.
5. Worker không nhận thêm batch mới.
6. Batch đang trong transaction được phép hoàn thành.
7. Worker đóng report tạm.
8. Report tạm không được publish.
9. Attempt chuyển CANCELLED.
10. Job chuyển CANCELLED.
11. Audit event `JOB_CANCELLED`.

### Business rules

- Cancellation là cooperative, không bảo đảm dừng tức thời.
- API cancel phải idempotent:
  - Cancel lại CANCELLATION_REQUESTED trả trạng thái hiện tại.
  - Cancel lại CANCELLED trả trạng thái hiện tại.
- Không kill thread bằng API deprecated/unsafe.
- Dữ liệu batch đã commit trước cancellation vẫn tồn tại.
- Retry job CANCELLED đọc lại file từ đầu.
- Cancel terminal success job trả `409 JOB_NOT_CANCELLABLE`.

### Acceptance Criteria

**AC-05.8**  
Given job QUEUED  
When owner cancel  
Then job chuyển CANCELLED và worker không được claim.

**AC-05.9**  
Given job PROCESSING  
When owner cancel  
Then API trả CANCELLATION_REQUESTED và worker dừng tại safe point.

**AC-05.10**  
Given job CANCELLATION_REQUESTED  
When actor gửi cancel lại  
Then response thành công idempotent và không tạo action lặp.

**AC-05.11**  
Given job COMPLETED_WITH_ERRORS  
When actor cancel  
Then trả `409 JOB_NOT_CANCELLABLE`.

---

# 15. Authorization Matrix

| Capability | Operator own resource | Operator other resource | Admin |
|---|---:|---:|---:|
| Upload file | Allow | N/A | Allow |
| List jobs | Allow | Deny | Allow |
| View detail/progress | Allow | Return 404 | Allow |
| Download report | Allow | Return 404 | Allow |
| Retry FAILED/CANCELLED | Allow | Return 404 | Allow |
| Cancel QUEUED/PROCESSING | Allow | Return 404 | Allow |
| View technical error code | Limited | Deny | Allow |
| View audit history | Deny | Deny | Allow |

JWT requirements:

- Token phải có subject/user ID.
- Token phải có role.
- Token hết hạn bị từ chối.
- Không dùng dữ liệu role từ request body.
- Authorization kiểm tra ở application/API boundary và query scope.
- Không chỉ ẩn dữ liệu ở frontend.

---

# 17. API Capability Contract

Developer tự thiết kế URI cuối cùng nhưng phải cung cấp các capability sau:

| Capability | Method gợi ý | Success |
|---|---|---|
| Login | POST | 200 |
| Upload CSV | POST multipart | 202 |
| List jobs | GET | 200 |
| Job detail | GET | 200 |
| Job progress | GET | 200 |
| Download report | GET | 200/302 |
| Retry job | POST | 202 |
| Cancel job | POST | 200/202 |
| Admin audit history | GET | 200 |

## 17.1 Error response chuẩn

Mọi API error phải có cấu trúc thống nhất:

```json
{
  "timestamp": "ISO-8601",
  "status": 422,
  "code": "INVALID_CSV_HEADER",
  "message": "CSV header is invalid",
  "details": {
    "missingColumns": ["email"],
    "unexpectedColumns": []
  },
  "traceId": "trace-id"
}
```

Quy định:

- `code` machine-readable và ổn định.
- `message` không chứa stack trace.
- `details` không chứa secret.
- `traceId` phải khớp log/tracing.
- Validation request thông thường trả danh sách field error.
- Resource không thuộc quyền actor trả 404.

---

# 18. Error Code Catalog

## 18.1 Upload/API errors

| Code | HTTP | Ý nghĩa |
|---|---:|---|
| FILE_REQUIRED | 400 | Không có file |
| ONLY_ONE_FILE_ALLOWED | 400 | Có nhiều file |
| EMPTY_FILE | 400 | File rỗng |
| FILE_SIZE_EXCEEDED | 413 | File quá lớn |
| UNSUPPORTED_FILE_TYPE | 415 | Không phải CSV hỗ trợ |
| INVALID_CSV_HEADER | 422 | Header thiếu/thừa/sai |
| DUPLICATE_FILE | 409 | File trùng của cùng owner |
| STORAGE_UNAVAILABLE | 503 | Storage không khả dụng |

## 18.2 Job action errors

| Code | HTTP |
|---|---:|
| JOB_NOT_FOUND | 404 |
| JOB_NOT_RETRYABLE | 409 |
| JOB_NOT_CANCELLABLE | 409 |
| RETRY_LIMIT_EXCEEDED | 409 |
| ORIGINAL_FILE_EXPIRED | 410 |
| REPORT_NOT_AVAILABLE | 409 |
| REPORT_EXPIRED | 410 |

## 18.3 Processing system errors

| Code | Ý nghĩa |
|---|---|
| ORIGINAL_FILE_NOT_FOUND | File object không tồn tại |
| MALFORMED_CSV | CSV hỏng giữa file |
| STORAGE_READ_TIMEOUT | Timeout đọc |
| STORAGE_WRITE_TIMEOUT | Timeout ghi report |
| DATABASE_UNAVAILABLE | DB unavailable |
| DATABASE_BATCH_FAILED | Batch transaction thất bại |
| PROCESSING_TIMEOUT | Task/chunk vượt timeout |
| EXECUTOR_OVERLOADED | Không nhận thêm task |
| REPORT_GENERATION_FAILED | Không tạo được report |
| INTERNAL_PROCESSING_ERROR | Lỗi không phân loại, đã log trace |

## 18.4 Row validation error codes

- REQUIRED_FIELD.
- INVALID_EXTERNAL_ID.
- FULL_NAME_TOO_SHORT.
- FULL_NAME_TOO_LONG.
- INVALID_EMAIL.
- INVALID_PHONE.
- INVALID_DATE_FORMAT.
- DATE_OF_BIRTH_IN_FUTURE.
- DATE_OF_BIRTH_TOO_OLD.
- ADDRESS_TOO_LONG.
- DUPLICATE_EXTERNAL_ID_IN_FILE.

---
