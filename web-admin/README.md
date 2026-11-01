# Web Admin — Thành viên 2
React 18 + Vite. Không xử lý ảnh sản phẩm trong phiên bản này.

## Chạy
```powershell
npm ci
npm run dev
```
Mở http://127.0.0.1:5173. Backend chạy ở localhost:8080; Vite chuyển tiếp `/api` đến backend.
Web-admin dùng `strictPort: true`; cần giải phóng cổng 5173 nếu tiến trình khác đang sử dụng.
Đăng nhập bằng tài khoản ADMIN. Database phát triển được seed tài khoản `admin@bcm.com` / `admin123` theo nền tảng có sẵn.

## Bán tại quầy

- **Trả sân** là mục duy nhất cho danh sách booking và kết toán. Danhsách giữ mã rút gọn 8 ký tự, sao chép UUID đầy đủ và tìm kiếm; chọn **Kết toán** mở bill cùng ba phương thức thanh toán. Đóng bill quay về danh sách; đơn kết toán thành công được bỏ khỏi danh sách đang hoạt động.
- Nếu tiền sân đã được xác nhận và không phát sinh tiền hàng, bill còn 0đ: chỉ **Hoàn tất trả sân**, không chọn phương thức thu tiền. Với giao dịch khách tự khai chuyển khoản, nhân viên đối soát khoản đã chuyển; tích xác nhận sẽ khấu trừ ngay trên bill, không yêu cầu khách chuyển lại. Ba phương thức thu tiền chỉ hiện khi còn tiền phát sinh cần thu sau đối soát.

- Trong **Đơn hàng hiện tại**, chọn **Khách thuê sân** và nhập **booking UUID** của đơn trong mục **Trả sân** (không phải UUID sân vật lý).
- Đơn phải đã thanh toán sân và chưa kết toán. Hệ thống kiểm tra booking khi báo giá và khi lưu hàng.
- **Thêm hàng & xem bill gộp** lưu hàng vào booking rồi mở phiếu kết toán gồm tiền sân và hàng hóa. Tiền sân đã thanh toán được khấu trừ khỏi số còn phải thu; xác nhận thu tiền ở màn hình Trả sân sẽ hoàn tất lượt thuê.
- Chọn **Khách vãng lai** để thu tiền hàng ngay bằng tiền mặt; request không gắn booking và hóa đơn không có tiền sân.
- Tái sử dụng `/pos/quote`, `/pos/sales` và `/checkout/{bookingId}`; không thay đổi schema.
- Khi khách tự khai đã chuyển khoản, phiếu kết toán yêu cầu nhân viên xác nhận thực nhận tiền sân. `courtReceiptConfirmed=true` đối soát giao dịch cùng transaction với hóa đơn; không đánh dấu thành công chỉ từ lời khai khách. Hóa đơn gồm cả sân và hàng hóa được tính một lần vào báo cáo; tổng còn phải thu khấu trừ tiền sân đã nhận. Admin được chuyển tới Doanh thu & Báo cáo sau kết toán.

## Nhập kho

- Ba thống kê: **Số phiếu nhập kho**, **Số nhà cung cấp**, **Tổng giá trị nhập** (tổng giá trị các phiếu chưa hủy, bao gồm chờ duyệt).
- **Tạo phiếu nhập** yêu cầu nhập tay tên nhà cung cấp, tên loại sản phẩm, số lượng nguyên dương và tổng tiền; không dùng danh sách chọn. Ngày nhập theo múi giờ Việt Nam.
- `POST /api/import-orders/manual` nhận `supplierName`, `productName`, `quantity`, `totalAmount`. Tên trùng duy nhất (không phân biệt hoa thường) được dùng lại; tên mới được tạo cùng transaction với phiếu. Nhiều bản ghi trùng tên trả 409 thay vì chọn nhầm. Dùng khóa row danh mục để tránh tạo trùng trong các yêu cầu nhập tay đồng thời.
- Sản phẩm mới thuộc danh mục **Hàng nhập kho**, đơn vị **cái**, giá bán mặc định **0đ**, tồn kho **0** trước khi duyệt phiếu. Duyệt giá bán và cập nhật đơn vị trong **Hàng hóa & dịch vụ** trước khi bán. Không đổi giá hoặc đơn vị của sản phẩm đã có.
- Danh sách phiếu có cột **Trạng thái** để xem ngay đã duyệt hay chưa. Duyệt phiếu nhập chỉ tăng tồn kho; tách biệt với duyệt giá bán.
- Trong **Hàng hóa & dịch vụ**, hàng thuộc **Hàng nhập kho** có nhãn **Chờ duyệt giá / Đã duyệt** và nút **Duyệt giá niêm yết**. Admin nhập giá >0 rồi **Duyệt & cho phép bán**. API `POST /api/admin/products/{id}/approve-price` nhận `price`, `version`; phiên bản cũ trả 409, không thay đổi tồn kho. Màn hình sửa thông tin thông thường không được đổi giá hàng nhập kho hoặc chuyển hàng chưa duyệt sang danh mục khác để bỏ qua bước duyệt.
- Trạng thái giá được derive từ `products.price` hiện có: 0đ là chờ duyệt; giá >0 là đã duyệt (bao gồm giá đã thiết lập trước tính năng này). Không thêm trạng thái/column hay lịch sử duyệt riêng vào schema. `priceApprovalManaged` chỉ là metadata runtime, không persist. POS ẩn hàng nhập kho giá 0 và cả báo giá/tạo đơn đều chặn mã sản phẩm chưa duyệt dù gọi API trực tiếp.
- Danh sách chỉ có **Mã phiếu / Ngày nhập / Tổng tiền / Trạng thái**. Bấm **Chi tiết** cạnh mã phiếu để tải phiếu đã lưu từ DB, xem nhà cung cấp/sản phẩm/số lượng/tổng tiền/trạng thái và duyệt cộng tồn kho; tạo phiếu chưa tự tăng tồn kho.
- Giữ API/schema hiện tại. Tổng tiền được phân bổ thành đơn giá có 2 số thập phân; khi cần, cùng sản phẩm có hai dòng giá lệch 0,01đ để tổng lưu chính xác. UI gộp các dòng này khi xem chi tiết.

