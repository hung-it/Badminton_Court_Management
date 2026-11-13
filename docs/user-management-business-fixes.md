# Sửa nghiệp vụ sau review user-management — 13/11/2026

Các bản sửa đã được đưa vào `develop`, nền feature `a450c25`. Không cần chuyển sang worktree review để chạy project.

## Hành vi sau sửa

- Upgrade không còn thay chữ “demo” trong email, voucher, tham chiếu hoặc snapshot giao dịch. Không tự đảo dữ liệu đã từng bị thay; phải đối chiếu backup nếu gặp trường hợp đó.
- Sửa hồ sơ quản trị khóa User rồi Customer và đọc lại entity sau khóa. Sửa thông tin không mở khóa tài khoản hoặc giảm token version. Hồ sơ đã khóa vẫn sửa được; mở khóa là thao tác riêng.
- Lưu/xóa danh mục và tạo/chuyển sản phẩm dùng chung khóa Category; thứ tự Category → Product. Không tạo sản phẩm active vào danh mục vừa bị xóa.
- POS bán lẻ tìm khách theo SĐT: 0 hồ sơ thì tạo vãng lai khi lưu đơn; 1 hồ sơ thì chọn sẵn; nhiều hồ sơ phải chọn. Lịch sử cũ không được chuyển hay gộp.
- Backend xác minh lại customerId/SĐT/trạng thái sau khi lấy khóa User → Customer, trước khi khóa sản phẩm. Khách đã đổi hoặc khóa trả 409 và rollback; retry thành công cùng idempotency key không tạo thêm đơn.
- Ma trận sinh cột theo ngày giới hạn 366 ngày tính cả hai đầu, kể cả khi khoảng ngày được suy ra từ DB. Ngày phải thuộc năm 1–9998. Backend kiểm tra trước khi dựng cột; frontend chặn khoảng tường minh quá dài.
- Seed danh mục sân chỉ cập nhật khi cần chuyển nhãn cũ hoặc bổ sung district trống; chạy lại không thay timestamp của bản ghi đã đúng.

## API POS

`GET /api/pos/customers?phone=<SĐT>` dành cho ADMIN/STAFF, chỉ trả hồ sơ active có SĐT khớp sau khi bỏ ký tự phân cách. Không tự đổi `+84` thành `0` trong đợt sửa này.

Response vẫn nằm trong `ApiResponse.data`, là mảng:

```json
[{"id":"<customer UUID>","fullName":"Tên khách","phone":"0912345678","kind":"REGISTERED"}]
```

`kind` là `REGISTERED` hoặc `WALK_IN`. Bỏ tham số phone vẫn trả danh sách active để tương thích client cũ.

`POST /api/pos/sales` dùng trường `customerId` hiện có cùng `customerPhone` cho bán lẻ. Thiếu lựa chọn khi trùng SĐT, ID sai/SĐT không khớp hoặc khách vừa bị khóa trả 409. Luồng booking tiếp tục lấy khách từ booking; ID do client gửi không thay được chủ booking. Quote và tra cứu không tạo khách.

## BANK_TRANSFER và dữ liệu cũ

Luồng mới chỉ dùng CASH/VNPAY. Giữ test và preflight từ chối phương thức không hỗ trợ; không có chế độ tương thích đọc BANK_TRANSFER.

Hóa đơn `b2000000-0000-4000-8000-000000000103` và dòng `b2000000-0000-4000-8000-000000000124` được đối chiếu với `3b57b62:db/revenue-breakdown-sample.sql`: 60.000đ, 6 sản phẩm × 10.000đ, ngày 14/03/2026 08:45, không có receipt/voucher hoặc liên kết phát sinh.

