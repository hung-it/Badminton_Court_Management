# Web Admin — Thành viên 2
React 18 + Vite. Không xử lý ảnh sản phẩm trong phiên bản này.

## Chạy
```powershell
npm ci
npm run dev
```
Mở http://127.0.0.1:5173. Backend chạy ở localhost:8080; Vite chuyển tiếp `/api` đến backend.
Đăng nhập bằng tài khoản ADMIN. Database phát triển được seed tài khoản `admin@bcm.com` / `admin123` theo nền tảng có sẵn.

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
