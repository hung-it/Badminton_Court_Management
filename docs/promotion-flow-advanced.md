# Promotion System (Advanced) - 3 Loại Discount Rules

Hệ thống khuyến mãi nâng cao với 3 tầng: **Promotions** → **Discount Rules** → **Customer Voucher Usage**.

## 📊 Kiến Trúc Tổng Quan

```
┌─────────────────────────────────────────────────────────────┐
│                        PROMOTIONS                            │
│  (Master: code, name, valid_from/to, is_active)             │
└────────────────────┬────────────────────────────────────────┘
                     │ 1:N
                     ▼
┌─────────────────────────────────────────────────────────────┐
│                     DISCOUNT_RULES                           │
│  (3 loại: PRODUCT / INVOICE_TOTAL / VOUCHER)                │
└────────────────────┬────────────────────────────────────────┘
                     │ 1:N (chỉ với VOUCHER)
                     ▼
┌─────────────────────────────────────────────────────────────┐
│                CUSTOMER_VOUCHER_USAGE                        │
│  (Tracking: customer_id, rule_id, used_at)                  │
└─────────────────────────────────────────────────────────────┘
```

---

## 1. Database Schema

### 1.1. Bảng `promotions`

```sql
CREATE TABLE promotions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) UNIQUE NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    valid_from TIMESTAMP NOT NULL,
    valid_to TIMESTAMP NOT NULL,
    is_active BOOLEAN DEFAULT true,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    
    CONSTRAINT chk_valid_dates CHECK (valid_from <= valid_to)
);

CREATE INDEX idx_promotions_code_active 
ON promotions(code) 
WHERE is_active = true AND deleted_at IS NULL;

CREATE INDEX idx_promotions_validity 
ON promotions(valid_from, valid_to) 
WHERE is_active = true AND deleted_at IS NULL;
```

**Mô tả:**
- **Master table**: Chứa thông tin chung của chương trình khuyến mãi
- **code**: Mã KM (SUMMER2024, VIP20, NEWUSER)
- **valid_from/to**: Thời gian hiệu lực
- **is_active**: Bật/tắt mà không cần xóa

---

### 1.2. Bảng `discount_rules`

```sql
CREATE TABLE discount_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    promotion_id UUID NOT NULL REFERENCES promotions(id) ON DELETE CASCADE,
    rule_type VARCHAR(20) NOT NULL CHECK (rule_type IN ('PRODUCT', 'INVOICE_TOTAL', 'VOUCHER')),
    
    -- Cho PRODUCT rule
    target_product_id UUID REFERENCES products(id),
    
    -- Cho INVOICE_TOTAL & VOUCHER
    min_invoice_amount NUMERIC(15,2) DEFAULT 0,
    
    -- Cho VOUCHER
    voucher_code VARCHAR(50),
    max_usage_per_customer INT DEFAULT 1,
    
    -- Giảm giá chung
    discount_type VARCHAR(20) NOT NULL CHECK (discount_type IN ('PERCENT', 'AMOUNT')),
    discount_value NUMERIC(15,2) NOT NULL,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Constraints
    CONSTRAINT chk_product_rule CHECK (
        (rule_type = 'PRODUCT' AND target_product_id IS NOT NULL)
        OR (rule_type != 'PRODUCT' AND target_product_id IS NULL)
    ),
    CONSTRAINT chk_voucher_rule CHECK (
        (rule_type = 'VOUCHER' AND voucher_code IS NOT NULL)
        OR (rule_type != 'VOUCHER' AND voucher_code IS NULL)
    ),
    CONSTRAINT chk_discount_positive CHECK (discount_value > 0),
    CONSTRAINT chk_min_amount_positive CHECK (min_invoice_amount >= 0),
    CONSTRAINT chk_max_usage_positive CHECK (max_usage_per_customer > 0)
);

CREATE INDEX idx_discount_rules_promotion ON discount_rules(promotion_id);
CREATE INDEX idx_discount_rules_product ON discount_rules(target_product_id) WHERE rule_type = 'PRODUCT';
CREATE INDEX idx_discount_rules_voucher ON discount_rules(voucher_code) WHERE rule_type = 'VOUCHER' AND voucher_code IS NOT NULL;
```

**Mô tả:**
- **Chi tiết rules**: 1 promotion có thể có nhiều rules
- **rule_type**: 3 loại giảm giá khác nhau (xem chi tiết bên dưới)

