# Database và dữ liệu demo

Thư mục được phân loại theo mục đích; không bỏ bảng, seed hoặc logic nâng cấp.
Nội dung chín file SQL được giữ nguyên khi chuyển thư mục. Dữ liệu PostgreSQL
đang có nằm trong Docker volume, không nằm trong các file SQL này.

```text
db/
├── migration.sql                   # Schema nền và dữ liệu tham chiếu ban đầu
├── demo-seed.sql                   # Demo tổng thể, nhập kho và Trả sân
├── upgrades/
│   ├── booking-ux-upgrade.sql       # Booking, chi nhánh, receipt và audit
│   ├── product-promotions-upgrade.sql # Khuyến mãi sản phẩm và catalog cơ bản
│   ├── court-promotions-upgrade.sql   # Giới hạn ưu đãi tiền sân
│   ├── legacy-bank-transfer-upgrade.sql # Chặn dữ liệu phương thức không hỗ trợ
│   └── remove-legacy-transfer-seed.sql # Dọn đúng một fixture cũ, opt-in
├── seeds/
│   ├── branches-seed.sql            # Danh mục cơ sở/sân
│   └── revenue-breakdown-sample.sql # Mẫu báo cáo cũ, chỉ chạy thủ công
└── checks/
    └── verify-demo-seed.sql         # Kiểm tra DB demo mới
```

## DB mới trên máy khác

Tại thư mục gốc repository, chạy:

```sh
docker compose up -d --build
```

Compose tự nạp theo thứ tự dưới đây khi PostgreSQL volume còn trống:

1. `migration.sql`.
2. `upgrades/booking-ux-upgrade.sql`.
3. `seeds/branches-seed.sql`.
4. `upgrades/product-promotions-upgrade.sql`.
5. `upgrades/court-promotions-upgrade.sql`.
6. `demo-seed.sql`.
7. `upgrades/email-identity-upgrade.sql`.

Tên đích trong `/docker-entrypoint-initdb.d` được giữ nguyên để tương thích cách
chạy psql và tài liệu demo. Chỉ đường dẫn source trên host thay đổi. Hibernate
vẫn dùng `ddl-auto: validate`, SQL init của Spring vẫn `never`.

Tài khoản, số lượng demo, expiry và cách chạy seed trên DB cũ:
[DEMO-DATA.md](../docs/DEMO-DATA.md).

## DB đang có dữ liệu

Việc sắp xếp file không chạy SQL, không đổi schema và không tác động Docker volume
`badminton_court_management_postgres_data`. Không xóa volume hoặc chạy lại
`migration.sql` trên DB đã có bảng.

Khi thực sự cần nâng cấp: sao lưu và kiểm tra bản sao lưu, rồi chạy các upgrade
cần thiết sau khi đối chiếu trạng thái schema. `scripts/start-shared-docker.ps1`
giữ quy trình backup trước upgrade và đã dùng đường dẫn thư mục mới.

- `upgrades/legacy-bank-transfer-upgrade.sql`: chỉ kiểm tra và chặn dữ liệu phương
  thức không hỗ trợ; không chuyển đổi hoặc cung cấp chế độ đọc BANK_TRANSFER.
- `scripts/remove-legacy-transfer-seed.ps1 -Database <tên_DB>`: sao lưu đầy đủ rồi
  dọn đúng hóa đơn seed `b2000000-0000-4000-8000-000000000103` và dòng hàng gốc.
  SQL đối chiếu toàn bộ bản ghi và từ chối nếu đã sửa hoặc có liên kết phát sinh.
  Không chạy tự động khi start project; không dùng để xóa giao dịch thật.
- `seeds/revenue-breakdown-sample.sql`: giữ đầy đủ mẫu báo cáo cũ. Không tự nạp
  cùng demo vì script có `ON CONFLICT DO UPDATE`, có thể ghi đè bản ghi mẫu và
  thay đổi tồn kho/doanh thu. Không chạy chỉ để sắp xếp folder.
- `checks/verify-demo-seed.sql`: chỉ đọc, dành cho DB demo vừa khởi tạo; số lượng
  không còn khớp khi người dùng đã kết toán/nhập kho hoặc thêm giao dịch.

## Kiểm tra

`scripts/verify-demo-seed.ps1` dùng schema và seed theo cấu trúc mới, tạo DB test
riêng, kiểm tra hai lần nạp đồng thời, tổng tiền, tồn kho và không ghi đè khi chạy lại.
Các test Java đọc SQL và các script khởi động/acceptance cũng đã cập nhật đường dẫn.

Lịch sử nâng cấp và sao lưu được giữ tại
[database-history.md](README.md); đường dẫn cũ trong bản lưu đó
là thông tin lịch sử, không phải hướng dẫn bootstrap hiện tại.

## Identity upgrades and local manual DB

Apply email-identity-upgrade.sql after a verified backup; collisions include soft-deleted users and stop migration rather than merge/delete accounts. The account-token-version section of booking-ux-upgrade.sql adds the durable revocation epoch. Apply only required sections to an existing database; do not replay the whole upgrade blindly over financial history.

BCM_POS_GUEST_USER_ID must reference an active CUSTOMER-only user with an active customer profile. It is a user UUID, not a customer UUID or a phone number.

Local manual testing uses bcm_manual_p2_20261111 with .tools/shared-docker/start-manual-p2.ps1. Keep the original badminton_court_db separate. The latter has legacy transfer history requiring review before a full upgrade.

Before upgrading, verify pg_dump -Fc by listing and restoring into a separate database; compare data and constraints. Keep backups outside backend/target. Historical audit backup backups/bcm-remove-transfer-2026-11-05T09-39-40-425Z.dump is retained; do not delete it as part of source cleanup. The latest manual backup is .tools/develop-sync-20261112/manual-before-develop.dump. No database/volume is changed by documentation cleanup.
