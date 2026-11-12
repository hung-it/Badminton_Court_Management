# Khuyến mãi sản phẩm, tiền sân và voucher

Hợp nhất các mô tả khuyến mãi cũ. Schema tại `db/upgrades/product-promotions-upgrade.sql`, `court-promotions-upgrade.sql`; nâng cấp theo [db/README](../db/README.md).

## Sản phẩm

- Cộng số lượng các dòng cùng productId trước khi xét ngưỡng. Chọn mức giảm tốt nhất mỗi sản phẩm, không cộng dồn; tỷ lệ lớn hơn 0 và không quá 100%.
- Hiệu lực theo ngày kinh doanh, gồm ngày bắt đầu/kết thúc; hỗ trợ bật/tắt, xóa mềm. Tiền giảm làm tròn HALF_UP hai chữ số thập phân.
- Backend báo giá và tính lại khi bán; client không quyết định giá/tỷ lệ giảm. Quote không ghi tồn/hóa đơn.
- Giá và ưu đãi của hóa đơn là snapshot. Sửa/tắt chương trình không tính lại giao dịch đã lưu.
- GOODS kiểm tra/trừ tồn; SERVICE không trừ tồn. Lỗi rollback tồn kho và hóa đơn cùng nhau.

## Tiền sân và voucher

- Ưu đãi tự động có ngưỡng giá trị, giảm cố định hoặc phần trăm và trần giảm. Chọn mức giảm tốt nhất.
- Voucher có mã, ngưỡng tối thiểu, hiệu lực, trần giảm, tổng lượt và lượt mỗi khách. Không đổi mã/loại sau khi tạo hoặc tái sử dụng mã lịch sử.
- NEWMEMBER dành cho khách mới, tối đa một lượt; KMKH10 kiểm tra lịch sử booking hoàn thành và thanh toán xác thực theo rule backend.
- Khi voucher giảm ít hơn hoặc bằng ưu đãi tự động, ưu đãi tự động thắng và không chiếm lượt voucher.
- Booking PENDING còn khả năng thanh toán giữ reservation, kể cả attempt PENDING trong cửa sổ chờ callback. Chỉ nhả theo thất bại cuối cùng/expiration nguyên tử; preview không giữ lượt.
- Giảm tiền sân tách khỏi giảm sản phẩm; không sửa snapshot giao dịch đã thanh toán.

## API và chứng từ

ADMIN quản lý `/api/admin/product-promotions`, `/api/admin/court-promotions`. ADMIN/STAFF xem `/api/pos/product-promotions`, báo giá/bán qua `/api/pos/quote`, `/api/pos/sales`. Áp dụng mã POS qua `/api/pos/bookings/{id}/promotion` cho booking hợp lệ. API booking kiểm tra ownership, trạng thái và khóa thay đổi ưu đãi theo payment attempt.

Tiền bill sau giảm = tiền sân gốc + tiền hàng gốc − giảm sân − giảm sản phẩm. Khoản đã nhận/số còn phải thu dựa trên receipt hợp lệ; không thu lại toàn bộ bill khi sân đã trả. Draft gộp vào bill tổng không tính doanh thu hai lần. Chi tiết: [payment/invoice](payment-invoice-flow.md).

Kiểm thử: `BookingPromotionPostgresTest`, `ProductPromotionPostgresTest`, các suite POS/checkout; xem [testing](testing.md).
