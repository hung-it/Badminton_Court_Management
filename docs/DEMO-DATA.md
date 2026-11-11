# Bộ dữ liệu demo Docker

Đây là dữ liệu giả lập cho đồ án, không phải dữ liệu vận hành thật. Toàn bộ SQL
được lưu cùng source, không phụ thuộc file dump hoặc thư mục `.tools` của máy tác giả.

## Chạy trên máy mới

Cài Docker Desktop với Linux containers, mở Docker Engine, clone code và tại thư mục gốc chạy:

```sh
git clone --branch dataseed --single-branch https://github.com/hung-it/Badminton_Court_Management.git
cd Badminton_Court_Management
docker compose up -d --build
```

- Web Admin: http://localhost:5173
- Web User: http://localhost:5174
- API health: http://localhost:8080/api/health
- PostgreSQL: localhost:5433

Không cần cài Java, Node, Maven hoặc PostgreSQL trên máy host. Các cổng trên phải
đang trống. Thứ tự init DB: migration → booking-ux upgrade → branches seed →
product-promotions upgrade → court-promotions upgrade → demo seed. Backend chờ
PostgreSQL hoàn tất init; hai web được build tĩnh và phục vụ bằng Nginx, proxy `/api`
đến backend trong mạng Docker. Các biến VNPay trong `.env.example` là tùy chọn;
seed demo không gọi gateway, không cần secret. Chạy VNPay thật/sandbox cần cấu hình riêng.

## Tài khoản

Mật khẩu demo chung: **`Demo@123`**, lưu BCrypt trong SQL.

| Vai trò | Email | Số lượng |
| --- | --- | --- |
| ADMIN | `admin.demo@bcm.test` | 1 |
| STAFF | `staff1.demo@bcm.test` … `staff4.demo@bcm.test` | 4 |
| CUSTOMER | `customer1.demo@bcm.test` … `customer9.demo@bcm.test` | 9 |
| CUSTOMER vãng lai | `guest@bcm.com` | 1 |

Tổng 15 tài khoản. ADMIN có thêm profile Staff để ghi nhận người thao tác, nhưng chỉ
mang quyền ADMIN; bốn nhân viên mang quyền STAFF. Guest nằm trong mười khách hàng.
`BCM_SEED_ENABLED=false` trong Compose tránh seeder Java tạo thêm tài khoản ngoài bộ này.

## Dữ liệu

- Giữ seed vai trò, khung giờ, chi nhánh/sân và các chương trình có sẵn trong repository.
- 11 sản phẩm/dịch vụ: nước suối, nước điện giải, nước thể thao, cầu lẻ, ống cầu,
  hai loại quấn cán, băng cổ tay, khăn, thuê vợt, căng cước. Tái sử dụng ba sản phẩm catalog cũ.
- 3 nhà cung cấp mẫu, tên có nhãn `[DEMO]`.
- 12 phiếu nhập RECEIVED với chi tiết và tổng tiền khớp; sản phẩm mới bắt đầu tồn 0.
- 3 phiếu DRAFT vào ngày cài seed, tổng 884.000đ: 60 chai nước suối, 24 chai nước
  điện giải, 20 quấn cán. Chúng xuất hiện trong bộ lọc “Hôm nay” khi mới khởi tạo.
  Duyệt phiếu mới cộng tồn kho; seed không cộng trước. Tổng cộng 15 phiếu nhập.
- 100 booking COMPLETED với chi tiết sân, snapshot giá và phiếu thu tiền sân.
- Thêm 3 booking Trả sân đang thao tác được ở ngày chạy seed: hai PAID, một PENDING.
  Tổng DB mới: 103 booking; 151 hóa đơn (150 PAID lịch sử và 1 POS DRAFT);
  252 phiếu thu demo. Phiếu POS giữ snapshot hai chai nước và đã trừ kho đúng một lần.
- 150 hóa đơn PAID: 100 checkout đặt sân, 50 bán hàng độc lập; 200 dòng bán hàng/dịch vụ.
- 250 phiếu thu CASH/MANUAL giả lập, có actor và nhãn `[DEMO]`. Hóa đơn tiền sân
  tính cả phiếu thu theo booking và phần hàng/dịch vụ theo invoice, không thu tiền sân lần hai.
- 3 chương trình mới `DEMO-OFF-1/2/3` ở trạng thái tắt: sản phẩm, tổng tiền sân, voucher.
  Các chương trình cũ giữ trạng thái cũ; ba chương trình mới không áp dụng vào lịch sử mẫu.

Ngày giao dịch trải trong 12 tháng gần nhất, kết thúc hôm qua, neo theo ngày đầu
khởi tạo (giờ Việt Nam). Phân bố số lượng dùng công thức cố định thay cho `random()`;
máy khởi tạo cùng ngày có cùng nội dung, dù UUID master data cũ có thể khác nhau.
Tồn kho = lượng nhập đã nhận − lượng đã bán; dịch vụ không có tồn kho.

