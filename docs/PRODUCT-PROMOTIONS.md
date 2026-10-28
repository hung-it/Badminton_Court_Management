# Khuyến mãi sản phẩm theo số lượng

## Chạy và nâng cấp

Database có sẵn: bảo đảm đã áp dụng các nâng cấp Booking/chi nhánh của develop, sao lưu rồi chạy `db/product-promotions-upgrade.sql` trước khi khởi động backend mới. Script chỉ thêm cột; chạy lại an toàn, không đổi hóa đơn cũ.

Database mới: `migration.sql` → `booking-ux-upgrade.sql` → `branches-seed.sql` nếu dùng bộ dữ liệu chi nhánh của nhóm → `member2-upgrade.sql` → `member2-payment-method-upgrade.sql` → `product-promotions-upgrade.sql`. `scripts/start-local-db.ps1` đã chạy bản nâng cấp khuyến mãi (DB local vẫn phải được chuẩn bị theo schema develop trước); Docker Compose mount cùng thứ tự khi volume mới. Volume Docker đã có dữ liệu không tự chạy lại init script.

Schema dùng lại `promotions` và `discount_rules`: thêm `min_quantity` cho điều kiện số lượng. Các cột discount_amount/promotion_id/promotion_name/discount_percent trên invoice_details là snapshot của lần bán. Invoice header lưu tổng giảm trong discount_amount; product_fee vẫn là tổng tiền sản phẩm trước giảm.

## Quy tắc đã chốt

- Mỗi chương trình cho một sản phẩm, một số lượng tối thiểu, tỷ lệ lớn hơn 0 và không quá 100%.
- Hiệu lực theo ngày Asia/Ho_Chi_Minh, gồm cả ngày bắt đầu và kết thúc; có bật/tắt và xóa mềm.
- Mua từ ngưỡng trở lên: giảm cho toàn bộ số lượng sản phẩm đó trong cùng request bán hàng. Các dòng trùng productId được cộng số lượng trước khi xét ngưỡng.
- Chọn đúng một ưu đãi có số tiền giảm lớn nhất cho mỗi sản phẩm; không cộng dồn. Tiền giảm làm tròn HALF_UP 2 chữ số mỗi dòng.
- Không nhận discount_amount khác lớn hơn 0 trong request đã hưởng ưu đãi tự động, vì request giảm tổng không phân bổ rõ khoản tiền để cộng dồn an toàn. Booking/voucher giảm riêng tiền sân không bị cấu hình lại bởi module này.
- POS dùng báo giá từ backend và tính lại khi thu tiền; không tin tỷ lệ/đơn giá/giảm giá do client tự gửi. Quote không ghi tồn kho hoặc hóa đơn. Snapshot của hóa đơn không đổi khi khuyến mãi sửa, tắt, hết hạn hoặc xóa.
- Tồn kho vẫn được kiểm tra; giao dịch lỗi rollback cả tồn, header và dòng hóa đơn. SERVICES không trừ tồn.
- Giữ cách tính doanh thu hiện có trên develop bằng tổng total_amount đã lưu trên hóa đơn PAID; bổ sung tổng discount_amount để báo cáo được cả khoản giảm, không tính giảm hai lần.

## API

Mọi URL có prefix `/api`.

| Endpoint | Quyền | Chức năng |
|---|---|---|
| GET /admin/product-promotions | ADMIN | Danh sách cấu hình |
| POST /admin/product-promotions | ADMIN | Tạo chương trình |
| PUT /admin/product-promotions/{id} | ADMIN | Sửa, bật/tắt |
| DELETE /admin/product-promotions/{id} | ADMIN | Ngừng và xóa mềm |
| GET /pos/product-promotions | ADMIN, STAFF | Xem cấu hình chỉ đọc |
| GET /pos/products | ADMIN, STAFF | Sản phẩm còn hiệu lực phục vụ POS |
| POST /pos/quote | ADMIN, STAFF | Báo giá, ưu đãi và tổng tiền từng dòng |
| POST /pos/sales | ADMIN, STAFF | Tính lại, bán và lưu snapshot |

ID trả về ở API cấu hình là ID discount_rules; snapshot promotionId trên dòng hóa đơn là ID promotions.

