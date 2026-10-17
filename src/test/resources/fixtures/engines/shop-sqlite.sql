-- "shop" fixture, SQLite (INTEGER PRIMARY KEY is the rowid: SQLite assigns it on insert).
CREATE TABLE countries (code CHAR(2) PRIMARY KEY NOT NULL, name VARCHAR(80) NOT NULL);
CREATE TABLE customers (
  id INTEGER PRIMARY KEY,
  email VARCHAR(120) NOT NULL UNIQUE,
  full_name VARCHAR(100) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  notes TEXT);
CREATE TABLE products (
  id INTEGER PRIMARY KEY,
  sku VARCHAR(40) NOT NULL UNIQUE,
  name VARCHAR(100) NOT NULL,
  price DECIMAL(10,2) NOT NULL,
  country_code CHAR(2) REFERENCES countries (code));
CREATE TABLE orders (
  id INTEGER PRIMARY KEY,
  customer_id INTEGER NOT NULL REFERENCES customers (id),
  ordered_on DATE NOT NULL,
  status VARCHAR(20) DEFAULT 'NEW' NOT NULL);
CREATE TABLE order_items (
  order_id INTEGER NOT NULL REFERENCES orders (id),
  line_no INTEGER NOT NULL,
  product_id INTEGER NOT NULL REFERENCES products (id),
  quantity INTEGER NOT NULL CHECK (quantity > 0),
  PRIMARY KEY (order_id, line_no));
CREATE TABLE tags (id INTEGER PRIMARY KEY, label VARCHAR(40) NOT NULL UNIQUE);
CREATE TABLE product_tags (
  product_id INTEGER NOT NULL REFERENCES products (id),
  tag_id INTEGER NOT NULL REFERENCES tags (id),
  PRIMARY KEY (product_id, tag_id));