---

### 1.3. Bảng `customer_voucher_usage`

```sql
CREATE TABLE customer_voucher_usage (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id UUID NOT NULL REFERENCES customers(id),
    discount_rule_id UUID NOT NULL REFERENCES discount_rules(id),
    invoice_id UUID NOT NULL REFERENCES invoices(id),
    used_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    UNIQUE(customer_id, discount_rule_id, invoice_id)
);

CREATE INDEX idx_voucher_usage_customer ON customer_voucher_usage(customer_id);
CREATE INDEX idx_voucher_usage_rule ON customer_voucher_usage(discount_rule_id);
```

**Mô tả:**
- **Tracking voucher**: Đảm bảo khách không dùng quá `max_usage_per_customer`
- **Unique constraint**: 1 khách không thể dùng cùng 1 voucher cho cùng 1 invoice nhiều lần

---

### 1.4. Cập Nhật Bảng `invoices`

```sql
ALTER TABLE invoices
ADD COLUMN promotion_id UUID REFERENCES promotions(id),
ADD COLUMN discount_amount NUMERIC(15,2) DEFAULT 0;

-- Discount không vượt quá subtotal
ALTER TABLE invoices
ADD CONSTRAINT chk_discount_not_exceed
CHECK (discount_amount <= COALESCE(court_fee, 0) + COALESCE(product_fee, 0));

-- Discount phải >= 0
ALTER TABLE invoices
ADD CONSTRAINT chk_discount_positive
CHECK (discount_amount >= 0);

-- Index
CREATE INDEX idx_invoices_promotion ON invoices(promotion_id) WHERE promotion_id IS NOT NULL;
```

**Công thức tính total_amount:**
```sql
total_amount = court_fee + product_fee - discount_amount
```

---

## 2. Ba Loại Discount Rules

### 2.1. PRODUCT - Giảm Giá Theo Sản Phẩm

**Use Case:** Giảm 15% cho sản phẩm Nước Suối

**Discount Rule:**
```sql
INSERT INTO discount_rules (
    promotion_id, 
    rule_type, 
    target_product_id,
    discount_type, 
    discount_value
) VALUES (
    'promo-summer-id',
    'PRODUCT',
    'product-nuoc-suoi-id',
    'PERCENT',
    15
);
```

**Logic Áp Dụng:**
```java
// Khi thu ngân nhập mã SUMMER2024
List<DiscountRule> rules = discountRuleRepository
    .findByPromotionIdAndRuleType(promotionId, "PRODUCT");

BigDecimal totalDiscount = BigDecimal.ZERO;

for (DiscountRule rule : rules) {
    // Tìm trong invoice_details
    InvoiceDetail item = invoice.getDetails().stream()
        .filter(d -> d.getProductId().equals(rule.getTargetProductId()))
        .findFirst()
        .orElse(null);
    
    if (item != null) {
        BigDecimal itemTotal = item.getUnitPrice().multiply(item.getQuantity());
        BigDecimal itemDiscount;
        
        if (rule.getDiscountType() == DiscountType.PERCENT) {
            itemDiscount = itemTotal.multiply(rule.getDiscountValue())
                .divide(BigDecimal.valueOf(100));
        } else {
            itemDiscount = rule.getDiscountValue()
                .multiply(item.getQuantity());
        }
        
        totalDiscount = totalDiscount.add(itemDiscount);
    }
}

invoice.setDiscountAmount(totalDiscount);
```

**Ví dụ:**
```
Invoice:
- Nước Suối (10k × 3) = 30k
- Cầu Yonex (100k × 1) = 100k
Subtotal = 130k

Áp dụng SUMMER2024 (Giảm 15% Nước Suối):
- Giảm: 30k × 15% = 4.5k
Total = 130k - 4.5k = 125.5k
```

---

### 2.2. INVOICE_TOTAL - Giảm Giá Theo Tổng Hóa Đơn

**Use Case:** Hóa đơn >= 500k giảm 50k

**Discount Rule:**
```sql
INSERT INTO discount_rules (
    promotion_id, 
    rule_type, 
    min_invoice_amount,
    discount_type, 
    discount_value
) VALUES (
    'promo-vip-id',
    'INVOICE_TOTAL',
    500000,
    'AMOUNT',
    50000
);
```