Ví dụ tạo chương trình:

```json
{"name":"Mua từ 10 cầu giảm 10%","productId":"UUID","minQuantity":10,"discountPercent":10,"validFrom":"2026-10-28","validTo":"2026-11-28","active":true}
```

Frontend: Admin thấy **Quản lý dữ liệu → Khuyến mãi sản phẩm**. STAFF đăng nhập vào POS, chỉ được xem khuyến mãi, không có nút thêm/sửa/bật/tắt/xóa. POS hiển thị tên chương trình, tiền trước giảm, tổng giảm và thực thu; dưới ngưỡng thì tự bỏ ưu đãi.

## Mã nguồn chính

- `ProductPromotionController`, `ProductPromotionService`, `ProductPromotion`/`ProductPromotionRule` và hai Repository: cấu hình, truy vấn hiệu lực và chọn ưu đãi.
- `PosService`: quote và tính lại khi bán; `InvoiceDetail`: lưu snapshot.
- `web-admin/src/ProductPromotions.jsx`: màn hình cấu hình độc lập; `App.jsx`: menu, phân quyền UI và quote POS.
- `ProductPromotionIntegrationTest`: validation, ngày/hoạt động, ngưỡng 9/10/11, gộp dòng, không cộng dồn, làm tròn, snapshot và quyền.
- `ProductPromotionPostgresTest`: cùng suite trên PostgreSQL độc lập khi có BCM_PROMOTION_TEST_DB_URL. Không dùng database thực để chạy test này.

## Kiểm tra

```powershell
npm --prefix web-admin run build
mvn -f backend/pom.xml verify
# Chỉ bật khi URL trỏ database PostgreSQL kiểm thử đã áp dụng toàn bộ SQL ở trên:
$env:BCM_PROMOTION_TEST_DB_URL='jdbc:postgresql://127.0.0.1:55432/your_isolated_test_db'
mvn -f backend/pom.xml verify
```

Các biến BCM_TEST_DB_URL của suite Booking cũ là cấu hình riêng, không tự kích hoạt bởi URL trên.

### Kết quả kiểm tra ngày 28/10/2026

- FE build thành công. Maven verify: 257 bài được phát hiện, 114 chạy đạt, 143 bài PostgreSQL của các module cũ bỏ qua do chưa bật biến DB riêng; không failures/errors.
- Suite khuyến mãi: 10 bài H2 và 11 bài trên PostgreSQL riêng đã áp dụng migration. Bài đồng thời phát 2 request bán 10 sản phẩm cuối cùng: đúng 1 HTTP 201, 1 HTTP 400; tồn 0 và đúng 1 dòng hóa đơn giảm 10.000.
- Migration chạy hai lần thành công trên DB kiểm thử mới. Database local chính đã sao lưu trước khi áp dụng bản nâng cấp.
- Chrome headless qua DB/API riêng: tạo/sửa/bật/tắt chương trình, ngưỡng 9/10, tự bỏ giảm giá khi giảm số lượng, snapshot sau bán, sửa/xóa chương trình không đổi hóa đơn, request bị từ chối rollback tồn và hóa đơn, STAFF chỉ xem, viewport 390px không tràn trang và không lỗi JavaScript.
- Không tạo giao dịch thử vào DB demo chính; Web Admin chính được xác minh đăng nhập và đọc API khuyến mãi thành công.

### Kiểm tra lại sau khi tích hợp develop 4ae82fd

- Giữ menu không biểu tượng, metadata/mã sân và cách cộng total_amount của develop; thêm mục khuyến mãi và quyền STAFF vào giao diện hiện có.
- npm ci và npm run build thành công.
- Maven verify theo schema develop (migration.sql + booking-ux-upgrade.sql + nâng cấp TV2 + nâng cấp khuyến mãi): 374 bài, 123 chạy đạt, 251 bỏ qua; không failures/errors. Riêng khuyến mãi PostgreSQL chạy 11/11 đạt, gồm bán đồng thời.
- Kiểm tra UI bản tích hợp bằng Chrome headless với API local thử nghiệm và DB riêng: tạo/sửa/bật/tắt, ngưỡng số lượng, snapshot, rollback và STAFF chỉ xem đều đạt.
