CREATE TABLE products (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    name           VARCHAR(255)   NOT NULL,
    sku            VARCHAR(100)   NOT NULL UNIQUE,
    description    VARCHAR(1000),
    price          DECIMAL(10,2)  NOT NULL,
    category       VARCHAR(100)   NOT NULL,
    stock_quantity INT            NOT NULL,
    image_url      VARCHAR(500)
);

CREATE TABLE orders (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_number   VARCHAR(100)   NOT NULL UNIQUE,
    customer_email VARCHAR(255)   NOT NULL,
    status         VARCHAR(50)    NOT NULL,
    total          DECIMAL(10,2)  NOT NULL,
    created_at     TIMESTAMP      NOT NULL
);

CREATE TABLE order_items (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id     BIGINT         NOT NULL,
    product_id   BIGINT         NOT NULL,
    product_name VARCHAR(255)   NOT NULL,
    product_sku  VARCHAR(100)   NOT NULL,
    quantity     INT            NOT NULL,
    unit_price   DECIMAL(10,2)  NOT NULL,
    FOREIGN KEY (order_id) REFERENCES orders(id)
);

-- Seed: 12 tech products across 5 categories
INSERT INTO products (name, sku, description, price, category, stock_quantity) VALUES
('DevBook Pro 16',        'DBP-16',   'High-performance laptop for developers',         2499.99, 'Laptops',      50),
('DevBook Air 13',        'DBA-13',   'Lightweight laptop for everyday use',             999.99, 'Laptops',      75),
('CodeStation 15',        'CS-15',    'Mid-range laptop with great keyboard',           1299.99, 'Laptops',      40),
('MechKey Pro',           'MKP-01',   'Mechanical keyboard with Cherry MX switches',     149.99, 'Peripherals',  120),
('UltraWide 34',          'UW-34',    '34-inch ultrawide monitor for productivity',      699.99, 'Peripherals',   30),
('ErgoMouse X',           'EMX-01',   'Ergonomic wireless mouse',                         79.99, 'Peripherals',  200),
('GigaSwitch 8',          'GS-08',    '8-port gigabit network switch',                    49.99, 'Networking',   150),
('WiFi 6 Router Pro',     'WRP-01',   'Tri-band WiFi 6 router for home office',          249.99, 'Networking',    60),
('NVMe SSD 2TB',          'SSD-2T',   'PCIe 4.0 NVMe SSD — blazing fast storage',       189.99, 'Storage',       90),
('USB-C Hub 10-in-1',     'UCH-10',   '10-port USB-C hub with 4K HDMI',                  89.99, 'Accessories',  180),
('Laptop Stand Pro',      'LSP-01',   'Adjustable aluminum laptop stand',                 49.99, 'Accessories',  250),
('Webcam 4K',             'WC-4K',    '4K webcam with built-in ring light',              129.99, 'Peripherals',   85);
