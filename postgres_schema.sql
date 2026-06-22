-- BORMAK BÜRO MAKİNELERİ | Stok ve Sayaç Portalı
-- PostgreSQL Schema Initialization Script (Supabase ve Heroku Postgres Uyumlu)

-- 1. Kullanıcılar (Giriş Yetkileri)
CREATE TABLE IF NOT EXISTS users (
    id SERIAL PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    full_name VARCHAR(100) NOT NULL,
    role VARCHAR(50) NOT NULL -- 'ADMIN' veya 'STAFF'
);

-- 2. Ürünler ve Ana Depo Miktarı
CREATE TABLE IF NOT EXISTS products (
    id SERIAL PRIMARY KEY,
    stock_code VARCHAR(50) UNIQUE NOT NULL,
    name VARCHAR(150) NOT NULL,
    supplier_name VARCHAR(200) DEFAULT NULL,
    warehouse_quantity INT NOT NULL DEFAULT 0
);

-- 3. Personeller (Çalışanlar)
CREATE TABLE IF NOT EXISTS employees (
    id SERIAL PRIMARY KEY,
    employee_code VARCHAR(50) UNIQUE NOT NULL,
    first_name VARCHAR(50) NOT NULL,
    last_name VARCHAR(50) NOT NULL
);

-- 4. Müşteriler
CREATE TABLE IF NOT EXISTS customers (
    id SERIAL PRIMARY KEY,
    customer_code VARCHAR(50) UNIQUE NOT NULL,
    company_name VARCHAR(150) NOT NULL,
    responsible_employee_id INT DEFAULT NULL,
    unit_price DECIMAL(10, 4) NOT NULL DEFAULT 0.1500,
    business_model VARCHAR(50) NOT NULL DEFAULT 'KOPYA_BASI', -- 'KOPYA_BASI', 'MALZEME_KARSILIGI' veya 'NORMAL'
    FOREIGN KEY (responsible_employee_id) REFERENCES employees(id) ON DELETE SET NULL
);

-- 5. Personel Zimmetleri (Çalışanın elindeki ürün miktarı)
CREATE TABLE IF NOT EXISTS employee_handovers (
    employee_id INT NOT NULL,
    product_id INT NOT NULL,
    quantity INT NOT NULL DEFAULT 0,
    PRIMARY KEY (employee_id, product_id),
    FOREIGN KEY (employee_id) REFERENCES employees(id) ON DELETE CASCADE,
    FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
);

-- 6. Müşteri Teslimatları (Müşterideki aktif ürün miktarı)
CREATE TABLE IF NOT EXISTS customer_deliveries (
    customer_id INT NOT NULL,
    product_id INT NOT NULL,
    quantity INT NOT NULL DEFAULT 0,
    PRIMARY KEY (customer_id, product_id),
    FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE CASCADE,
    FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
);

-- 7. Stok Hareketleri Geçmişi
CREATE TABLE IF NOT EXISTS stock_movements (
    id SERIAL PRIMARY KEY,
    transaction_type VARCHAR(100) NOT NULL, -- 'WAREHOUSE_ENTRY', 'WAREHOUSE_TO_EMPLOYEE', 'EMPLOYEE_TO_CUSTOMER', 'WAREHOUSE_TO_CUSTOMER'
    product_id INT NOT NULL,
    employee_id INT DEFAULT NULL,
    customer_id INT DEFAULT NULL,
    quantity INT NOT NULL,
    transaction_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    description VARCHAR(255) DEFAULT NULL,
    FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE,
    FOREIGN KEY (employee_id) REFERENCES employees(id) ON DELETE SET NULL,
    FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE SET NULL
);

-- 8. Müşteri Makineleri (Kopya Başı Makineleri)
CREATE TABLE IF NOT EXISTS customer_machines (
    id SERIAL PRIMARY KEY,
    customer_id INT NOT NULL,
    serial_number VARCHAR(100) NOT NULL,
    machine_name VARCHAR(150) NOT NULL,
    initial_meter INT NOT NULL DEFAULT 0,
    current_meter INT NOT NULL DEFAULT 0,
    initial_meter_date DATE DEFAULT NULL,
    installation_date DATE DEFAULT NULL,
    ownership_type VARCHAR(50) NOT NULL DEFAULT 'BİZİM_MAKİNEMİZ',
    FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE CASCADE
);

