# File Processing Service — BA Handoff Package

## Mục đích

Đây là bộ tài liệu đã ở trạng thái **Approved for Development** cho bài toán import dữ liệu khách hàng từ CSV.

## Thứ tự đọc đề xuất

1. `01-BRD-business-context.md` — hiểu vấn đề, mục tiêu, scope và actor.
2. `02-domain-model-and-business-rules.md` — hiểu aggregate, state machine, CSV rule và consistency.
3. `03-functional-specification.md` — implement 5 feature và các use case/acceptance criteria.
4. `04-non-functional-requirements.md` — hiệu năng, reliability, security, observability.
5. `05-test-and-developer-handover.md` — acceptance scenarios, deliverables, Definition of Done.
6. `requirements-traceability-matrix.csv` — truy vết feature → use case → acceptance criteria → test.
7. `99-master-BA-SRS.md` — bản tài liệu tổng hợp đầy đủ.

## Quy tắc sử dụng

- Business rule trong tài liệu là quyết định đã chốt.
- Developer được tự thiết kế code, package, schema vật lý và library.
- Mọi thay đổi làm khác hành vi nghiệp vụ phải được ghi thành change request.
- Nên implement happy path tuần tự trước, sau đó mới bổ sung concurrency.