**Logic Áp Dụng:**
```java
List<DiscountRule> rules = discountRuleRepository
    .findByPromotionIdAndRuleType(promotionId, "INVOICE_TOTAL");

BigDecimal subtotal = invoice.getCourtFee()
    .add(invoice.getProductFee());

for (DiscountRule rule : rules) {
    if (subtotal.compareTo(rule.getMinInvoiceAmount()) >= 0) {
        BigDecimal discount;
        
        if (rule.getDiscountType() == DiscountType.PERCENT) {
            discount = subtotal.multiply(rule.getDiscountValue())
                .divide(BigDecimal.valueOf(100));
        } else {
            discount = rule.getDiscountValue();
        }
        
        invoice.setDiscountAmount(discount);
        break; // Chỉ áp 1 rule INVOICE_TOTAL
    }
}
```

**Ví dụ:**
```
Invoice:
- Court fee: 400k
- Product fee: 150k
Subtotal = 550k

Áp dụng VIP50K (Hóa đơn >= 500k giảm 50k):
- Giảm: 50k
Total = 550k - 50k = 500k
```

---

### 2.3. VOUCHER - Mã Voucher Giới Hạn Số Lần Dùng

**Use Case:** Mã VIP20 giảm 20%, mỗi khách dùng tối đa 1 lần

**Discount Rule:**
```sql
INSERT INTO discount_rules (
    promotion_id, 
    rule_type, 
    voucher_code,
    max_usage_per_customer,
    min_invoice_amount,
    discount_type, 
    discount_value
) VALUES (
    'promo-vip-id',
    'VOUCHER',
    'VIP20',
    1,
    200000,
    'PERCENT',
    20
);
```

**Logic Áp Dụng:**
```java
// Bước 1: Kiểm tra khách đã dùng voucher chưa
DiscountRule voucherRule = discountRuleRepository
    .findByVoucherCode(voucherCode)
    .orElseThrow(() -> new NotFoundException("Voucher không tồn tại"));

int usageCount = customerVoucherUsageRepository
    .countByCustomerIdAndDiscountRuleId(customerId, voucherRule.getId());

if (usageCount >= voucherRule.getMaxUsagePerCustomer()) {
    throw new BusinessException("Bạn đã sử dụng voucher này");
}

// Bước 2: Kiểm tra min_invoice_amount
BigDecimal subtotal = invoice.getCourtFee().add(invoice.getProductFee());

if (subtotal.compareTo(voucherRule.getMinInvoiceAmount()) < 0) {
    throw new BusinessException(
        "Hóa đơn tối thiểu " + voucherRule.getMinInvoiceAmount()
    );
}

// Bước 3: Tính discount
BigDecimal discount;
if (voucherRule.getDiscountType() == DiscountType.PERCENT) {
    discount = subtotal.multiply(voucherRule.getDiscountValue())
        .divide(BigDecimal.valueOf(100));
} else {
    discount = voucherRule.getDiscountValue();
}

invoice.setDiscountAmount(discount);

// Bước 4: Tracking usage
CustomerVoucherUsage usage = new CustomerVoucherUsage();
usage.setCustomerId(customerId);
usage.setDiscountRuleId(voucherRule.getId());
usage.setInvoiceId(invoice.getId());
customerVoucherUsageRepository.save(usage);
```

**Ví dụ:**
```
Khách hàng: Nguyễn Văn A
Voucher: VIP20 (Giảm 20%, hóa đơn >= 200k, mỗi người dùng 1 lần)

Lần 1:
- Subtotal: 300k
- Giảm: 300k × 20% = 60k
- Total: 240k
→ ✅ Thành công, tracking vào customer_voucher_usage

Lần 2:
- Subtotal: 500k
→ ❌ Lỗi: "Bạn đã sử dụng voucher này"
```

---

## 3. Luồng Áp Dụng Khuyến Mãi (Full Flow)

### 3.1. Sequence Diagram

