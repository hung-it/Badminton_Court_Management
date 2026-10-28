# Khuyến mãi chung: sản phẩm, tiền sân, voucher

## Phạm vi
- Admin: mục Khuyến mãi gồm ba tab. Sản phẩm giữ nguyên ngưỡng số lượng và ưu đãi tốt nhất mỗi sản phẩm.
- Tiền sân: nhiều ngưỡng tự động, giảm tiền hoặc %, có trần giảm. Mặc định 300.000 giảm 30.000, 500.000 giảm 50.000. Backend chọn mức giảm lớn nhất.
- Voucher: tạo mã mới, điều kiện tiền sân tối thiểu, phần trăm/số tiền, trần giảm, ngày hiệu lực, bật/tắt, tổng lượt và lượt mỗi khách. Ngày theo clock Booking hiện có; ngày kết thúc được tính bao gồm.
- NEWMEMBER giữ điều kiện khách mới và tối đa một lượt; KMKH10 giữ điều kiện 15 booking COMPLETED có thanh toán xác thực. Hai mã không được xóa/đổi loại, có thể tắt. Không đổi mã và ruleType sau khi tạo; mã lịch sử không được tái sử dụng.
- Voucher hợp lệ nhưng giảm ít hơn ưu đãi tự động không chiếm lượt. Tự động thắng khi bằng nhau. Giảm tiền sân không áp dụng cho sản phẩm.
- Web User nhận đúng số tiền giảm từ backend; không còn gán cứng tỷ lệ 5%/10% trong nhãn.

## API
- ADMIN GET/POST /api/admin/court-promotions, PUT/DELETE /api/admin/court-promotions/{ruleId}.
  GET dùng key SQL snake_case; POST/PUT dùng CourtPromotionRequest camelCase.
- Booking API preview/apply/remove giữ nguyên contract, ownership và khóa sau khi đã tạo payment attempt.
- ADMIN/STAFF POST /api/pos/bookings/{id}/promotion với {code}: dùng cùng chính sách với Web User cho booking PENDING còn hạn và chưa có payment attempt.
- ADMIN/STAFF POST /api/pos/bookings/{id}/receive với {paymentMethod: CASH|BANK_TRANSFER}: nhân viên xác nhận đã nhận đủ tiền sân sau giảm. Số tiền do server lấy từ booking. Chỉ dành booking chưa có giao dịch thanh toán; không thay thế xử lý VNPay hay xác nhận giao dịch online đang chờ. Lưu người xác nhận trong note STAFF_RECEIVED. Lặp lại không tạo receipt mới.
- POS /quote và /sales nhận bookingId khi mua thêm hàng. Booking phải PAID/CHECKED_IN; server lấy customer từ booking, không nhận lại tiền sân hay giảm giá nhập tay. Dòng hàng được snapshot và lưu DRAFT để thu khi checkout. Khách lẻ không có booking vẫn bán PAID ngay.
- GET /api/checkout/{bookingId}: xem bill thật từ DB. POST cùng URL: kết toán một lần, hỗ trợ CASH cho khoản hàng còn phải thu. Booking chuyển COMPLETED; chặn thêm hàng/kết toán lặp.

## Bill và thanh toán
Dùng invoices, invoice_details, bookings, payment_transactions hiện có; không thêm bảng tổng bill.
- total_amount = court_fee gốc + product_fee gốc - giảm sân - giảm sản phẩm.
- paidCourtAmount = tiền sân sau giảm đã thanh toán.
- amountDue = tiền hàng sau giảm cần thu tại checkout.
- Response bổ sung courtDiscount, productDiscount, paidCourtAmount, amountDue. Không thu lại total_amount khi sân đã trả.
- Hóa đơn con DRAFT chuyển CANCELLED khi chi tiết chuyển sang bill PAID tổng. Khóa booking chung giữa thêm hàng, nhận tiền sân và checkout để không mất giao dịch đồng thời.
- Không sửa giá/ưu đãi các giao dịch đã thanh toán khi sửa hoặc tắt chương trình.

## Database và triển khai
Giữ ddl-auto: validate và SQL init never. Sao lưu trước khi nâng cấp DB đang sử dụng.
Trên schema develop đã chạy booking-ux-upgrade.sql và member2-upgrade.sql, chạy product-promotions-upgrade.sql rồi court-promotions-upgrade.sql với psql -v ON_ERROR_STOP=1.
Migration mới chỉ thêm max_discount_amount, max_total_usage vào discount_rules và seed hai ngưỡng tiền sân nếu chưa có; chạy lại không ghi đè cấu hình Admin. Script local và Docker volume mới đã nối bước này.

## Lượt voucher và concurrency
Khóa row campaign trước khi kiểm tra tổng lượt/lượt mỗi khách. Booking PENDING còn hạn giữ lượt nếu chưa có attempt hoặc có attempt PENDING; booking thành công được tính cùng lịch sử POS. Preview không persist và không giữ lượt; apply chỉ giữ nếu voucher thực sự được chọn. Hết hạn/gỡ mã/attempt thất bại giải phóng reservation theo trạng thái. Customer lock và điều kiện nhận diện NEWMEMBER/KMKH10 hiện có được giữ lại.

## Vị trí code
CourtPromotionService/Controller/Request: cấu hình và tính ngưỡng/giới hạn.
BookingPromotionService: dùng chính sách chung, kiểm tra khách hàng, apply tại quầy và nhận tiền sân.
Promotions.jsx: ba tab; ProductPromotions.jsx: màn hình sản phẩm cũ.
PosService: hàng mua thêm gắn booking; CheckoutService: gộp bill và tách khoản đã trả/còn thu.
BookingPromotionPostgresTest: ngưỡng, voucher mới/cap, snapshot, tranh lượt, nhận tiền một lần và bill sản phẩm giảm riêng.

## Kiểm chứng bản tích hợp ngày 28/10/2026
- Maven verify trên PostgreSQL riêng: 515 tests, 0 failures, 0 errors, 0 skipped. Dùng heap Maven 256 MB, test JVM 512 MB, context cache 2 và Hikari pool 5 để phù hợp máy local.
- npm run build của web-admin và web-app đều thành công; git diff --check sạch.
- UI thật: đăng nhập Admin, ba tab, hai ngưỡng mặc định, tạo voucher tùy ý với giới hạn lượt và ngày không hết hạn, lưu trạng thái tắt, xóa mềm voucher thử nghiệm và màn hình lấy bill từ backend.
- Hai fixture Booking cũ được điều chỉnh để phù hợp exclusion constraint Master Data: tăng grid bằng khung giờ không trùng; kiểm tra create/edit cùng giờ bằng hai sân khác nhau. Không gỡ ràng buộc DB và không bỏ qua test.
- Bản demo local dùng bcm_unified_promotions_demo (DB riêng), backend 8081, Web Admin 5173, Web User 5174. Database bcm_member2_test cũ không bị nâng cấp/ghi đè bởi bản tích hợp này.
- POS áp dụng ưu đãi cho booking PENDING theo UUID; không mở rộng màn hình tạo booking tại quầy trong thay đổi này. Booking có payment attempt online tiếp tục dùng luồng online hiện có.
