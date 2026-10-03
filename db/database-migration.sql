-- =====================================================
-- DATABASE MIGRATION SCRIPT
-- Hệ thống Quản lý Sân Cầu Lông
-- Version: 1.0
-- Date: 2026-10-03
-- =====================================================

-- Extension for UUID generation
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- =====================================================
-- 1. AUTH & USERS MODULE
-- =====================================================

-- Users table (Authentication only)
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

-- Roles table
CREATE TABLE roles (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    role_name VARCHAR(50) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- User-Role mapping (Many-to-Many)
CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, role_id)
);

-- Customers table (Domain data)
CREATE TABLE customers (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    full_name VARCHAR(255) NOT NULL,
    phone VARCHAR(20) NOT NULL,
    address TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Staffs table (Domain data)
CREATE TABLE staffs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    full_name VARCHAR(255) NOT NULL,
    phone VARCHAR(20) NOT NULL,
    position VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- =====================================================
-- 2. CORE DATA MODULE
-- =====================================================

-- Courts table
CREATE TABLE courts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    court_number INT NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'MAINTENANCE', 'CLOSED')),
    base_price NUMERIC(10, 2) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

-- Time Slots table (Master data - templates only)
CREATE TABLE time_slots (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    price_multiplier NUMERIC(3, 2) NOT NULL DEFAULT 1.0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_time_order CHECK (start_time < end_time)
);

-- =====================================================
-- 3. BOOKING ENGINE MODULE
-- =====================================================

-- Bookings table
CREATE TABLE bookings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    customer_id UUID NOT NULL REFERENCES customers(id) ON DELETE RESTRICT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PAID', 'CHECKED_IN', 'COMPLETED', 'NO_SHOW', 'EXPIRED')),
    court_fee NUMERIC(10, 2) NOT NULL,
    expires_at TIMESTAMP,
    created_by UUID REFERENCES staffs(id),
    updated_by UUID REFERENCES staffs(id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_pending_expires CHECK (status != 'PENDING' OR expires_at IS NOT NULL)
);

-- Booking Details table (Snapshot pricing)
CREATE TABLE booking_details (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    booking_id UUID NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    court_id UUID NOT NULL REFERENCES courts(id) ON DELETE RESTRICT,
    time_slot_id UUID NOT NULL REFERENCES time_slots(id) ON DELETE RESTRICT,
    booking_date DATE NOT NULL,
    price NUMERIC(10, 2) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Payment Transactions table
CREATE TABLE payment_transactions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    booking_id UUID REFERENCES bookings(id) ON DELETE RESTRICT,
    invoice_id UUID REFERENCES invoices(id) ON DELETE RESTRICT,
    payment_method VARCHAR(50) NOT NULL CHECK (payment_method IN ('CASH', 'BANK_TRANSFER', 'VNPAY', 'MOMO')),
    transaction_id VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'REFUNDED')),
    amount NUMERIC(10, 2) NOT NULL,
    transaction_date TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    note TEXT,
    CONSTRAINT chk_payment_target CHECK (
        (booking_id IS NOT NULL AND invoice_id IS NULL) OR
        (booking_id IS NULL AND invoice_id IS NOT NULL)
    )
);

-- =====================================================
-- 4. POS & PROMOTION MODULE
-- =====================================================

