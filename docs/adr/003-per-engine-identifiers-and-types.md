# ADR 003: Per-engine identifier quoting, schema qualification and column types

- **Status:** Accepted (2026-10-17)
- **Deviates from:** Milestone 1's single rule for every engine: quote every identifier
  (`globally_quoted_identifiers=true`) and always qualify tables with their schema
- **Keeps:** INT-8 (identifiers exactly as the engine stores them, quoted when needed), GEN-2 (`ddl-auto=validate`),
  GEN-6 (the project starts against the source with zero manual edits)

## Context

Milestone 2 runs the same "shop" model on every tier-1 engine (`EngineMatrixIT`) and starts each generated
application with `ddl-auto=validate`. That surfaced engine behaviour that one rule cannot serve:

1. **Oracle.** With every identifier quoted, Hibernate inserts into `"SHOP"."CUSTOMERS"` and asks the driver for
   the generated identity value. ojdbc parses the table name to do that and fails on a quoted `schema.table`
   (ORA-04043, "Object SHOP does not exist"). Unquoted uppercase names are what Oracle stores anyway.
2. **MySQL and MariaDB.** A schema is the database itself. `@Table(schema = "shop")` would tie the generated
   application to that database name, although `DB_URL` already selects the database.
3. **Column types.** Hibernate validates the JDBC type the driver reports. `CHAR`, `NCHAR`, `NVARCHAR`, CLOB and
   BLOB columns are not the default type of `String` / `byte[]`, so validation fails without `@JdbcTypeCode`.
   PostgreSQL `bpchar` failed the same way (a latent Milestone 1 bug the prior-art fixture did not cover).
4. **Numbers.** Oracle `NUMBER(19)` is Hibernate's own column for `Long`, but the precision rule mapped it to
   `BigDecimal`. SQLite reports every `*INT*` column as `INTEGER` and `DECIMAL` as `FLOAT`.
5. **SQLite.** The Hibernate dialect is in `hibernate-community-dialects`; it does not classify constraint errors
   (no 409), and SQLite checks foreign keys only when asked to per connection.

## Decision

- **Oracle:** the generated configuration sets `hibernate.auto_quote_keyword=true` instead of global quoting; the
  suggestion engine quotes, with Hibernate's backticks, each name that is not plain uppercase.
- **MySQL / MariaDB:** entities, sequences and join tables are not schema-qualified.
- **Types:** a column whose JDBC type is not its Java type's default carries the matching `SqlTypes` code
  (`CHAR`, `NCHAR`, `NVARCHAR`, `LONGVARCHAR`, `CLOB`, `NCLOB`, `BINARY`, `LONGVARBINARY`, `BLOB`). JSON and
  large objects are not offered for filtering, sorting or length checks.
- **Numbers:** scale 0 and precision up to 19 → `Long`. SQLite follows the JDBC type its driver reports.
  Oracle `DATE` maps to `LocalDateTime`, because it stores a time of day.
- **SQLite:** `sqlite-jdbc` (compile scope) and `hibernate-community-dialects` are added as BOM-managed
  libraries without Initializr ids; the configuration names the dialect and runs `PRAGMA foreign_keys = ON` per
  connection; the exception handler maps `SQLITE_CONSTRAINT_*` result codes to 409.
- **Foreign keys not ending in `_id`** (e.g. `country_code`): the relation is named after its target
  (`country`) and the DTO keeps the column's name (`countryCode`), instead of `countryCodeId`.

## Consequences

- Every rule above is exercised by `EngineMatrixIT` on a real engine and by unit tests in `SuggestionRulesTest`.
- Templates branch on `source.engine` in three places (properties, exception handler, build ids). That is the
  STU-12 mechanism, not a copy of templates per engine.
- An Oracle name that is a keyword relies on Hibernate's keyword list (`auto_quote_keyword`).
- A PostgreSQL `char(n)` column now generates `@JdbcTypeCode(SqlTypes.CHAR)`.
