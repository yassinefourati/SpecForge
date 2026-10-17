-- "shop" fixture, MySQL (no sequence defaults: keys use AUTO_INCREMENT).
CREATE TABLE countries (code CHAR(2) PRIMARY KEY, name VARCHAR(80) NOT NULL);
CREATE TABLE customers (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  email VARCHAR(120) NOT NULL UNIQUE,
  full_name VARCHAR(100) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  notes TEXT);
CREATE TABLE products (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  sku VARCHAR(40) NOT NULL UNIQUE,
  name VARCHAR(100) NOT NULL,
  price DECIMAL(10,2) NOT NULL,
  country_code CHAR(2),
  FOREIGN KEY (country_code) REFERENCES countries (code));
CREATE TABLE orders (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  customer_id BIGINT NOT NULL,
  ordered_on DATE NOT NULL,
  status VARCHAR(20) DEFAULT 'NEW' NOT NULL,
  FOREIGN KEY (customer_id) REFERENCES customers (id));
CREATE TABLE order_items (
  order_id BIGINT NOT NULL,
  line_no INT NOT NULL,
  product_id BIGINT NOT NULL,
  quantity INT NOT NULL CHECK (quantity > 0),
  PRIMARY KEY (order_id, line_no),
  FOREIGN KEY (order_id) REFERENCES orders (id),
  FOREIGN KEY (product_id) REFERENCES products (id));
CREATE TABLE tags (id BIGINT AUTO_INCREMENT PRIMARY KEY, label VARCHAR(40) NOT NULL UNIQUE);
CREATE TABLE product_tags (
  product_id BIGINT NOT NULL,
  tag_id BIGINT NOT NULL,
  PRIMARY KEY (product_id, tag_id),
  FOREIGN KEY (product_id) REFERENCES products (id),
  FOREIGN KEY (tag_id) REFERENCES tags (id));
