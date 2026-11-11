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
│   └── legacy-bank-transfer-upgrade.sql # Bảo toàn thanh toán cũ, opt-in
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

- `upgrades/legacy-bank-transfer-upgrade.sql`: dành cho lịch sử BANK_TRANSFER
  đã được rà soát; chạy trước booking UX upgrade nếu cần giữ dữ liệu đó. Không chạy
  tự động trên DB mới và không tạo thanh toán BANK_TRANSFER mới.
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
[database-history.md](../docs/database-history.md); đường dẫn cũ trong bản lưu đó
là thông tin lịch sử, không phải hướng dẫn bootstrap hiện tại.
