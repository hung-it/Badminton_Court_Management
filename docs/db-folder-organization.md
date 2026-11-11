# Sắp xếp thư mục db ngày 11/11/2026

Giữ hai entry point `db/migration.sql` và `db/demo-seed.sql` ở ngoài cùng.
Chuyển các file còn lại vào `upgrades/`, `seeds/`, `checks/`, không gộp lại lịch sử
SQL hoặc loại bỏ dữ liệu mẫu. Cấu trúc và thứ tự chạy: [db/README.md](../db/README.md).

## Nội dung được bảo toàn

- Chín file SQL có SHA-256 giống hệt trước khi sắp xếp, bao gồm seed Trả sân v1.2
  và các fixture báo cáo cũ chỉ chạy thủ công. Không đổi bảng/cột/status/constraint.
- `db/README.md` được viết lại cho cấu trúc hiện tại; toàn bộ README cũ được giữ
  trong [database-history.md](database-history.md).
- Cập nhật đường dẫn source mount trong Compose, script local/shared Docker,
  kiểm thử backend/acceptance/demo và test Java đọc SQL. Giữ nguyên tên file đích
  bên trong `/docker-entrypoint-initdb.d` để các lệnh psql hiện có vẫn dùng được.
- DB chính vẫn dùng volume `badminton_court_management_postgres_data`.

## Kiểm chứng

1. Kiểm tra hash từng file trước/sau: 9/9 giống hệt.
2. `docker compose config --quiet`, parser PowerShell và `git diff --check`: PASS.
3. `scripts/verify-demo-seed.ps1 -Container bcm-demo-inventory-test`: PASS trên DB
   test mới, gồm dữ liệu tài chính/tồn kho, hai lần nạp đầu tiên đồng thời, fingerprint
   khi chạy lại và giữ nguyên các chỉnh sửa.
4. Docker bootstrap từ volume mới, mạng/cổng QA riêng: PASS. Hibernate validate
   khởi động; có 21 bảng, 15 tài khoản, 210 sân, 15 phiếu nhập, 103 booking,
   151 hóa đơn. Hai web và proxy API trả 200; checks/verify-demo-seed.sql PASS.
5. PostgreSQL migration/seed tests: 18 PASS, 1 FAIL tại
   `DatabaseConsolidationPostgresTest:47`. Lỗi giống hệt đã tái hiện trên nguyên
   source HEAD trước khi sắp xếp: test so sánh cả metadata receipt mới được upgrade
   thêm vào payment_transactions với snapshot trước upgrade. Không đổi logic
   SQL/test để che lỗi này; chỉ thay đường dẫn file.
6. `mvn -f backend/pom.xml verify`: 509 tests, 5 failures, 29 errors, 337 skipped;
   cùng baseline đã báo trước task. Không coi full verify là pass.

## DB chính và dịch vụ đang chạy

Logical backup được ghi nguyên binary ở `.tools/db-reorg/main-before.dump` và
kiểm tra TOC bằng pg_restore trước khi cập nhật container. Backup không nằm trong Git.
Không chạy migration hoặc seed lên DB chính trong task sắp xếp thư mục.

Tạm dừng backend để không có job/ghi dữ liệu trong lúc kiểm tra. Recreate riêng
PostgreSQL để thay bind mount cũ sang đường dẫn mới, trên đúng volume đã có.
Đối chiếu count và hash toàn bộ nội dung của từng bảng trong 21 bảng: giống hệt
trước/sau. Sau đó chạy lại backend, kiểm tra API đọc chi nhánh từ DB và API qua
cả hai web đều thành công. Không reset hoặc xóa volume.

Log kiểm tra được giữ trong `.tools/db-reorg/`; môi trường test dùng container,
network và volume riêng, không dùng DB chính để chạy fixture.
