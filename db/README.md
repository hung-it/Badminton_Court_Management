# 🗄️ Database Setup với Docker

## Loại mặt sân và địa chỉ — 2026-10-24

`courts.type` đã là loại mặt sân: chỉ `STANDARD_MAT` (Thảm tiêu chuẩn) và
`WOODEN_FLOOR` (Sàn gỗ). Tái sử dụng field này, không thêm surface_type/bảng loại sân.
`courts-metadata-upgrade.sql` thêm `address VARCHAR(500)` nullable và CHECK hai mã type.
Type NULL vẫn biểu thị chưa xác minh; giá trị cũ không tương thích làm upgrade rollback,
cần quản trị viên kiểm tra, không tự chuyển đổi. Giữ Hibernate validate/SQL init never.

Sau bốn script bootstrap bên dưới, chạy lần lượt:

5. `courts-metadata-upgrade.sql`: schema cộng thêm, không sửa migration lịch sử.
6. `courts-metadata-seed.sql`: UPDATE metadata fixture; không INSERT sân.

Compose mount cả sáu script theo thứ tự. Với database đang chạy, backup `pg_dump -Fc`
ngoài backend/target, kiểm tra archive và counts trước khi chạy hai file mới bằng
`psql -X -v ON_ERROR_STOP=1 -f ...`. Không cần chạy lại seed tạo sân cũ trên runtime.

Backfill dùng UUID cố định của runtime đã xác minh; UUID tám sân gốc ở database mới
được resolve qua court_number/tên/type/giá fixture chính xác. Chỉ cập nhật khi tên,
số sân và district còn đúng mapping. Không đổi type hợp lệ hoặc địa chỉ khác rỗng,
không đổi ID, tên, giá, district, booking/payment/slot. Lần hai UPDATE 0.

Địa chỉ `Khu thể thao mô phỏng <số sân>, <district>, TP. Hồ Chí Minh` là fixture
đồ án minh bạch, không phải số nhà/đường hoặc cơ sở ngoài đời đã xác minh.
Record đổi tên/di chuyển district hoặc sân thật không khớp fixture được bỏ qua để
quản trị viên bổ sung, không suy địa chỉ từ tên sân. Bảy district dùng quy ước đồ án.

Public/admin API giữ tên `type`, thêm optional `address`. POST/PUT kiểm tra enum,
địa chỉ tối đa 500 ký tự, không control characters; địa chỉ có dữ liệu cần district.
Các thành phần quận rõ ràng, phân cách dấu phẩy/chấm phẩy, được kiểm tra nhất quán;
đây không phải geocoding hay xác minh địa giới. PUT bỏ/null/blank address giữ địa chỉ cũ.
Omitted district giữ giá trị cũ; xóa district khi còn address bị từ chối.

Thông tin schema/4 bước ở phần lịch sử bên dưới mô tả checkpoint trước upgrade này.

Folder này chứa migration script để khởi tạo PostgreSQL database cho dự án.

## 📁 Files

- `migration.sql` - Script tạo 20 bảng + indexes + seed data
- `court-demo-seed.sql` - Fixture cũ 5 sân của checkpoint khác; không nằm trong bootstrap hiện tại.
- `booking-ux-upgrade.sql` - Upgrade cộng cột nullable `courts.district` (150 ký tự), chạy lặp an toàn, không backfill/quy đổi địa giới.
- `booking-contact-upgrade.sql` - Hai cột nullable `bookings.contact_full_name` / `contact_phone`
  để sửa tên/SĐT theo từng booking tại checkout; không sửa hồ sơ/email và không có customer_note.
- `courts-districts-seed.sql` - Nguồn seed chính thức cho bộ dữ liệu đồ án: 15 sân có district;
  chuyển bảy UUID cũ từ tên `[DEMO]` sang tên thường, không xóa/reinsert hoặc thay giá.
- `demo-districts-seed.sql` - Entry point psql cũ, chỉ `\ir courts-districts-seed.sql`,
  không chứa bộ INSERT thứ hai và không tạo thêm sân demo.

## Bộ dữ liệu đồ án: 15 sân / 7 district

Thứ tự database mới (Compose tự chạy theo tên mount khi volume mới):

1. `migration.sql`: schema gốc, 8 sân mẫu với UUID sinh ngẫu nhiên và 16 slot.
2. `booking-ux-upgrade.sql`: nullable district, không có customer_note.
3. `courts-districts-seed.sql`: gán district fixture cho 8 sân và thêm 7 sân UUID cố định.
4. `booking-contact-upgrade.sql`: thông tin liên hệ riêng của booking, legacy NULL fallback về customer.

Volume hiện có không tự chạy init lại. Sau khi xác nhận đây là bộ dữ liệu đồ án, backup
ngoài `backend/target` rồi áp dụng upgrade nếu thiếu district, sau đó chạy seed chính:

```powershell
docker cp db/courts-districts-seed.sql bcm-postgres:/tmp/courts-districts-seed.sql
docker exec bcm-postgres psql -X -U bcm_admin -d badminton_court_db -v ON_ERROR_STOP=1 -f /tmp/courts-districts-seed.sql
docker exec bcm-postgres psql -X -U bcm_admin -d badminton_court_db -c "SELECT district, COUNT(*) FROM courts GROUP BY district ORDER BY district;"
```