```
Customer         POS Staff        System                   Database
   │                │                │                          │
   │   Mua hàng     │                │                          │
   ├───────────────>│                │                          │
   │                │  Tạo invoice   │                          │
   │                │  (DRAFT)       │                          │
   │                ├───────────────>│   INSERT invoices        │
   │                │                ├─────────────────────────>│
   │                │                │                          │
   │  Nhập mã KM    │                │                          │
   │  "SUMMER2024"  │                │                          │
   ├───────────────>│                │                          │
   │                │  Validate KM   │                          │
   │                ├───────────────>│   SELECT promotions      │
   │                │                │   WHERE code = ?         │
   │                │                │   AND is_active = true   │
   │                │                │   AND NOW() BETWEEN      │
   │                │                │   valid_from AND valid_to│
   │                │                ├─────────────────────────>│
   │                │                │<─────────────────────────┤
   │                │                │   Promotion found        │
   │                │                │                          │
   │                │  Load rules    │                          │
   │                │                │   SELECT discount_rules  │
   │                │                │   WHERE promotion_id = ? │
   │                │                ├─────────────────────────>│
   │                │                │<─────────────────────────┤
   │                │                │   Rules: PRODUCT,        │
   │                │                │   INVOICE_TOTAL, VOUCHER │
   │                │                │                          │
   │                │  Tính discount │                          │
   │                │                │   1. PRODUCT rules       │
   │                │                │   2. INVOICE_TOTAL rules │
   │                │                │   3. VOUCHER rules       │
   │                │                │      (check usage)       │
   │                │                │                          │
   │                │                │   UPDATE invoices        │
   │                │                │   SET discount_amount=?, │
   │                │                │       promotion_id=?     │
   │                │                ├─────────────────────────>│
   │                │                │                          │
   │                │                │   INSERT                 │
   │                │                │   customer_voucher_usage │
   │                │                │   (nếu là VOUCHER)       │
   │                │                ├─────────────────────────>│
   │                │                │                          │
   │                │<───────────────┤                          │
   │<───────────────┤  Hiển thị giảm giá                       │
   │  "Giảm 60k"    │                │                          │
   │                │                │                          │
   │   Thanh toán   │                │                          │
   ├───────────────>│  Chuyển PAID   │                          │
   │                ├───────────────>│   UPDATE invoices        │
   │                │                │   SET status = 'PAID'    │
   │                │                ├─────────────────────────>│
   │<───────────────┤<───────────────┤                          │
   │   In hóa đơn   │                │                          │
```

---

### 3.2. Validation Rules

```java
public class PromotionValidator {
    
    public void validate(Promotion promotion, Invoice invoice, Customer customer) {
        // 1. Kiểm tra promotion active
        if (!promotion.getIsActive()) {
            throw new BusinessException("Khuyến mãi đã bị vô hiệu hóa");
        }
        
        // 2. Kiểm tra thời gian hiệu lực
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(promotion.getValidFrom()) || now.isAfter(promotion.getValidTo())) {
            throw new BusinessException("Khuyến mãi đã hết hạn");
        }
        
        // 3. Kiểm tra soft delete
        if (promotion.getDeletedAt() != null) {
            throw new BusinessException("Khuyến mãi không tồn tại");
        }
        
        // 4. Kiểm tra invoice chưa áp dụng KM nào
        if (invoice.getPromotionId() != null) {
            throw new BusinessException("Hóa đơn đã áp dụng khuyến mãi khác");
        }
        
        // 5. Kiểm tra invoice status
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            throw new BusinessException("Chỉ áp dụng KM cho hóa đơn đang soạn thảo");
        }
    }
}
```

---

## 4. Ví Dụ Thực Tế

### Scenario 1: Khách Check-in + Mua Hàng + Áp KM

**Setup:**
```sql
-- Promotion: SUMMER2024
INSERT INTO promotions (id, code, name, valid_from, valid_to, is_active)
VALUES (
    'promo-summer', 
    'SUMMER2024', 
    'Khuyến mãi mùa hè', 
    '2024-06-01', 
    '2024-08-31', 
    true
);

-- Rule 1: Giảm 15% cho Nước Suối
INSERT INTO discount_rules (promotion_id, rule_type, target_product_id, discount_type, discount_value)
VALUES ('promo-summer', 'PRODUCT', 'prod-nuoc-suoi', 'PERCENT', 15);

-- Rule 2: Hóa đơn >= 500k giảm 50k
INSERT INTO discount_rules (promotion_id, rule_type, min_invoice_amount, discount_type, discount_value)
VALUES ('promo-summer', 'INVOICE_TOTAL', 500000, 'AMOUNT', 50000);
```