-- Categories table
CREATE TABLE categories (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    category_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

-- Products table (with Optimistic Locking)
CREATE TABLE products (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    category_id UUID NOT NULL REFERENCES categories(id) ON DELETE RESTRICT,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(20) NOT NULL CHECK (type IN ('GOODS', 'SERVICE')),
    price NUMERIC(10, 2) NOT NULL,
    stock_quantity INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    created_by UUID REFERENCES staffs(id),
    updated_by UUID REFERENCES staffs(id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

-- Promotions table
CREATE TABLE promotions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    valid_from DATE NOT NULL,
    valid_to DATE NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_by UUID REFERENCES staffs(id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

-- Discount Rules table (3-tier system)
CREATE TABLE discount_rules (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    promotion_id UUID NOT NULL REFERENCES promotions(id) ON DELETE CASCADE,
    rule_type VARCHAR(20) NOT NULL CHECK (rule_type IN ('PRODUCT', 'INVOICE_TOTAL', 'VOUCHER')),
    target_product_id UUID REFERENCES products(id) ON DELETE RESTRICT,
    min_invoice_amount NUMERIC(10, 2),
    voucher_code VARCHAR(50),
    max_usage_per_customer INT,
    discount_type VARCHAR(20) NOT NULL CHECK (discount_type IN ('PERCENT', 'AMOUNT')),
    discount_value NUMERIC(10, 2) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_product_rule CHECK (rule_type != 'PRODUCT' OR target_product_id IS NOT NULL),
    CONSTRAINT chk_voucher_rule CHECK (rule_type != 'VOUCHER' OR voucher_code IS NOT NULL)
);

-- Customer Voucher Usage tracking
CREATE TABLE customer_voucher_usage (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    customer_id UUID NOT NULL REFERENCES customers(id) ON DELETE RESTRICT,
    discount_rule_id UUID NOT NULL REFERENCES discount_rules(id) ON DELETE RESTRICT,
    invoice_id UUID NOT NULL REFERENCES invoices(id) ON DELETE RESTRICT,
    used_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (customer_id, discount_rule_id, invoice_id)
);

-- Invoices table
CREATE TABLE invoices (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    booking_id UUID REFERENCES bookings(id) ON DELETE RESTRICT,
    customer_id UUID NOT NULL REFERENCES customers(id) ON DELETE RESTRICT,
    promotion_id UUID REFERENCES promotions(id) ON DELETE SET NULL,
    payment_method VARCHAR(50) NOT NULL CHECK (payment_method IN ('CASH', 'BANK_TRANSFER', 'VNPAY', 'MOMO')),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PAID', 'REFUNDED', 'CANCELLED')),
    court_fee NUMERIC(10, 2) DEFAULT 0,
    product_fee NUMERIC(10, 2) NOT NULL DEFAULT 0,
    discount_amount NUMERIC(10, 2) NOT NULL DEFAULT 0,
    total_amount NUMERIC(10, 2) NOT NULL,
    created_by UUID REFERENCES staffs(id),
    updated_by UUID REFERENCES staffs(id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_discount_amount CHECK (discount_amount <= COALESCE(court_fee, 0) + product_fee)
);

-- Invoice Details table
CREATE TABLE invoice_details (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    invoice_id UUID NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    quantity INT NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(10, 2) NOT NULL,
    note TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- =====================================================
-- 5. INVENTORY MODULE
-- =====================================================

-- Suppliers table
CREATE TABLE suppliers (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    phone VARCHAR(20),
    address TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

-- Import Orders table
CREATE TABLE import_orders (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    supplier_id UUID NOT NULL REFERENCES suppliers(id) ON DELETE RESTRICT,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'CONFIRMED', 'RECEIVED', 'CANCELLED')),
    total_amount NUMERIC(10, 2) NOT NULL,
    import_date DATE NOT NULL,
    created_by UUID REFERENCES staffs(id),
    updated_by UUID REFERENCES staffs(id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Import Order Details table
CREATE TABLE import_order_details (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    import_order_id UUID NOT NULL REFERENCES import_orders(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    quantity INT NOT NULL CHECK (quantity > 0),
    import_price NUMERIC(10, 2) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- =====================================================
-- 6. INDEXES FOR PERFORMANCE
-- =====================================================

-- Booking & Court Management
CREATE INDEX idx_bookings_customer_created ON bookings(customer_id, created_at DESC);
CREATE INDEX idx_booking_details_date_court ON booking_details(booking_date, court_id);
CREATE INDEX idx_bookings_status ON bookings(status) WHERE status IN ('PENDING', 'PAID', 'CHECKED_IN');
CREATE INDEX idx_bookings_expires ON bookings(expires_at) WHERE status = 'PENDING' AND expires_at IS NOT NULL;
CREATE UNIQUE INDEX uq_booking_slot ON booking_details(booking_date, court_id, time_slot_id);

-- Invoice & Payment
CREATE INDEX idx_invoices_customer_created ON invoices(customer_id, created_at DESC);
CREATE INDEX idx_invoices_booking ON invoices(booking_id) WHERE booking_id IS NOT NULL;
CREATE INDEX idx_payments_booking ON payment_transactions(booking_id) WHERE booking_id IS NOT NULL;
CREATE INDEX idx_payments_invoice ON payment_transactions(invoice_id) WHERE invoice_id IS NOT NULL;
CREATE UNIQUE INDEX uq_transaction_id ON payment_transactions(transaction_id) WHERE transaction_id IS NOT NULL;
CREATE INDEX idx_payments_status ON payment_transactions(status, transaction_date DESC);

-- Product & Inventory
CREATE INDEX idx_products_category_active ON products(category_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_products_type_stock ON products(type, stock_quantity) WHERE type = 'GOODS';
CREATE INDEX idx_invoice_details_product ON invoice_details(product_id);
CREATE INDEX idx_import_orders_supplier ON import_orders(supplier_id, import_date DESC);

-- Promotion System
CREATE INDEX idx_promotions_code_active ON promotions(code) WHERE is_active = true AND deleted_at IS NULL;
CREATE INDEX idx_promotions_validity ON promotions(valid_from, valid_to) WHERE is_active = true AND deleted_at IS NULL;
CREATE INDEX idx_discount_rules_promotion ON discount_rules(promotion_id);
CREATE INDEX idx_discount_rules_product ON discount_rules(target_product_id) WHERE rule_type = 'PRODUCT';
CREATE INDEX idx_discount_rules_voucher ON discount_rules(voucher_code) WHERE rule_type = 'VOUCHER' AND voucher_code IS NOT NULL;
CREATE INDEX idx_voucher_usage_customer ON customer_voucher_usage(customer_id);
CREATE INDEX idx_voucher_usage_rule ON customer_voucher_usage(discount_rule_id);

-- User Management
CREATE INDEX idx_users_email ON users(email) WHERE deleted_at IS NULL;
CREATE INDEX idx_customers_phone ON customers(phone);
CREATE INDEX idx_staffs_active ON staffs(id);

-- =====================================================
-- 7. SEED DATA (Optional - for development)
-- =====================================================

-- Insert default roles
INSERT INTO roles (role_name) VALUES
    ('ADMIN'),
    ('STAFF'),
    ('CUSTOMER');

-- Insert sample time slots (6:00 AM - 10:00 PM)
INSERT INTO time_slots (start_time, end_time, price_multiplier) VALUES
    ('06:00:00', '07:00:00', 1.0),
    ('07:00:00', '08:00:00', 1.0),
    ('08:00:00', '09:00:00', 1.2),
    ('09:00:00', '10:00:00', 1.2),
    ('10:00:00', '11:00:00', 1.0),
    ('11:00:00', '12:00:00', 1.0),
    ('12:00:00', '13:00:00', 1.0),
    ('13:00:00', '14:00:00', 1.0),
    ('14:00:00', '15:00:00', 1.0),
    ('15:00:00', '16:00:00', 1.2),
    ('16:00:00', '17:00:00', 1.2),
    ('17:00:00', '18:00:00', 1.5),
    ('18:00:00', '19:00:00', 1.5),
    ('19:00:00', '20:00:00', 1.5),
    ('20:00:00', '21:00:00', 1.5),
    ('21:00:00', '22:00:00', 1.2);

-- =====================================================
-- END OF MIGRATION SCRIPT
-- =====================================================