## Chạy lại và dữ liệu hiện có

Volume tên `badminton_court_management_postgres_data` được Compose tạo tự động nếu
chưa có và tái sử dụng nếu đã tồn tại. Build lại image không xóa hoặc tạo lại DB.
Không dùng `docker compose down -v` để cập nhật dữ liệu đang dùng.

Init SQL chỉ chạy khi volume mới. Với DB cũ đã có đủ schema upgrade, có thể nạp bộ
demo một lần sau khi sao lưu:

```sh
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc -f /tmp/before-demo.dump'
docker compose cp postgres:/tmp/before-demo.dump ./backups/before-demo.dump
docker compose exec -T postgres sh -c 'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /docker-entrypoint-initdb.d/08-demo-seed.sql'
```

Nếu container được tạo trước khi thêm mount demo, trước tiên chạy
`docker compose up -d postgres` để cập nhật mount; giữ nguyên volume. Không chạy lại
`migration.sql` trên DB đã có schema. Script không tự sửa schema cũ chưa upgrade.

Seed dùng một transaction và advisory lock PostgreSQL, UUID có namespace cố định.
ID tài khoản admin demo là dấu hoàn tất transaction: chạy lại chỉ báo đã cài, không
reset mật khẩu, tồn kho, giá, ngày, trạng thái hoặc phục hồi bản ghi bị sửa/xóa.
Phần bổ sung v1.1 tạo ba phiếu nhập chờ duyệt nếu thiếu ngay cả khi bộ v1 đã cài;
UUID từng phiếu cố định nên không tạo trùng hoặc reset phiếu đã duyệt/hủy.
Hai lần nạp đồng thời được tuần tự hóa. Xung đột email/ID hoặc catalog mơ hồ làm
rollback toàn bộ, không ghi đè tài khoản khác. Guest có sẵn được tái sử dụng, giữ
nguyên mật khẩu; sản phẩm có sẵn giữ tồn ban đầu và được cộng/trừ đúng phần giao dịch mới.
Do đó số đếm chính xác ở trên dành cho DB mới, không phải DB đã có dữ liệu.

Để kiểm tra một **DB demo mới riêng**, chạy `db/checks/verify-demo-seed.sql` bằng psql
với `ON_ERROR_STOP=1`. Script chỉ đọc, kiểm tra số lượng, 12 tháng, đối chiếu tổng
tiền/phiếu thu, tồn kho và tồn lịch sử. Không dùng kiểm tra số đếm này cho DB vận hành.

Trên Windows, có thể chạy test tự động trong container PostgreSQL QA riêng (tên
phải bắt đầu bằng `bcm-demo-`, có user `bcm_admin`):

```powershell
powershell -ExecutionPolicy Bypass -File scripts/verify-demo-seed.ps1 -Container bcm-demo-qa-postgres
```

Test tự tạo DB tên `bcm_demo_verify_<random>`, nạp đúng schema và catalog từ source,
chặn advisory lock để hai tiến trình seed độc lập cùng chờ, kiểm tra một lần cài/một
lần no-op, chạy các assertion SQL, so sánh fingerprint toàn bộ 21 bảng khi chạy lại,
rồi kiểm tra giữ nguyên mật khẩu/soft delete/giá/tồn kho/khuyến mãi đã sửa. DB test
và log `.tools/demo-verify-*` được giữ để kiểm tra; không chạy lên DB chính.

## Kết quả kiểm chứng ngày 10/11/2026

- Build và bootstrap đủ bốn service Docker trên volume mới, mạng/cổng QA riêng: PASS.
- SQL kiểm tra số lượng, 12 tháng, tồn lịch sử, booking snapshot và tổng phiếu thu: PASS.
- Hai lần seed đầu tiên đồng thời, chạy lại và giữ chỉnh sửa: PASS.
- HTTP hai web và proxy API: 200; đăng nhập ADMIN/STAFF/CUSTOMER/guest qua cả hai web: PASS.
- PostgreSQL `BookingConcurrencyPostgresTest`: 9 tests PASS, bao gồm hai transaction
  cùng slot nhận đúng một thành công và một xung đột.
- `git diff --check`: PASS.
- `mvn -f backend/pom.xml verify`: chưa pass, 494 tests, 5 failures, 29 errors,
  322 skipped, cùng kết quả baseline trước task. Bộ acceptance thiếu DB riêng;
  các lỗi còn lại ở Analytics, ProductPromotion, RevenueBreakdown, MasterData và JWT.
- Chạy thêm mapping + concurrency trên PostgreSQL: 22 tests, 5 errors ở fixture
  mapping cũ (CASH/GATEWAY/PENDING và DRAFT có payment_method), không khớp constraint
  hiện hành; cả 9 concurrency tests pass. Không sửa schema hay test nghiệp vụ ngoài task.