**Timeline:**
```
15:30 - Khách đặt sân online, thanh toán 200k (court_fee)
18:00 - Khách check-in → Tạo invoice (DRAFT):
        - court_fee: 200k (đã thanh toán)
        - product_fee: 0 (chưa mua gì)
        
18:15 - Khách mua:
        - Nước Suối: 10k × 5 = 50k
        - Cầu Yonex: 100k × 3 = 300k
        → product_fee: 350k
        → Subtotal: 200k + 350k = 550k
        
18:20 - Thu ngân nhập mã "SUMMER2024"
        System tính:
        - Rule PRODUCT: Giảm 15% Nước Suối = 50k × 15% = 7.5k
        - Rule INVOICE_TOTAL: Subtotal 550k >= 500k → Giảm 50k
        → Total discount: 7.5k + 50k = 57.5k
        
        Invoice final:
        - court_fee: 200k
        - product_fee: 350k
        - discount_amount: 57.5k
        - total_amount: 550k - 57.5k = 492.5k
        
18:25 - Khách thanh toán 292.5k (đã trả 200k trước)
        Invoice chuyển PAID
```

---

### Scenario 2: Khách Vãng Lai + Voucher

**Setup:**
```sql
-- Promotion: VIPCODE
INSERT INTO promotions (id, code, name, valid_from, valid_to, is_active)
VALUES (
    'promo-vip', 
    'VIPCODE', 
    'Ưu đãi khách VIP', 
    '2024-01-01', 
    '2024-12-31', 
    true
);

-- Rule: Voucher VIP20 (Giảm 20%, hóa đơn >= 300k, mỗi người dùng 1 lần)
INSERT INTO discount_rules (
    promotion_id, 
    rule_type, 
    voucher_code, 
    max_usage_per_customer, 
    min_invoice_amount, 
    discount_type, 
    discount_value
) VALUES (
    'promo-vip', 
    'VOUCHER', 
    'VIP20', 
    1, 
    300000, 
    'PERCENT', 
    20
);
```

**Timeline:**
```
10:00 - Khách vãng lai (Nguyễn Văn A) vào cửa hàng
        → Tạo invoice (DRAFT):
        - court_fee: 0 (không đặt sân)
        - product_fee: 0
        
10:05 - Khách mua:
        - Giày Yonex: 800k × 1
        → product_fee: 800k
        
10:10 - Khách nhập voucher "VIP20"
        System check:
        1. ✅ Promotion active & trong thời hạn
        2. ✅ Khách A chưa dùng VIP20 (query customer_voucher_usage)
        3. ✅ Subtotal 800k >= 300k (min_invoice_amount)
        
        System tính:
        - Giảm: 800k × 20% = 160k
        
        Invoice final:
        - court_fee: 0
        - product_fee: 800k
        - discount_amount: 160k
        - total_amount: 640k
        
10:15 - Khách thanh toán 640k
        → Invoice chuyển PAID
        → INSERT customer_voucher_usage (customer_id=A, rule_id=VIP20)
        
--- 1 tuần sau ---

10:00 - Khách A quay lại, mua Cầu: 500k
10:05 - Khách nhập voucher "VIP20" lần 2
        System check:
        1. ✅ Promotion active
        2. ❌ Khách A đã dùng VIP20 (count = 1 >= max_usage_per_customer)
        
        → Lỗi: "Bạn đã sử dụng voucher này"
```

---

## 5. Queries Hữu Ích

### 5.1. Tìm Promotions Đang Active

```sql
SELECT p.*, COUNT(dr.id) AS total_rules
FROM promotions p
LEFT JOIN discount_rules dr ON p.id = dr.promotion_id
WHERE p.is_active = true
  AND p.deleted_at IS NULL
  AND NOW() BETWEEN p.valid_from AND p.valid_to
GROUP BY p.id
ORDER BY p.created_at DESC;
```

### 5.2. Chi Tiết Promotion + Rules

```sql
SELECT 
    p.code AS promo_code,
    p.name AS promo_name,
    dr.rule_type,
    dr.discount_type,
    dr.discount_value,
    CASE 
        WHEN dr.rule_type = 'PRODUCT' THEN prod.name
        WHEN dr.rule_type = 'VOUCHER' THEN dr.voucher_code
        ELSE NULL
    END AS target
FROM promotions p
JOIN discount_rules dr ON p.id = dr.promotion_id
LEFT JOIN products prod ON dr.target_product_id = prod.id
WHERE p.code = 'SUMMER2024';
```