Script opt-in:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/remove-legacy-transfer-seed.ps1 -Database badminton_court_db
```

Wrapper tạo full backup, kiểm tra archive rồi mới gọi SQL. SQL so toàn bộ bản ghi gốc, khóa dữ liệu, từ chối fixture đã sửa/có phụ thuộc và chỉ xóa hai bản ghi xác định. Không xóa khách/sản phẩm hoặc cập nhật tồn kho. Chạy lại khi fixture không còn là no-op. Không tự chạy khi start hoặc upgrade; không dùng cho chứng từ thật.

Đã diễn tập restore backup và dọn fixture trên DB riêng; thử sửa fixture để xác nhận bị chặn; kiểm tra hash toàn bộ bảng ngoài hai bản ghi không đổi. Sau đó áp dụng đúng cleanup đã duyệt trên DB gốc, không chạy bộ schema upgrade trên DB gốc.

**DB gốc mới:** theo lựa chọn của người dùng, giữ backup DB cũ và dùng DB test tay đầy đủ làm nền cho `badminton_court_db`. Bản sao được nâng cấp, kiểm tra bảo toàn toàn bộ identity, giá, snapshot, receipt và tồn kho trước khi đổi tên. Không đổi mã hoặc xóa 3 sân có mã âm trong DB cũ; DB cũ được lưu trong backup thay vì dùng làm runtime.

## Kiểm chứng và chạy lại

Kết quả cuối ngày 13/11/2026:

| Kiểm tra | Kết quả |
|---|---|
| Backend regression, lượt 1 | 816/816, 0 skip |
| Backend regression, lượt 2 | 816/816, 0 skip |
| HTTP acceptance qua MockMvc | 27/27, 0 skip |
| Build web-admin và web-app | Đạt |
| Browser POS: booking, CASH/VNPAY, trùng SĐT, chọn khách, desktop/mobile | Đạt |
| Browser thống kê: chặn 367 ngày, nhận 366 ngày | Đạt |
| Upgrade DB mới + chạy lại, bảo toàn định danh/voucher/receipt | Đạt |
| Restore/dọn fixture cũ, chạy lại, chặn fixture bị sửa, hash ngoài phạm vi | Đạt |
| Preflight BANK_TRANSFER trên bản sao chưa dọn | Chặn đúng, giữ nguyên chứng từ |
| Upgrade bản sao DB gốc sau dọn fixture | Chặn tại 3 mã sân âm đã nêu; không nâng cấp DB gốc |
| DB runtime mới từ bản sao test tay, theo lựa chọn sau đó của người dùng | Đã nâng cấp, kiểm tra bảo toàn dữ liệu và chuyển thành badminton_court_db |
| git diff --check | Đạt |

Tổng bắt buộc hiện tại: 843 test trong một lượt regression + acceptance. Lượt regression thứ hai xác nhận lại 816 test, không cộng thành test mới.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-p1.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-p1-acceptance.ps1
```

Các test mới kiểm tra stale entity sau khóa, thứ tự xen kẽ ngược lại, token vẫn bị thu hồi sau mở khóa, mua vãng lai → đăng ký → chọn khách → mua tiếp, lookup theo quyền, rollback khi chọn sai khách, retry, hai thứ tự xóa danh mục/tạo sản phẩm, khoảng ngày biên và upgrade đầy đủ hai lần giữ nguyên định danh thực.

Fixture POS cũ được bổ sung SĐT; giữ assertion tài chính/tồn kho. Hai assertion seed bị đổi nhầm thành `LIKE '%'` được khôi phục thành kiểm tra tiền tố `[DEMO]`, không bỏ kiểm tra bảo toàn dữ liệu hoặc tính lặp lại.

Test trình duyệt dùng API fixture riêng, không ghi vào DB chạy demo:

```powershell
# Cần PLAYWRIGHT_MODULE nếu Playwright không nằm trong node_modules local.
# ADMIN_TEST_URL trỏ Vite của đúng checkout đang kiểm tra.
node web-admin/pos-customer-mode.e2e.cjs
node web-admin/statistics-range.e2e.cjs
```

Bằng chứng các lượt chạy và backup diễn tập nằm tại:
`D:\BCM\Badminton_Court_Management\.tools\review-evidence-20261113`.

Kết quả chuyển DB và chạy trực tiếp trên develop nằm ở `.tools/develop-original-db-20261113`.
Backup DB gốc cũ: `backups/develop-original-before-upgrade-20261113.dump`.
Backup DB test tay đã dùng làm DB gốc mới: `backups/bcm-manual-complete-20261113.dump`.
Các DB test khác và container test đã được sao lưu rồi dọn; chỉ còn một PostgreSQL runtime và một DB nghiệp vụ `badminton_court_db`. Database `postgres` là DB quản trị hệ thống PostgreSQL.

## Trước khi merge và triển khai

1. Hai lượt backend regression sạch liên tiếp, acceptance, frontend build và browser checks phải đạt.
2. Review diff trên nhánh sửa; không lấy thay đổi `.env.example` của checkout chính vào nhánh.
3. Backup và restore thử DB đích, kiểm tra mã sân/định danh/receipt/phương thức thanh toán cũ trước upgrade.
4. Chạy upgrade trên bản sao trước, kiểm tra lại snapshot và lần chạy lặp lại; chỉ triển khai DB đáp ứng điều kiện.
5. Nếu upgrade bị chặn, giữ dữ liệu để đối chiếu, không tự đổi chứng từ hay tắt constraint. Khôi phục bản backup đã thử restore nếu cần rollback schema.