Seed chạy trong transaction và khóa bảng courts để tránh cạnh tranh khi chuyển đổi. Chạy lại
không tạo record hoặc cập nhật timestamp thừa. Bảy sân dùng lại UUID namespace
`bcdd0000-0000-4000-8000-000000000001..007`, số sân `10001..10007`. Chỉ tên cũ chính xác
`[DEMO] Cơ sở ... - Sân 01` được chuyển sang tên tương ứng; xung đột UUID/số sân/tên dừng toàn bộ.
District khác rỗng, giá, status, type và deleted_at hiện có được giữ nguyên.

Tám sân gốc đã đối chiếu migration và runtime. Script chứa UUID runtime đã xác minh;
database mới có UUID khác được đối chiếu bằng court_number unique và đầy đủ signature
tên/type/base_price của fixture rồi cập nhật qua UUID đã resolve. Record khác hoặc không đủ
căn cứ bị từ chối thay vì gán district tùy ý. Court number 1..8 có mapping lần lượt:
Quận 1, Quận 3, Quận 5, Quận 7, Bình Thạnh, Tân Bình, Quận 1, Gò Vấp.

**Nguồn district là phân bổ fixture được người dùng cho phép, không phải địa chỉ xác minh.**
Tên "Sân cầu lông Thủ Đức" được giữ nguyên; mapping Quận 1 là cấu hình mô phỏng, không suy ra
từ địa danh trong tên. Schema courts không có address/image/description/is_demo; không tạo
địa chỉ đường/số nhà giả. "Chính thức" chỉ có nghĩa record thường của bộ dữ liệu đồ án.

Số liệu kiểm chứng 2026-10-24 và danh sách UUID trước/sau nằm trong
[báo cáo chuyển đổi](../docs/courts-official-2026-10-24.md). Báo cáo 2026-10-23 là checkpoint
lịch sử trước khi chuyển đổi; không dùng số 12 sân ở môi trường cũ làm dữ liệu hiện tại.

## Upgrade Courts / Checkout trên database hiện có

Repository dùng SQL chạy thủ công và Docker init scripts; không có Flyway/Liquibase.
Bản hiện tại chỉ thêm district; chức năng ghi chú checkout và mapping đã được loại bỏ.
Cột ghi chú từ các lần triển khai cũ (nếu có) được giữ nguyên để bảo toàn dữ liệu; script không DROP cột.
Không sửa `migration.sql` lịch sử. Trước khi chạy backend có mapping mới, áp dụng:

```powershell
docker cp db/booking-ux-upgrade.sql bcm-postgres:/tmp/booking-ux-upgrade.sql
docker exec bcm-postgres psql -U bcm_admin -d badminton_court_db -v ON_ERROR_STOP=1 -f /tmp/booking-ux-upgrade.sql
```

Compose chạy upgrade này sau schema gốc khi tạo volume mới. Với volume cũ, init scripts
không chạy lại nên cần lệnh trên; giữ `ddl-auto: validate`, SQL init `never`.
Upgrade không xóa bảng/dữ liệu, không đổi deadline/status/payment note và không cần index mới
vì catalog sân hiện trả toàn bộ danh sách để lọc ở client.

Với dữ liệu người dùng thật, quận/huyện là nhãn vị trí do quản trị viên xác minh qua địa chỉ,
không suy từ tên sân và không lấy địa chỉ khách hàng làm địa chỉ sân. Điền bằng field optional
`district` trong API quản lý sân hiện có (`POST/PUT /api/admin/courts`). Bỏ field khi update
giữ giá trị cũ; chuỗi trống xóa nhãn về NULL. Sân chưa xác minh giữ NULL, vẫn xuất hiện khi
không áp dụng bộ lọc quận. Danh sách checkbox lấy từ catalog backend, không dùng danh mục địa giới hardcode.

## 🚀 Quick Start

```bash
# Khởi động database
docker-compose up -d

# Kiểm tra logs
docker-compose logs -f

# Dừng database
docker-compose down

```

Compose chạy sáu file theo thứ tự trên. Không dùng `court-demo-seed.sql` cũ chung với bộ
15 sân vì fixture đó dùng các court_number 1–5 cho dữ liệu khác. Không cần xóa/tạo lại volume.

Với volume cũ, trước khi chạy backend hỗ trợ sửa người đặt:

```powershell
docker cp db/booking-contact-upgrade.sql bcm-postgres:/tmp/booking-contact-upgrade.sql
docker exec bcm-postgres psql -X -U bcm_admin -d badminton_court_db -v ON_ERROR_STOP=1 -f /tmp/booking-contact-upgrade.sql
```

Backup bằng pg_dump -Fc ra ngoài backend/target trước khi upgrade. Script chỉ thêm cột,
không backfill booking cũ, không tạo/sửa user hoặc payment, chạy lại an toàn.

## 🔗 Kết nối Database

```yaml
Host: localhost
Port: 5433
Database: badminton_court_db
Username: bcm_admin
Password: 12345
```

## 📊 Verify Database

```bash
# Kiểm tra tables
docker exec bcm-postgres psql -U bcm_admin -d badminton_court_db -c "\dt"

# Kiểm tra roles
docker exec bcm-postgres psql -U bcm_admin -d badminton_court_db -c "SELECT * FROM roles;"

# Kiểm tra time slots
docker exec bcm-postgres psql -U bcm_admin -d badminton_court_db -c "SELECT * FROM time_slots;"
```
