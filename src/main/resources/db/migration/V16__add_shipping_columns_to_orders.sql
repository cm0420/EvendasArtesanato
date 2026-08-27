ALTER TABLE orders
ADD COLUMN shipping_cost NUMERIC(10,2),
ADD COLUMN shipping_option_code VARCHAR(50),
ADD COLUMN shipping_option_name VARCHAR(100),
ADD COLUMN estimated_delivery_days INTEGER;