## Mã giảm giá sản phẩm tại POS

- Khi nhập mã vào POS, ngưỡng số lượng tính trên toàn giỏ; không bắt buộc có mặt hàng gốc của chương trình. Tỷ lệ mã áp cho các dòng hàng, chọn mức giảm tốt hơn giữa mã và ưu đãi tự động trên mỗi dòng. Không nhập mã vẫn giữ luồng tự giảm theo đúng sản phẩm đã cấu hình.

- Trong **Khuyến mãi → Theo sản phẩm**, đặt **Mã giảm giá** khi tạo chương trình (2–50 chữ/số/gạch ngang/gạch dưới). Để trống sẽ tự sinh mã; mã hiện trong danh sách, chương trình cũ dùng nguyên mã đã lưu. Mã không đổi sau khi tạo.
- Trong **Bán tại quầy → Đơn hàng hiện tại**, nhập mã vào **Mã giảm giá**. Backend tự kiểm tra khi báo giá và kiểm tra lại khi lưu: đủ tổng số lượng trong giỏ, đang bật và còn hiệu lực. Mã lỗi chặn lưu đơn cho tới khi sửa hoặc xóa mã.
- `promotionCode` là field tùy chọn của `/pos/quote` và `/pos/sales`. Áp dụng cả đơn khách vãng lai và đơn gắn booking; chỉ giảm tiền hàng, không đổi tiền sân. Không nhập mã vẫn giữ luồng tự áp dụng ưu đãi tốt nhất; nhập mã không giảm thêm lần hai. Snapshot ưu đãi tiếp tục lưu trong invoice details.
- Tái sử dụng `promotions.code` và các rule PRODUCT hiện có; không thay đổi schema hoặc voucher tiền sân.

## Lịch sử hóa đơn doanh thu

- Admin xem hóa đơn đã thanh toán theo ngày/tuần/tháng tại **Doanh thu & Báo cáo**, phân trang 20 dòng. Hóa đơn nháp, đã hủy, hoàn tiền hoặc xóa mềm không được tính vào lịch sử doanh thu hiện tại.
- **Chi tiết** hiển thị booking, sân/khung giờ, các mặt hàng, số lượng, đơn giá, ưu đãi và tổng hóa đơn đã lưu. Tổng doanh thu gồm cả tiền sân đã trả trước; khác với số còn phải thu tại quầy.
- API ADMIN: `GET /api/analytics/invoices?period=DAY|WEEK|MONTH&date=YYYY-MM-DD&page=0`, `GET /api/analytics/invoices/{id}`. Dùng cùng khoảng ngày với API tổng doanh thu; ID không có/không thuộc hóa đơn doanh thu trả 404.
- Giá và giảm giá đọc từ snapshot hóa đơn/booking details. Schema chưa lưu snapshot tên sản phẩm/sân: tên hiển thị theo danh mục hiện tại; không dựng lại chi tiết đã bị xóa.

## Build
```powershell
npm run build
```
Khi triển khai `dist`, cấu hình reverse proxy `/api` đến Spring Boot. `vite preview` chỉ dùng để xem bản build, không thay thế cấu hình triển khai.

## Tích hợp cho leader
- Các màn hình và form nằm trong `src/App.jsx`; CSS trong `src/styles.css`.
- Bốn màn hình: sân, khung giờ, danh mục, hàng hóa/dịch vụ.
- JWT giữ trong sessionStorage, xóa khi đăng xuất; đăng nhập chỉ nhận ADMIN.
- Hệ số mặc định 17–21h là 1.5, giờ khác 1.0; quản trị viên được chỉnh riêng.
- Tồn kho chỉ nhập khi tạo; không có nghiệp vụ nhập/xuất kho.
- Có tìm kiếm, lọc, trạng thái tải/lỗi, xác nhận xóa và giao diện điện thoại.