-- 9. Sayaç Okumaları (Kopya Başı Toner Takip Sistemi)
CREATE TABLE IF NOT EXISTS meter_readings (
    id SERIAL PRIMARY KEY,
    customer_id INT NOT NULL,
    machine_id INT DEFAULT NULL,
    reading_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    meter_value INT NOT NULL,
    previous_meter_value INT NOT NULL DEFAULT 0,
    difference INT NOT NULL DEFAULT 0,
    unit_price DECIMAL(10, 4) NOT NULL,
    total_amount DECIMAL(12, 2) NOT NULL,
    FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE CASCADE,
    FOREIGN KEY (machine_id) REFERENCES customer_machines(id) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_customer_reading ON meter_readings (customer_id, reading_date);

-- 10. Toner Türleri (Toner Maliyet Analiz Modülü)
CREATE TABLE IF NOT EXISTS toner_types (
    id SERIAL PRIMARY KEY,
    toner_name VARCHAR(150) NOT NULL,
    unit_kg_cost DECIMAL(10, 2) NOT NULL,
    standard_page_capacity INT NOT NULL DEFAULT 20000
);

-- 11. Toner Dolum Kayıtları (Toner Maliyet Analiz Modülü)
CREATE TABLE IF NOT EXISTS toner_fillings (
    id SERIAL PRIMARY KEY,
    toner_type_id INT NOT NULL,
    filled_grams INT NOT NULL CHECK (filled_grams > 0),
    calculated_cost DECIMAL(10, 2) NOT NULL,
    expected_pages INT NOT NULL,
    filling_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (toner_type_id) REFERENCES toner_types(id) ON DELETE CASCADE
);

-- 12. Müşteriye Toner Teslimatları (Müşteri Bazlı Maliyet Analizi)
CREATE TABLE IF NOT EXISTS customer_toner_deliveries (
    id SERIAL PRIMARY KEY,
    customer_id INT NOT NULL,
    toner_type_id INT NOT NULL,
    delivered_grams INT NOT NULL CHECK (delivered_grams > 0),
    delivery_cost DECIMAL(10, 2) NOT NULL,
    delivery_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    notes VARCHAR(255) DEFAULT NULL,
    FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE CASCADE,
    FOREIGN KEY (toner_type_id) REFERENCES toner_types(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_cust_toner_date ON customer_toner_deliveries (customer_id, delivery_date);

-- 13. Servis Giderleri (Müşteri Kartı - Servis Takibi)
CREATE TABLE IF NOT EXISTS service_expenses (
    id SERIAL PRIMARY KEY,
    customer_id INT NOT NULL,
    description VARCHAR(255) DEFAULT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    expense_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_cust_service_date ON service_expenses (customer_id, expense_date);

-- 14. Toner Operasyonları (Cihaz bazlı toner dolum işlemleri)
CREATE TABLE IF NOT EXISTS toner_operations (
    id SERIAL PRIMARY KEY,
    device_id INT NOT NULL,
    toner_type_id INT NOT NULL,
    filled_grams INT NOT NULL,
    calculated_cost DECIMAL(10, 2) NOT NULL,
    expected_pages INT NOT NULL,
    operation_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (device_id) REFERENCES customer_machines(id) ON DELETE CASCADE,
    FOREIGN KEY (toner_type_id) REFERENCES toner_types(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_toner_op_device ON toner_operations (device_id);
CREATE INDEX IF NOT EXISTS idx_toner_op_type ON toner_operations (toner_type_id);

-- 15. Genel Operasyon Günlüğü (Sorumluluk, Stok ve Sözleşme Takip Sistemi)
CREATE TABLE IF NOT EXISTS operations_log (
    id SERIAL PRIMARY KEY,
    device_id INT NOT NULL, -- referans: customer_machines.id
    operation_type VARCHAR(50) NOT NULL, -- 'KOPYA_BASI_TONER', 'KOPYA_BASI_YEDEK_PARCA', 'MALZEME_KARSILIGI_YEDEK_PARCA', 'NORMAL_YEDEK_PARCA', 'NORMAL_TONER'
    toner_type_id INT DEFAULT NULL,
    filled_grams INT DEFAULT 0,
    product_id INT DEFAULT NULL,
    quantity INT DEFAULT 0,
    material_description VARCHAR(255) DEFAULT NULL,
    calculated_cost DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    billed_amount DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    expected_pages INT NOT NULL DEFAULT 0,
    operation_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (device_id) REFERENCES customer_machines(id) ON DELETE CASCADE,
    FOREIGN KEY (toner_type_id) REFERENCES toner_types(id) ON DELETE SET NULL,
    FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_op_log_device ON operations_log (device_id);
CREATE INDEX IF NOT EXISTS idx_op_log_type ON operations_log (operation_type);

-- Varsayılan Kullanıcıları Ekleme
INSERT INTO users (username, password, full_name, role) VALUES 
('admin', 'admin123', 'Yönetici Admin', 'ADMIN'),
('personel', 'personel123', 'Stok Sorumlusu Personel', 'STAFF')
ON CONFLICT (username) DO NOTHING;
