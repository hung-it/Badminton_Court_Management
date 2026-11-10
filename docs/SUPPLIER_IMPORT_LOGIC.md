# LOGIC NHÀ CUNG CẤP & NHẬP KHO — Tài liệu phân tích

**Ngày:** 29/10/2026
**Phạm vi:** Web Admin — tab "Nhập kho" (`web-admin/src/App.jsx`) và API backend
`/api/suppliers`, `/api/import-orders`
**Người lập:** Thành viên 1 (Leader)

---

## Mục lục

1. [Mô hình dữ liệu](#1-mô-hình-dữ-liệu)
2. [Luồng nghiệp vụ end-to-end](#2-luồng-nghiệp-vụ-end-to-end)
3. [Chi tiết từng luồng](#3-chi-tiết-từng-luồng)
4. [Mối liên hệ với POS](#4-mối-liên-hệ-với-pos)
5. [Phân quyền](#5-phân-quyền)
6. [Bảng API](#6-bảng-api)
7. [Danh sách lỗi cần sửa](#7-danh-sách-lỗi-cần-sửa)
8. [Câu hỏi thường gặp khi demo](#8-câu-hỏi-thường-gặp-khi-demo)

---

## 1. Mô hình dữ liệu

Nguồn chuẩn: `db/migration.sql`

```text
suppliers ──1:N──> import_orders ──1:N──> import_order_details ──N:1──> products
                                                                       │
                                                        stock_quantity, @Version
```

### Bảng `suppliers`

| Cột | Kiểu | Ràng buộc | Ý nghĩa |
| --- | --- | --- | --- |
| `id` | UUID | PK | |
| `name` | VARCHAR(255) | NOT NULL | Tên nhà cung cấp |
| `phone` | VARCHAR(20) | nullable | |
| `address` | TEXT | nullable | |
| `created_at` / `updated_at` | TIMESTAMP | NOT NULL | |
| `deleted_at` | TIMESTAMP | nullable | **Soft delete** |

### Bảng `import_orders`

| Cột | Kiểu | Ràng buộc | Ý nghĩa |
| --- | --- | --- | --- |
| `id` | UUID | PK | |
| `supplier_id` | UUID | NOT NULL, FK → `suppliers(id)` **ON DELETE RESTRICT** | |
| `status` | VARCHAR(20) | NOT NULL, DEFAULT `'DRAFT'`, **CHECK IN ('DRAFT','CONFIRMED','RECEIVED','CANCELLED')** | Vòng đời phiếu |
| `total_amount` | NUMERIC(10,2) | NOT NULL | Tổng tiền snapshot lúc lập/sửa phiếu |
| `import_date` | DATE | NOT NULL | Ngày nhập hàng |
| `created_by` / `updated_by` | UUID | FK → `staffs(id)` | Nhân viên lập/sửa |
| `deleted_at` | TIMESTAMP | nullable | Soft delete |

### Bảng `import_order_details`

| Cột | Kiểu | Ràng buộc | Ý nghĩa |
| --- | --- | --- | --- |
| `id` | UUID | PK | |
| `import_order_id` | UUID | NOT NULL, FK → `import_orders(id)` **ON DELETE CASCADE** | |
| `product_id` | UUID | NOT NULL, FK → `products(id)` **ON DELETE RESTRICT** | |
| `quantity` | INT | NOT NULL, **CHECK (quantity > 0)** | Không cho nhập âm → không làm phiếu trả hàng |
| `import_price` | NUMERIC(10,2) | NOT NULL | Đơn giá nhập (snapshot) |

### Bảng `products` (phần liên quan)

| Cột | Kiểu | Ràng buộc | Ý nghĩa |
| --- | --- | --- | --- |
| `type` | VARCHAR(20) | CHECK IN ('GOODS','SERVICE') | GOODS = có tồn kho; SERVICE = dịch vụ, không tồn kho |
| `price` | NUMERIC(10,2) | CHECK (price >= 0) | Giá bán ra |
| `stock_quantity` | INT | NOT NULL, DEFAULT 0, **CHECK (stock_quantity >= 0)** | Tồn kho |
| `version` | INT | NOT NULL, DEFAULT 0 | **Optimistic lock** (`@Version` trong entity) |

Index phụ trợ: `idx_products_type_stock ON products(type, stock_quantity) WHERE type = 'GOODS'`

---

## 2. Luồng nghiệp vụ end-to-end

```text
┌─────────────────────────────────────────────────────────────────┐
│ 0. CHUẨN BỊ NHÀ CUNG CẤP                                      │
│    POST /api/suppliers   (hiện CHƯA có màn hình trên UI)        │
│    → tạo bản ghi suppliers                                     │
└──────────────────────────┬──────────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│ 1. LẬP PHIẾU NHẬP                                             │
│    UI: tab "Nhập kho" → [＋ Lập phiếu nhập]                      │
│    POST /api/import-orders                                      │
│    → status = DRAFT                                             │
│    → total_amount = Σ(import_price × quantity)                  │
│    → TỒN KHO KHÔNG THAY ĐỔI                                     │
└──────────────────────────┬──────────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│ 2. DUYỆT PHIẾU NHẬP                                            │
│    UI: nút [✓ Duyệt nhập kho]                                   │
│    POST /api/import-orders/{id}/approve                         │
│    → cộng stock_quantity của từng product                       │
│    → status = RECEIVED                                          │
└──────────────────────────┬──────────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│ 3. BÁN HÀNG TẠI QUẦY (POS)                                     │
│    UI: tab "Bán tại quầy"                                       │
│    POST /api/pos/sale                                           │
│    → trừ stock_quantity (chỉ type = GOODS)                       │
└─────────────────────────────────────────────────────────────────┘
```

**Điểm cốt lõi:** `stock_quantity` là điểm nối hai chiều giữa kho và bán hàng.

```text
Nhập kho (duyệt phiếu)  ──►  stock_quantity TĂNG
Bán tại quầy (POS)      ──►  stock_quantity GIẢM
```

---

## 3. Chi tiết từng luồng

### 3.1 Vòng đời trạng thái phiếu nhập

Định nghĩa trong `backend/src/main/java/com/bcm/service/ImportOrderService.java:30-32`:

```java
private static final String DRAFT_STATUS     = "DRAFT";
private static final String CONFIRMED_STATUS = "CONFIRMED";
private static final String RECEIVED_STATUS  = "RECEIVED";
```

Ràng buộc DB: `CHECK (status IN ('DRAFT','CONFIRMED','RECEIVED','CANCELLED'))`

```text
   Lập phiếu              Duyệt (duyệt kho)             Hủy
      │                        │                          │
      ▼                        ▼                          ▼
   DRAFT ─────────────────► RECEIVED                   CANCELLED
      │                                                     ▲
      └─────────────────────────────────────────────────────┘
                    (ensureEditable chặn)

   CONFIRMED: có trong DB constraint + ensureEditable, nhưng KHÔNG có
              endpoint nào chuyển tới → trạng thái không dùng được
```

**Chỉ `RECEIVED` mới cộng tồn kho.** `DRAFT` và `CONFIRMED` đều là "trước khi nhận hàng thật".

### 3.2 Luồng 0 — Quản lý nhà cung cấp

**Backend**: `SupplierService.java`

| Hành viện | Endpoint | Logic |
| --- | --- | --- |
| Danh sách | `GET /api/suppliers` | `findAllByDeletedAtIsNullOrderByNameAsc()` — bỏ soft-deleted, sắp theo tên A→Z |
| Chi tiết | `GET /api/suppliers/{id}` | Tìm theo id, lọc `deleted_at IS NULL`, không có → 404 |
| Tạo | `POST /api/suppliers` | `name.trim()`, `phone`, `address` |
| Sửa | `PUT /api/suppliers/{id}` | Ghi đè 3 field |
| Xoá | `DELETE /api/suppliers/{id}` | **Soft delete** — `supplier.softDelete()` set `deleted_at` |

Validate đầu vào (`SupplierRequest`):

- `name`: `@NotBlank`, `@Size(max = 255)`
- `phone`: `@Size(max = 20)`
- `address`: không ràng buộc

**UI**: `web-admin/src/App.jsx` chỉ dùng `GET /api/suppliers` để đổ dropdown (dòng 71, 318) và đếm số ở thẻ thống kê (dòng 302). **Không có màn hình tạo/sửa/xoá NCC.**

### 3.3 Luồng 1 — Lập phiếu nhập

**UI**: tab "Nhập kho" → nút `＋ Lập phiếu nhập` (`App.jsx:298`)

Modal (`App.jsx:317-322`) có 4 trường:

| Trường | Kiểm soát | Ghi chú |
| --- | --- | --- |
| Nhà cung cấp | `<select>` | Mặc định lấy `supps[0].id` (dòng 73-74) |
| Sản phẩm nhập | `<select>` | Chỉ hiện `p.type === 'GOODS'` — dịch vụ không có tồn kho |
| Số lượng nhập | `<input type="number" min="1">` | |
| Đơn giá nhập | `<input type="number" min="0">` | Mặc định 50000 |

**Payload** (`App.jsx:164-168`):

```js
{
  supplierId: importForm.supplierId,
  importDate: new Date().toISOString().slice(0, 10),
  details: [{
    productId: importForm.productId || data.products.find(p => p.type === 'GOODS')?.id || data.products[0]?.id,
    quantity: Number(importForm.quantity),
    importPrice: Number(importForm.importPrice)
  }]
}
```

**Backend** — `ImportOrderService.create()` (dòng 49-63):

```java
requireSupplier(request.getSupplierId());      // → 404 nếu NCC không tồn tại / đã xoá
ImportOrder order = ImportOrder.builder()
        .supplierId(request.getSupplierId())
        .status(DRAFT_STATUS)                    // ← DRAFT
        .totalAmount(calculateTotal(request.getDetails()))
        .importDate(request.getImportDate())
        .createdBy(request.getStaffId())
        .updatedBy(request.getStaffId())
        .build();
ImportOrder savedOrder = importOrderRepository.saveAndFlush(order);
saveDetails(savedOrder.getId(), request.getDetails());
```

`calculateTotal()` (dòng 132-136) — tổng tiền do backend tự tính, không tin client:

```java
return details.stream()
        .map(detail -> detail.getImportPrice().multiply(BigDecimal.valueOf(detail.getQuantity())))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
```

**Validate đầu vào** (`ImportOrderRequest` / `ImportOrderDetailRequest`):

| Field | Ràng buộc |
| --- | --- |
| `supplierId` | `@NotNull` |
| `importDate` | `@NotNull` |
| `staffId` | optional (không bắt buộc) |
| `details` | `@NotEmpty`, `@Valid` |
| `details[].productId` | `@NotNull` |
| `details[].quantity` | `@NotNull`, `@Positive` (> 0) |
| `details[].importPrice` | `@NotNull`, `@DecimalMin("0.0")` inclusive |

### 3.4 Luồng 2 — Duyệt phiếu (nơi tồn kho tăng)

**UI**: nút `✓ Duyệt nhập kho` (`App.jsx:312`) — chỉ hiện khi `o.status !== 'RECEIVED'`

**Backend** — `ImportOrderService.approve()` (dòng 89-113):

```java
ImportOrder order = getOrder(id);
ensureEditable(order);                          // ← chặn duyệt trùng
List<ImportOrderDetail> details = detailRepository.findAllByImportOrderId(id);
if (details.isEmpty()) {
    throw new BadRequestException("Phiếu nhập phải có ít nhất một sản phẩm");
}

try {
    for (ImportOrderDetail detail : details) {
        Product product = productRepository.findById(detail.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product", "id", detail.getProductId()));
        int currentStock = product.getStockQuantity() == null ? 0 : product.getStockQuantity();
        product.setStockQuantity(currentStock + detail.getQuantity());   // ← CỘNG TỒN KHO
        productRepository.saveAndFlush(product);
    }
} catch (ObjectOptimisticLockingFailureException | OptimisticLockException exception) {
    throw new BadRequestException("Tồn kho vừa được cập nhật bởi giao dịch khác, vui lòng thử lại");
}

order.setStatus(RECEIVED_STATUS);                // ← RECEIVED
ImportOrder savedOrder = importOrderRepository.saveAndFlush(order);
```

**Ba lớp bảo vệ:**

| # | Cơ chế | Chặn trường hợp | Thông báo |
| --- | --- | --- | --- |
| 1 | `ensureEditable()` | Duyệt phiếu đã `RECEIVED`/`CANCELLED` → cộng tồn kho 2 lần | 400 "Phiếu nhập không ở trạng thái có thể thay đổi" |
| 2 | `details.isEmpty()` | Phiếu không có dòng nào | 400 "Phiếu nhập phải có ít nhất một sản phẩm" |
| 3 | `@Version` optimistic lock | 2 phiếu duyệt đồng thời cùng 1 sản phẩm | 400 "Tồn kho vừa được cập nhật bởi giao dịch khác, vui lòng thử lại" |

`ensureEditable()` (dòng 126-130):

```java
if (!DRAFT_STATUS.equals(order.getStatus()) && !CONFIRMED_STATUS.equals(order.getStatus())) {
    throw new BadRequestException("Phiếu nhập không ở trạng thái có thể thay đổi");
}
```

Method có `@Transactional` → nếu một dòng trong vòng lặp lỗi, **toàn bộ rollback**, không cộng dở.

### 3.5 Luồng 3 — Sửa phiếu

**Endpoint**: `PUT /api/import-orders/{id}` → `ImportOrderService.update()` (dòng 65-78)

```java
requireSupplier(request.getSupplierId());
ImportOrder order = getOrder(id);
ensureEditable(order);                       // chỉ DRAFT/CONFIRMED
order.setSupplierId(request.getSupplierId());
order.setImportDate(request.getImportDate());
order.setTotalAmount(calculateTotal(request.getDetails()));
order.setUpdatedBy(request.getStaffId());
ImportOrder savedOrder = importOrderRepository.saveAndFlush(order);
detailRepository.deleteAllByImportOrderId(id);   // ← XOÁ TOÀN BỘ detail cũ
saveDetails(savedOrder.getId(), request.getDetails());   // ← tạo lại từ đầu
```

Hệ quả: `details` bị **thay thế toàn bộ**, không phải cập nhật từng dòng. `import_order_details` có FK `ON DELETE CASCADE` nên xoá cha cũng xoá con — nhưng ở đây xoá con trước rồi tạo lại.

### 3.6 Luồng 4 — Hủy phiếu

**Endpoint**: `DELETE /api/import-orders/{id}` → `ImportOrderService.delete()` (dòng 80-86)

```java
ImportOrder order = getOrder(id);
ensureEditable(order);
order.setStatus("CANCELLED");
importOrderRepository.save(order);
```

**Không xoá hẳn** — chuyển status sang `CANCELLED` (soft delete).

Phiếu đã `RECEIVED` **không** hủy được (vì `ensureEditable` chặn). Đây là hành vi đúng: tồn kho đã cộng, cần cơ chế trả hàng riêng.

### 3.7 Luồng 5 — Xem danh sách

**Endpoint**: `GET /api/import-orders` → `findAllByDeletedAtIsNullOrderByImportDateDesc()`

- Bỏ phiếu đã soft-delete
- Sắp xếp ngày nhập **mới nhất lên đầu**
- **Không phân trang** — trả về toàn bộ danh sách

**Endpoint**: `GET /api/import-orders/{id}` → chi tiết 1 phiếu kèm `details`

Response shape (`ImportOrderResponse`):

```json
{
  "id": "uuid", "supplierId": "uuid", "status": "DRAFT",
  "totalAmount": 500000.00, "importDate": "2026-10-29",
  "createdBy": null, "updatedBy": null,
  "details": [{ "id": "uuid", "importOrderId": "uuid", "productId": "uuid",
                "quantity": 10, "importPrice": 50000.00 }]
}
```

Lưu ý: `details` trả về entity `ImportOrderDetail` thô, **không có** `productName` hay `productUnit`. UI không hiển thị tên sản phẩm trong bảng — chỉ có mã phiếu, ngày, tổng tiền, trạng thái.

---

## 4. Mối liên hệ với POS

Nguồn: `backend/src/main/java/com/bcm/service/PosService.java:40-71`

```java
for (Map.Entry<UUID, Integer> entry : quantitiesByProduct.entrySet()) {
    Product product = productRepository.findById(entry.getKey())
            .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm"));
    int quantity = entry.getValue();

    // Only GOODS products have physical inventory; SERVICE products are non-stocked
    if (product.getType() == ProductType.GOODS) {
        if (product.getStockQuantity() < quantity) {
            throw new BadRequestException(INSUFFICIENT_STOCK_MESSAGE);
        }
        product.setStockQuantity(product.getStockQuantity() - quantity);   // ← TRỪ TỒN KHO
        productRepository.saveAndFlush(product);
    }

    BigDecimal lineAmount = product.getPrice().multiply(BigDecimal.valueOf(quantity));
    productFee = productFee.add(lineAmount);
    invoiceDetails.add(InvoiceDetail.builder()
            .productId(product.getId())
            .quantity(quantity)
            .unitPrice(product.getPrice())
            .build());
}
```

### So sánh hai chiều

| | Nhập kho (`approve`) | POS (`sell`) |
| --- | --- | --- |
| Dấu | `+` (cộng) | `-` (trừ) |
| Kiểm tra trước | Không (chỉ cộng) | Có — thiếu tồn → 400 |
| Điều kiện type | Mọi product trong phiếu | Chỉ `type == GOODS` |
| Lock | `@Version` optimistic | `@Version` optimistic |
| Bắt lỗi lock | → 400 "Tồn kho vừa được cập nhật..." | → 400 `INSUFFICIENT_STOCK_MESSAGE` |
| Transaction | `@Transactional` | `@Transactional` |
| Ràng buộc DB | `stock_quantity >= 0` | `stock_quantity >= 0` |

**Sản phẩm `SERVICE`** (dịch vụ): không có tồn kho, không bị trừ khi bán, không được đưa vào phiếu nhập (UI lọc `type === 'GOODS'`).

---

## 5. Phân quyền

| Controller | Endpoint | Quyền |
| --- | --- | --- |
| `SupplierController` | `/api/suppliers/**` | `@PreAuthorize("hasRole('ADMIN')")` |
| `ImportOrderController` | `/api/import-orders/**` | `@PreAuthorize("hasRole('ADMIN')")` |
| `PosController` | `/api/pos/**` | `@PreAuthorize("hasRole('ADMIN')")` |

STAFF không truy cập được phần nhập kho.

---

## 6. Bảng API

### Nhà cung cấp

| Method | Path | Body | Trả về |
| --- | --- | --- | --- |
| GET | `/api/suppliers` | — | `Supplier[]` |
| GET | `/api/suppliers/{id}` | — | `Supplier` |
| POST | `/api/suppliers` | `{name, phone, address}` | 201 + `Supplier` |
| PUT | `/api/suppliers/{id}` | `{name, phone, address}` | 200 + `Supplier` |
| DELETE | `/api/suppliers/{id}` | — | 200 (soft delete) |

### Phiếu nhập

| Method | Path | Body | Trả về |
| --- | --- | --- | --- |
| GET | `/api/import-orders` | — | `ImportOrderResponse[]` |
| GET | `/api/import-orders/{id}` | — | `ImportOrderResponse` |
| POST | `/api/import-orders` | `{supplierId, importDate, staffId?, details[]}` | 201 + `ImportOrderResponse` |
| PUT | `/api/import-orders/{id}` | `{supplierId, importDate, staffId?, details[]}` | 200 + `ImportOrderResponse` |
| DELETE | `/api/import-orders/{id}` | — | 200 (→ CANCELLED) |
| POST | `/api/import-orders/{id}/approve` | — | 200 + `ImportOrderResponse` (→ RECEIVED, cộng tồn kho) |

---

## 7. Danh sách lỗi cần sửa

Xếp theo mức độ ảnh hưởng demo.

### LỖI 1 — Không có màn hình quản lý nhà cung cấp (BLOCKER)

| | |
| --- | --- |
| **Mức độ** | 🔴 Cao — chặn toàn bộ demo luồng nhập kho |
| **File** | `web-admin/src/App.jsx` |
| **Hiện trạng** | Backend có đủ CRUD `/api/suppliers`. UI chỉ gọi `GET /api/suppliers` (dòng 71) để đổ dropdown, và đếm ở thẻ thống kê (dòng 302). Không có nơi tạo/sửa/xoá. |
| **Hệ quả** | Database **không có seed cho `suppliers`** (grep `db/*.sql` không có `INSERT INTO suppliers`). Dropdown nhà cung cấp luôn rỗng → không lập được phiếu nhập → không demo được luồng kho. |
| **Cách sửa** | Thêm tab/modal CRUD nhà cung cấp trong web-admin: GET danh sách, form thêm/sửa (name, phone, address), nút xoá. **Hoặc** thêm seed `INSERT INTO suppliers` vào `db/migration.sql` / file seed riêng. |
| **Ưu tiên** | Cao nhất — làm trước khi demo |

### LỖI 2 — Phiếu `CANCELLED` hiển thị sai và vẫn có nút "Duyệt"

| | |
| --- | --- |
| **Mức độ** | 🔴 Cao — hiển thị sai trạng thái trên UI |
| **File** | `web-admin/src/App.jsx:311-312` |
| **Hiện trạng** | Badge: `o.status === 'RECEIVED' ? 'Đã nhập kho' : 'Chờ duyệt'` → phiếu `CANCELLED` hiện là **"Chờ duyệt"** (sai). Nút duyệt: `o.status !== 'RECEIVED' && <button>✓ Duyệt nhập kho</button>` → phiếu `CANCELLED` **vẫn hiện nút duyệt**. Bấm vào → backend trả 400 "Phiếu nhập không ở trạng thái có thể thay đổi". |
| **Cách sửa** | Tách 3 nhóm hiển thị: `RECEIVED` → "Đã nhập kho" (badge AVAILABLE); `CANCELLED` → "Đã hủy" (badge MAINTENANCE, không có nút duyệt); còn lại → "Chờ duyệt" (badge MAINTENANCE, có nút duyệt). Điều kiện nút duyệt nên là `['DRAFT','CONFIRMED'].includes(o.status)`. |
| **Ghi chú** | Hiện **không có UI nào hủy phiếu** (chưa gọi `DELETE /api/import-orders/{id}`), nên lỗi này chỉ lộ ra khi hủy qua API/Swagger. Nhưng nên sửa để đúng. |

### LỖI 3 — Thẻ "Phiếu chờ duyệt" đếm sai

| | |
| --- | --- |
| **Mức độ** | 🟡 Trung bình — số liệu sai trên dashboard |
| **File** | `web-admin/src/App.jsx:300` |
| **Hiện trạng** | `importOrders.filter(o => o.status !== 'RECEIVED').length` → tính cả `CANCELLED` vào "Phiếu chờ duyệt / Cần xác nhận". Phiếu đã hủy không cần xác nhận. |
| **Cách sửa** | `importOrders.filter(o => ['DRAFT','CONFIRMED'].includes(o.status)).length` |

### LỖI 4 — Modal chỉ hỗ trợ 1 sản phẩm

| | |
| --- | --- |
| **Mức độ** | 🟡 Trung bình — giới hạn nghiệp vụ, backend đã hỗ trợ |
| **File** | `web-admin/src/App.jsx:167` |
| **Hiện trạng** | `details: [{...}]` — hard-code **một** phần tử. Backend `ImportOrderRequest.details` là `List<ImportOrderDetailRequest>` (`@NotEmpty`) → đa dòng hoàn toàn được hỗ trợ. |
| **Hệ quả** | Phiếu nhập nhiều loại hàng phải tách thành nhiều phiếu → nhiều lần bấm "Duyệt", dễ sai lệch tồn kho. |
| **Cách sửa** | Đổi form thành bảng nhiều dòng: thêm/xoá dòng (product, quantity, importPrice), gửi `details` là mảng. Cần cập nhật state `importForm` từ object → `{supplierId, importDate, details: [...]}`. |
| **Ưu tiên** | Nếu thời gian demo ngắn → giữ nguyên, nhưng **nói rõ trong báo cáo** rằng giới hạn ở tầng UI |

### LỖI 5 — `staffId` không lấy từ token → `created_by`/`updated_by` luôn NULL

| | |
| --- | --- |
| **Mức độ** | 🟡 Trung bình — mất khả năng truy vết ai lập phiếu |
| **File** | `backend/.../ImportOrderRequest.java:25`, `ImportOrderService.java:57-58,73` |
| **Hiện trạng** | `private UUID staffId;` — **optional**, không có `@NotNull`. UI không gửi field này (`App.jsx:164-168` không có `staffId`). → `created_by` và `updated_by` luôn NULL. |
| **Hệ quả** | Không biết phiếu nhập do ai lập. Cột FK `created_by → staffs(id)` không bao giờ được dùng. |
| **Cách sửa** | Bỏ `staffId` khỏi request body, lấy từ `SecurityContextHolder.getContext().getAuthentication().getPrincipal()` trong service. Nếu muốn giữ body thì thêm `@NotNull` + validate user tồn tại. |

### LỖI 6 — Không có cơ chế trả hàng (return)

| | |
| --- | --- |
| **Mức độ** | 🟡 Trung bình — giới hạn nghiệp vụ |
| **Hiện trạng** | `import_order_details.quantity` có `CHECK (quantity > 0)` và DTO có `@Positive` → không thể tạo phiếu nhập âm để trừ tồn kho. Phiếu `RECEIVED` không hủy được (`ensureEditable` chặn). |
| **Hệ quả** | Nếu nhập nhầm số lượng, không có cách điều chỉnh tồn kho ngoài việc sửa trực tiếp trong DB. |
| **Cách sửa** | Thêm endpoint `POST /api/import-orders/{id}/return` tạo phiếu điều chỉnh âm, hoặc cho phép `approve` với `quantity` âm qua một trường riêng (returnOrder) chứ không mở ràng buộc `> 0`. |
| **Ưu tiên** | Thấp — nhiều đồ án không cần |

### LỖI 7 — `CONFIRMED` là trạng thái chết

| | |
| --- | --- |
| **Mức độ** | 🟢 Thấp — trạng thái thừa |
| **File** | `ImportOrderService.java:31`, `ensureEditable()` dòng 126-130 |
| **Hiện trạng** | Có hằng `CONFIRMED_STATUS`, `ensureEditable` cho phép, DB constraint cho phép. Nhưng **không có endpoint nào chuyển `DRAFT → CONFIRMED`**. Không bao giờ đạt được trạng thái này qua API. |
| **Cách sửa** | Hoặc thêm endpoint `POST /{id}/confirm` (xác nhận đơn hàng trước khi nhận hàng thật), hoặc bỏ `CONFIRMED` khỏi constant và khỏi `ensureEditable`. |

### LỖI 8 — Sửa được cả phiếu `CONFIRMED` mà không cần duyệt lại

| | |
| --- | --- |
| **Mức độ** | 🟢 Thấp |
| **File** | `ImportOrderService.java:126-130` |
| **Hiện trạng** | `ensureEditable` cho phép sửa cả `DRAFT` và `CONFIRMED`. Phiếu đã "xác nhận" vẫn sửa được thoải mái. |
| **Cách sửa** | Nếu giữ `CONFIRMED` → chỉ cho sửa khi `DRAFT`; `CONFIRMED` chỉ được duyệt. Đồng thời với LỖI 7. |

### LỖI 9 — Bảng phiếu nhập không hiển thị tên sản phẩm

| | |
| --- | --- |
| **Mức độ** | 🟢 Thấp — thiếu thông tin khi demo |
| **File** | `web-admin/src/App.jsx:306-313`, `ImportOrderResponse.java` |
| **Hiện trạng** | `details` trả entity `ImportOrderDetail` thô — không có `productName`, `productUnit`, `supplierName`. Bảng chỉ có: Mã phiếu, Ngày nhập, Tổng tiền, Trạng thái, Thao tác. Không biết phiếu đó nhập món gì, từ NCC nào. |
| **Cách sửa** | Enrich response: join `product_name`, `product_unit`, `supplier_name`. Hoặc thêm cột "Sản phẩm" và "Nhà cung cấp" ở UI, join từ `data.products` / `suppliers` đã có sẵn trong state. |

### LỖI 10 — Không phân trang danh sách phiếu nhập

| | |
| --- | --- |
| **Mức độ** | 🟢 Thấp |
| **File** | `ImportOrderService.findAll()` dòng 39-43 |
| **Hiện trạng** | `findAllByDeletedAtIsNullOrderByImportDateDesc()` — trả toàn bộ. |
| **Cách sửa** | Thêm `page`/`size` params, trả `Page<ImportOrderResponse>`; UI thêm phân trang. Theo đúng pattern đang dùng ở `BookingHistoryService.history()`: service gọi `PageRequest.of(page, size)`, controller nhận `@RequestParam(defaultValue = "0") int page` và `@RequestParam(defaultValue = "10") int size`. |

### LỖI 11 — Không có nút hủy / sửa phiếu trên UI

| | |
| --- | --- |
| **Mức độ** | 🟢 Thấp — thiếu chức năng |
| **Hiện trạng** | Bảng chỉ có nút "✓ Duyệt nhập kho". Backend có `PUT` (sửa) và `DELETE` (hủy) nhưng UI không gọi. |
| **Cách sửa** | Thêm nút "Sửa" (mở modal prefilled) và "Hủy" cho phiếu `DRAFT`/`CONFIRMED`. |

---

## 8. Câu hỏi thường gặp khi demo

**Q: Tại sao dropdown "Nhà cung cấp" luôn rỗng?**
A: Vì DB không có dữ liệu `suppliers` và UI không có màn hình tạo NCC. Sửa LỖI 1 trước — hoặc tạo nhanh qua `POST /api/suppliers` trên Swagger.

**Q: Bấm "Duyệt nhập kho" mà báo lỗi 400?**
A: Ba khả năng — (1) phiếu đã `RECEIVED` (duyệt 2 lần), (2) phiếu `CANCELLED`, (3) phiếu không có dòng nào. Xem message trả về để phân biệt.

**Q: Tồn kho không tăng sau khi duyệt?**
A: Kiểm tra: (1) sản phẩm có `type = 'GOODS'` không — `SERVICE` không có tồn kho, (2) phiếu có thực sự chuyển sang `RECEIVED` không, (3) refresh trang — UI `loadOperationalData()` + `load()` đã gọi lại sau khi duyệt.

**Q: Số "Phiếu chờ duyệt" không khớp với số dòng trong bảng?**
A: Đúng — thẻ đếm cả `CANCELLED`, bảng hiển thị từng dòng. Xem LỖI 3.

**Q: Có thể nhập hàng nhiều loại trong 1 phiếu?**
A: Được về mặt backend, nhưng UI chỉ tạo 1 dòng. Xem LỖI 4.

**Q: Giá trong `total_amount` lấy từ đâu?**
A: Backend tự tính `Σ(import_price × quantity)` (`ImportOrderService.calculateTotal()`), không tin giá trị client gửi lên. Đơn giá nhập là snapshot tại thời điểm lập phiếu, không tự cập nhật khi giá sản phẩm đổi.

**Q: Nhập kho có khác gì với bán hàng ở POS?**
A: Cùng cột `products.stock_quantity`. Duyệt phiếu nhập → **cộng**. Bán tại quầy → **trừ** (chỉ với `type = 'GOODS'`). Cả hai dùng optimistic lock qua `@Version`.

---

## Phụ lục — File tham chiếu

| File | Vai trò |
| --- | --- |
| `backend/src/main/java/com/bcm/entity/Supplier.java` | Entity nhà cung cấp |
| `backend/src/main/java/com/bcm/entity/ImportOrder.java` | Entity phiếu nhập |
| `backend/src/main/java/com/bcm/entity/ImportOrderDetail.java` | Entity dòng chi tiết |
| `backend/src/main/java/com/bcm/entity/Product.java` | Entity sản phẩm (có `@Version`) |
| `backend/src/main/java/com/bcm/service/SupplierService.java` | Logic NCC |
| `backend/src/main/java/com/bcm/service/ImportOrderService.java` | Logic phiếu nhập (trọng tâm) |
| `backend/src/main/java/com/bcm/service/PosService.java` | Trừ tồn kho khi bán |
| `backend/src/main/java/com/bcm/controller/SupplierController.java` | API NCC |
| `backend/src/main/java/com/bcm/controller/ImportOrderController.java` | API phiếu nhập |
| `backend/src/main/java/com/bcm/dto/request/ImportOrderRequest.java` | Validate đầu vào |
| `backend/src/main/java/com/bcm/dto/request/ImportOrderDetailRequest.java` | Validate dòng chi tiết |
| `backend/src/main/java/com/bcm/dto/request/SupplierRequest.java` | Validate NCC |
| `backend/src/main/java/com/bcm/dto/response/ImportOrderResponse.java` | Shape response |
| `web-admin/src/App.jsx` | UI tab "Nhập kho" (dòng 70-74, 160-183, 295-323) |
| `db/migration.sql` | Schema chuẩn: `suppliers`, `import_orders`, `import_order_details`, `products` |
