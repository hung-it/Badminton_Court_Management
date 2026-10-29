# Chạy Web Admin và Web User với một backend và DB

Nếu Admin trỏ backend native 8081 còn User trỏ Docker 8080, hai giao diện có thể thấy số sân, hàng hóa và khuyến mãi khác nhau. Chạy cả hai bằng cùng một backend Docker chứa develop và module TV2 để thay đổi Admin được User đọc ngay.

## Khởi động trên Windows

Cần Docker Desktop đang Engine Running, Docker Compose và Node.js/npm. Tại thư mục gốc repository:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-shared-docker.ps1
```

- Admin: http://127.0.0.1:5173/
- User: http://127.0.0.1:5174/
- Cả hai Vite server được đặt BCM_API_PROXY_TARGET=http://127.0.0.1:8080.
- Backend/DB: bcm-backend / bcm-postgres, database badminton_court_db, PostgreSQL host port 5433.
- Volume dùng lại: badminton_court_management_postgres_data. Script không xóa volume hoặc DB native cũ.

Script dùng đường dẫn tương đối theo repository, kiểm tra Docker và Compose, chờ PostgreSQL kết thúc init, chạy migration cộng thêm cho volume hiện có, build backend từ source hiện tại và chỉ khởi động frontend sau khi API healthy. Nếu thiếu node_modules thì npm ci. Cổng frontend đang thuộc ứng dụng khác sẽ báo lỗi, không dừng ứng dụng đó. Frontend đúng cấu hình đang chạy sẽ được giữ lại.

## DB đang có dữ liệu

**Sao lưu trước lần chạy đầu/nâng cấp backend.** Mount init SQL chỉ chạy cho volume mới. Script áp dụng member2-upgrade.sql, member2-payment-method-upgrade.sql, product-promotions-upgrade.sql, court-promotions-upgrade.sql và catalog-seed.sql cho volume đang có. Các script cộng cột/constraint, chạy lại không reset danh mục hay chương trình đã chỉnh. Giữ ddl-auto: validate và SQL init never.

Không chạy đồng thời các script backend local/demo nếu mục tiêu là kiểm tra dữ liệu dùng chung. Không dùng docker compose down -v để sửa lỗi vì thao tác đó có thể xóa dữ liệu.

Docker Compose dùng project develop-docker để tương thích tên container bcm-backend/bcm-postgres đang có. Nếu container cùng tên thuộc một project khác, kiểm tra chủ sở hữu trước; không tự xóa container/volume.

## Dữ liệu và kiểm tra

Dữ liệu thuộc DB/volume, không được chuyển qua Git. Trên volume mới, các SQL của develop tạo dữ liệu sân/chi nhánh; catalog-seed.sql thêm ba danh mục và ba sản phẩm mẫu; các dữ liệu tiếp theo do Admin quản lý. Muốn mang dữ liệu từ DB khác sang, cần sao lưu và ánh xạ khóa ngoại, không nạp dump tùy tiện đè lên DB nhóm.

Trong lần nối local ngày 28/10/2026, DB Docker giữ 210 sân; danh mục, hàng hóa, khuyến mãi và hóa đơn demo được chuyển một lần từ DB Admin cũ, giữ FK và snapshot. Dump/export chỉ lưu local, không đưa vào PR. Script startup không tự nhập lại dump demo hoặc ghi đè 210 sân bằng bộ sân cũ. File catalog-seed.sql chỉ bổ sung danh mục/hàng hóa mẫu còn thiếu.

Kiểm tra health qua http://127.0.0.1:5173/api/health và http://127.0.0.1:5174/api/health. Đăng nhập Admin, so sánh sân AVAILABLE với /api/public/courts bên User. Có thể tạo danh mục thử tại Admin, kiểm tra /api/public/categories bên User rồi xóa mềm danh mục thử. Danh sách public có thể lọc sân bảo trì/đã xóa nên phải so sánh theo trạng thái, không chỉ số tổng.

Log frontend và target được lưu dưới .tools/shared-docker/ (đã gitignore). Sau khi đổi nguồn dữ liệu, F5 cả hai trang.

## Dữ liệu mẫu khi clone máy mới

`db/catalog-seed.sql` thêm Nước giải khát / Phụ kiện / Thuê vợt và Nước suối 500ml / Cầu lông tiêu chuẩn / Thuê vợt theo lượt. Chạy script startup chung sau khi clone để có dữ liệu hiển thị; clone Git đơn thuần chưa tạo DB.

- Volume mới: Docker chạy 08-catalog-seed.sql sau các migration.
- Volume đã tồn tại: start-shared-docker.ps1 chạy seed sau các migration trước khi mở giao diện.
- Chạy lại không tạo trùng theo tên danh mục chuẩn hóa và tên sản phẩm trong danh mục. Khóa bảng trong transaction bảo vệ khi hai startup chạy đồng thời.
- Không ghi đè giá, tồn kho, đơn vị, version hoặc dữ liệu đã sửa; không khôi phục mẫu đã xóa mềm. Nếu đã chủ động xóa mẫu thì Admin cần tự khôi phục/tạo dữ liệu theo nghiệp vụ.
- Không đụng bảng courts/time_slots, không dùng nguyên member2-demo.sql vì file đó có sân demo cũ.

Kiểm thử SQL nằm tại `db/tests/catalog-seed-test.sql`, chỉ cho DB riêng có tên bắt đầu `bcm_catalog_seed_test_`. Tạo DB test rỗng, copy thư mục db vào container và chạy psql -v ON_ERROR_STOP=1 -f đường-dẫn/db/tests/catalog-seed-test.sql. Không chạy test này trên DB nhóm.