### 5.3. Kiểm Tra Khách Đã Dùng Voucher Chưa

```sql
SELECT COUNT(*) AS usage_count
FROM customer_voucher_usage cvu
JOIN discount_rules dr ON cvu.discount_rule_id = dr.id
WHERE cvu.customer_id = :customerId
  AND dr.voucher_code = :voucherCode;
```

### 5.4. Top Khách Hàng Dùng Khuyến Mãi Nhiều Nhất

```sql
SELECT 
    c.full_name,
    u.email,
    COUNT(DISTINCT i.id) AS total_invoices_with_promo,
    SUM(i.discount_amount) AS total_discount_received
FROM customers c
JOIN users u ON c.user_id = u.id
JOIN invoices i ON c.id = i.customer_id
WHERE i.promotion_id IS NOT NULL
  AND i.status = 'PAID'
GROUP BY c.id, c.full_name, u.email
ORDER BY total_discount_received DESC
LIMIT 10;
```

### 5.5. Báo Cáo Hiệu Quả Khuyến Mãi

```sql
SELECT 
    p.code,
    p.name,
    COUNT(i.id) AS times_used,
    SUM(i.discount_amount) AS total_discount_given,
    AVG(i.total_amount) AS avg_invoice_amount,
    MIN(i.created_at) AS first_used,
    MAX(i.created_at) AS last_used
FROM promotions p
LEFT JOIN invoices i ON p.id = i.promotion_id AND i.status = 'PAID'
WHERE p.valid_from >= '2024-06-01'
  AND p.valid_to <= '2024-08-31'
GROUP BY p.id, p.code, p.name
ORDER BY total_discount_given DESC;
```

---

## 6. Testing Checklist

### Test Cases Cần Cover:

**Promotion Validation:**
- [ ] Nhập mã không tồn tại → Lỗi
- [ ] Nhập mã đã bị xóa (soft delete) → Lỗi
- [ ] Nhập mã is_active = false → Lỗi
- [ ] Nhập mã quá hạn (NOW() > valid_to) → Lỗi
- [ ] Nhập mã chưa đến hạn (NOW() < valid_from) → Lỗi

**PRODUCT Rule:**
- [ ] Invoice có sản phẩm matching → Giảm giá đúng
- [ ] Invoice không có sản phẩm matching → Không giảm
- [ ] Giảm % → Tính đúng (price × qty × discount%)
- [ ] Giảm số tiền cố định → Tính đúng (discount × qty)

**INVOICE_TOTAL Rule:**
- [ ] Subtotal < min_invoice_amount → Không giảm
- [ ] Subtotal >= min_invoice_amount → Giảm giá đúng
- [ ] Giảm % → Tính đúng
- [ ] Giảm số tiền cố định → Tính đúng

**VOUCHER Rule:**
- [ ] Khách chưa dùng → Áp dụng thành công
- [ ] Khách đã dùng 1 lần (max_usage = 1) → Lỗi
- [ ] Subtotal < min_invoice_amount → Lỗi
- [ ] Tracking vào customer_voucher_usage → OK

**Edge Cases:**
- [ ] Invoice đã có promotion → Không thể áp mã khác
- [ ] Invoice status != DRAFT → Không thể áp mã
- [ ] Discount > subtotal → Lỗi (constraint)
- [ ] 1 Promotion có 3 rules (PRODUCT + INVOICE_TOTAL + VOUCHER) → Tính tổng đúng

---

## 7. Migration Script