Sau khi người dùng xác nhận riêng, seed đã nạp thêm vào DB đồ án đang có. Backup
`backups/before-demo-seed-20261110-200557.dump` đã kiểm tra TOC trước khi nạp.
Xác nhận 150 hóa đơn mới, 250 phiếu thu demo, 3 chương trình mới tắt và không có
hóa đơn demo lệch tổng phiếu thu; đăng nhập các vai trò qua web đang chạy thành công.

### Bổ sung nhập kho v1.1 — 10/11/2026

Màn hình mặc định lọc Hôm nay; 12 phiếu RECEIVED cũ nằm ngày đầu mỗi tháng nên
không xuất hiện trong ngày 10/11. Bổ sung ba phiếu DRAFT ngày cài seed, tổng 884.000đ.
Không đổi bộ lọc, không dời ngày phiếu lịch sử hoặc tự duyệt phiếu.

Đã chạy lại `scripts/verify-demo-seed.ps1` trên PostgreSQL test riêng: PASS số lượng
15 phiếu, tồn kho, số tiền, hai lần seed đồng thời và giữ dữ liệu đã sửa. `git diff --check`
PASS. Maven verify vẫn cùng baseline 494 tests, 5 failures, 29 errors, 322 skipped.

DB hiện tại đã backup ở `backups/before-inventory-demo-20261110-201216.dump`, kiểm tra
TOC trước khi nạp bổ sung. Fingerprint toàn bộ products trước/sau giống nhau. API
list/detail xác nhận 3 phiếu DRAFT ngày 10/11, tổng 884.000đ và 15 phiếu toàn lịch sử.
Sau khi nạp ở tab đang mở cần tải lại trang để lấy danh sách mới. Chỉ chọn Duyệt nhập
kho khi muốn thực sự cộng lượng hàng tương ứng vào tồn demo.

Các thay đổi nhập thủ công sau khi chạy không tự đi vào Git. Nếu muốn chuyển chúng
sang máy khác, cần bổ sung seed có chủ đích hoặc backup/restore riêng. Để người khác
nhận bộ seed này, commit/push các file thay đổi rồi họ pull đúng nhánh.

## Nhập kho và Trả sân qua ngày — v1.2 ngày 11/11/2026

Phiếu nhập ngày 10/11 không bị mất sau khi khởi động: bộ lọc Hôm nay ngày 11/11
đã ẩn chúng. Nhập kho nay mặc định Tất cả; vẫn có Hôm nay, Tuần này, Tháng này,
lọc theo tháng và khoảng ngày. Các màn hình khác giữ nguyên bộ lọc cũ.
Không đổi ngày hoặc tạo thêm phiếu nhập giả chỉ để làm đầy bộ lọc Hôm nay.

Ba booking mới dùng sân AVAILABLE còn trống trong template đang diễn ra, hoặc ca
gần nhất đã bắt đầu nếu ngoài giờ mở cửa. Giá lấy từ giá sân × hệ số khung giờ.
Mỗi ca khóa Court và tuân unique slot; thiếu sân/sản phẩm/tồn kho sẽ rollback seed.

1. Khách demo 2: PAID, tiền sân CASH đã thu giả lập, không còn tiền hàng cần thu.
2. Khách demo 3: PAID, tiền sân CASH đã thu giả lập, POS DRAFT hai chai nước cần thu khi kết toán.
3. Khách demo 4: PENDING, chưa có phiếu thu; dùng nút Thu tiền tại quầy rồi Kết toán.

PENDING có hạn giữ 10 phút kể từ lần nạp. Hết hạn thì EXPIRED theo nghiệp vụ.
PAID không có POS sẽ tự kết toán sau giờ kết thúc + 3 tiếng nếu job đang bật;
booking có POS DRAFT được giữ để nhân viên xử lý. Hoàn tất sẽ rời danh sách Trả sân.
Đây là dữ liệu để thao tác, không phải bản ghi luôn hiện vĩnh viễn.

UUID có ngày chạy seed và số kịch bản, chỉ tạo một bộ mỗi ngày khi **chủ động nạp seed**.
Chạy lại cùng ngày không đổi lịch, không trừ kho hoặc thu tiền lần nữa, không hồi sinh
đơn hoàn tất/hết hạn. Không có tác vụ tự nạp thêm demo hằng ngày. Docker chỉ tự nạp
trên volume mới; với volume cũ, sao lưu rồi chạy lại demo-seed.sql theo hướng dẫn trên.

Kiểm chứng bổ sung: seed PostgreSQL (lần đầu đồng thời, fingerprint khi chạy lại,
giữ dữ liệu đã sửa) PASS; `web-admin/inventory-history.e2e.cjs` PASS với API giả lập;
build admin PASS. `scripts/verify-checkout-demo.mjs` chạy trên backend QA riêng cổng
58098 PASS cả ba luồng thu tiền/kết toán, hai request retry đồng thời và không trừ kho lần hai.
Full Maven verify vẫn baseline 509 tests, 5 failures, 29 errors, 337 skipped.
DB chính đã sao lưu tại `backups/before-checkout-demo-20261111-065000.dump` trước khi nạp.
