-- "shop" fixture, SQL Server.
CREATE SEQUENCE products_seq AS BIGINT START WITH 1 INCREMENT BY 1;
CREATE TABLE countries (code CHAR(2) PRIMARY KEY, name VARCHAR(80) NOT NULL);
CREATE TABLE customers (
  id BIGINT IDENTITY(1,1) PRIMARY KEY,
  email VARCHAR(120) NOT NULL UNIQUE,
  full_name NVARCHAR(100) NOT NULL,
  created_at DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
  notes NVARCHAR(MAX));
CREATE TABLE products (
  id BIGINT DEFAULT (NEXT VALUE FOR products_seq) PRIMARY KEY,
  sku VARCHAR(40) NOT NULL UNIQUE,
  name VARCHAR(100) NOT NULL,
  price DECIMAL(10,2) NOT NULL,
  country_code CHAR(2) REFERENCES countries (code));
CREATE TABLE orders (
  id BIGINT IDENTITY(1,1) PRIMARY KEY,
  customer_id BIGINT NOT NULL REFERENCES customers (id),
  ordered_on DATE NOT NULL,
  status VARCHAR(20) DEFAULT 'NEW' NOT NULL);
CREATE TABLE order_items (
  order_id BIGINT NOT NULL REFERENCES orders (id),
  line_no INT NOT NULL,
  product_id BIGINT NOT NULL REFERENCES products (id),
  quantity INT NOT NULL CHECK (quantity > 0),
  PRIMARY KEY (order_id, line_no));
CREATE TABLE tags (id BIGINT IDENTITY(1,1) PRIMARY KEY, label VARCHAR(40) NOT NULL UNIQUE);
CREATE TABLE product_tags (
  product_id BIGINT NOT NULL REFERENCES products (id),
  tag_id BIGINT NOT NULL REFERENCES tags (id),
  PRIMARY KEY (product_id, tag_id));