```sql
-- File: migrations/008_add_advanced_promotion_system.sql

BEGIN;

-- 1. Create promotions table
CREATE TABLE promotions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) UNIQUE NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    valid_from TIMESTAMP NOT NULL,
    valid_to TIMESTAMP NOT NULL,
    is_active BOOLEAN DEFAULT true,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    CONSTRAINT chk_valid_dates CHECK (valid_from <= valid_to)
);

-- 2. Create discount_rules table
CREATE TABLE discount_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    promotion_id UUID NOT NULL REFERENCES promotions(id) ON DELETE CASCADE,
    rule_type VARCHAR(20) NOT NULL CHECK (rule_type IN ('PRODUCT', 'INVOICE_TOTAL', 'VOUCHER')),
    target_product_id UUID REFERENCES products(id),
    min_invoice_amount NUMERIC(15,2) DEFAULT 0,
    voucher_code VARCHAR(50),
    max_usage_per_customer INT DEFAULT 1,
    discount_type VARCHAR(20) NOT NULL CHECK (discount_type IN ('PERCENT', 'AMOUNT')),
    discount_value NUMERIC(15,2) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_product_rule CHECK (
        (rule_type = 'PRODUCT' AND target_product_id IS NOT NULL)
        OR (rule_type != 'PRODUCT' AND target_product_id IS NULL)
    ),
    CONSTRAINT chk_voucher_rule CHECK (
        (rule_type = 'VOUCHER' AND voucher_code IS NOT NULL)
        OR (rule_type != 'VOUCHER' AND voucher_code IS NULL)
    ),
    CONSTRAINT chk_discount_positive CHECK (discount_value > 0),
    CONSTRAINT chk_min_amount_positive CHECK (min_invoice_amount >= 0),
    CONSTRAINT chk_max_usage_positive CHECK (max_usage_per_customer > 0)
);

-- 3. Create customer_voucher_usage table
CREATE TABLE customer_voucher_usage (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id UUID NOT NULL REFERENCES customers(id),
    discount_rule_id UUID NOT NULL REFERENCES discount_rules(id),
    invoice_id UUID NOT NULL REFERENCES invoices(id),
    used_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(customer_id, discount_rule_id, invoice_id)
);

-- 4. Update invoices table
ALTER TABLE invoices
ADD COLUMN promotion_id UUID REFERENCES promotions(id),
ADD COLUMN discount_amount NUMERIC(15,2) DEFAULT 0,
ADD CONSTRAINT chk_discount_not_exceed 
    CHECK (discount_amount <= COALESCE(court_fee, 0) + COALESCE(product_fee, 0)),
ADD CONSTRAINT chk_discount_positive 
    CHECK (discount_amount >= 0);

-- 5. Create indexes
CREATE INDEX idx_promotions_code_active ON promotions(code) 
    WHERE is_active = true AND deleted_at IS NULL;
CREATE INDEX idx_promotions_validity ON promotions(valid_from, valid_to) 
    WHERE is_active = true AND deleted_at IS NULL;
CREATE INDEX idx_discount_rules_promotion ON discount_rules(promotion_id);
CREATE INDEX idx_discount_rules_product ON discount_rules(target_product_id) 
    WHERE rule_type = 'PRODUCT';
CREATE INDEX idx_discount_rules_voucher ON discount_rules(voucher_code) 
    WHERE rule_type = 'VOUCHER' AND voucher_code IS NOT NULL;
CREATE INDEX idx_voucher_usage_customer ON customer_voucher_usage(customer_id);
CREATE INDEX idx_voucher_usage_rule ON customer_voucher_usage(discount_rule_id);
CREATE INDEX idx_invoices_promotion ON invoices(promotion_id) 
    WHERE promotion_id IS NOT NULL;

COMMIT;
```

---

## 8. Tóm Tắt

### ✅ Ưu Điểm Thiết Kế 3 Tầng

1. **Linh hoạt:** 1 promotion có thể có nhiều rules
2. **Mở rộng:** Dễ thêm loại rule mới (ví dụ: CATEGORY, BRAND)
3. **Enterprise-grade:** Pattern chuẩn của Shopee, Tiki, Lazada
4. **Tracking:** customer_voucher_usage đảm bảo giới hạn usage
5. **Audit:** Biết chính xác khách nào dùng voucher nào, lúc nào

### ⚠️ Tradeoffs

- ❌ Phức tạp hơn (3 bảng thay vì 1)
- ❌ Tốn thêm 1-2 tuần implement
- ❌ Query phức tạp hơn (JOIN nhiều bảng)

### 🎯 Khi Nào Dùng

- ✅ Hệ thống eCommerce/POS với nhiều loại KM
- ✅ Cần tracking chi tiết voucher usage
- ✅ Cần flexibility cho marketing team
- ✅ Scale lớn (nhiều khách, nhiều KM)

### 🚫 Khi Nào KHÔNG Dùng

- ❌ Dự án nhỏ, KM đơn giản (chỉ "Giảm X% cho hóa đơn >= Y")
- ❌ Không có thời gian implement phức tạp
- ❌ Không cần tracking chi tiết

---

*Hệ thống khuyến mãi 3 tầng - Enterprise-grade promotion engine.*
