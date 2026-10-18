# Changelog

Generated output changes only deliberately (principle P2 in `docs/prompts/phase-database-first.md`). Every change
to generated code is listed here and mirrored in the golden files under `src/test/resources/golden/`.

## Unreleased

### Ports and adapters (ARCH-6, ARCH-7, phase-output-presets P8)
- Architecture → **Ports and adapters (hexagonal)**, per table:
  - `domain.model` holds the domain model, plain Java;
  - `domain.port.in` holds the inbound port (`XxxUseCases`) with its request and response records;
  - `domain.port.out` holds the outbound port (`XxxRepositoryPort`, with Spring-free `PageQuery` / `PageResult`);
  - `application` holds the application service;
  - `adapter.in.web` holds the controller (PATCH included);
  - `adapter.out.persistence` holds the JPA entity, the Spring Data repository and the persistence adapter. The
    adapter keeps the same filters, text search, ranges and sorting as the other architectures.
- **Generated test:** `ArchitectureTest` (ArchUnit 1.5.0) forbids Spring, JPA, Hibernate and Lombok in the domain,
  and adapters in the domain and the application. A JPA annotation in a domain model makes it fail.
- **How it is built:** as an overlay on the standard templates, so every other option (styles, security, Flyway,
  tests) works the same. Build ids may now pin a version (`=group:artifact:scope:version`) for libraries the Boot
  BOM does not manage.
- **Not yet:** composite keys, use-case controllers and controller tests (left out, with a warning).
- `ArchitectureIT` runs the same scenario (now including PATCH and the unknown-filter 400) against the layered,
  feature, modular and hexagonal projects; golden set `shop-h2-hexagonal` (its `ArchitectureTest` passes).

### Modular monolith (ARCH-3 … ARCH-5, phase-output-presets P7)
- Architecture → **Modules (Spring Modulith)**:
  - every table belongs to a module, suggested as the group of tables linked by foreign keys (named after the table
    the others point to most), and changeable per table in Map;
  - each module is `base.<module>`, holding its controllers, request and response records and a
    `package-info.java` with `@ApplicationModule(allowedDependencies = "shared")`;
  - its entities, repositories, services and mappers are in `base.<module>.internal`;
  - paging, conversions and errors live in the open module `base.shared`; use cases go with their tables' module.
- **Across modules:**
  - a foreign key to another module's table is a plain id property (the database still enforces it), so modules
    never reach into each other;
  - many-to-many links, inverse collections and use-case operations across modules are left out, each with a
    warning.
- **Generated tests:** `ModularStructureTests` runs `ApplicationModules.verify()` and writes the PlantUML
  documentation. A deliberate breach (a sales class using `catalog.internal.Product`) makes it fail.
- **Build:** the Spring Modulith BOM is imported at the release built for the Boot line (2.1.1 for 4.1, 2.0.8 for
  4.0, 1.4.13 for 3.5).
- **Fix:** JavaTidy kept no import used only before the package line, which a `package-info.java` needs.
- Golden set `shop-h2-modular` (its `ModularStructureTests` pass); `ArchitectureIT.modularMonolith` runs the same
  HTTP scenario; `ModuleSplitterTest`.

### Architecture (ARCH-1, ARCH-2, phase-output-presets P6)
- Settings → **Architecture** (database flow): **by layer** (as before) or **by table**
  (`com.example.shop.customer.{Customer, CustomerRepository, CustomerService, CustomerMapper, CustomerController, …}`),
  with a preview of the packages.
- A package layout gives every class its package per table. Templates see their table's packages as before
  (`entityPackage`, …), another table's through `pkgOf("Customer", "entities")`, and what all tables share as
  `sharedModelPackage` / `sharedServicePackage`. Use cases stay in the shared packages.
- The layered output is byte-identical (every golden set unchanged).
- New golden set `shop-h2-feature`. `ArchitectureIT` runs the same HTTP scenario against each architecture on
  PostgreSQL: CRUD, a relation, a many-to-many, an aggregate use case across two tables, 404, 400, 409.

### Tests and migrations (TEST-1 … 3, MIG-1 … 3, phase-output-presets P5)
- Settings → **Tests and migrations** (database flow, all off by default).
- **Flyway, baseline:** the existing database is version 1 (`baseline-on-migrate`, `spring.flyway.schemas` = the
  source's schemas), so nothing runs on it, and changes start at V2. Flyway and the engine's module are in the
  build; `db/migration/README.md` explains it. Not for SQLite (with a warning).
- **Flyway, with the schema:** `V1__baseline.sql` holds the selected tables, written for the engine they come from
  (PostgreSQL, MySQL, MariaDB, H2):
  - the source's own types and defaults, identity / auto-increment, sequences, keys, unique keys, and foreign keys
    last;
  - what the snapshot cannot recreate (computed columns, enums and other user-defined types) falls back to the
    baseline, with the reason.
  - `DdlRoundTripIT` proves it per engine: fixture → snapshot → V1 → a fresh database → snapshot gives byte-identical
    generated Java.
- **Controller tests:** `@WebMvcTest` per controller, with the service mocked. They cover the list's page shape, 404
  for an unknown key, 400 for an incomplete body, and 204 on delete. Boot 4's and Boot 3's test packages are both
  handled.
- **Schema test:** `DatabaseSchemaTests` runs the application against a database that Flyway creates from V1:
  - a Testcontainers container of the source engine at the source's version (Testcontainers 2 with Boot 4, 1.x with
    Boot 3), skipped without Docker; or H2 in memory;
  - Hibernate validates every mapping against that schema, and every table answers.
- `MigrationsAndTestsIT` (Boot 4.1.1 and 3.5.16):
  1. the generated project's own tests pass, including the schema test on a real container;
  2. the app then starts against the existing database;
  3. Flyway's history holds only the baseline.
- Also: golden set `shop-h2-checks` (its 24 generated tests pass), and `DdlWriterTest`.

### admin-template modules (CONTRACT-1, 2, 4, 5, 8, phase-output-presets P3)
- Settings → **Where will this code live?** (database flow): a standalone project (as before) or **a module of an
  admin-template project** (Next.js + Spring Boot admin dashboard). The module follows that project's conventions:
  - `com.admintemplate.<module>` with entity, repository, `XxxDtos` (request / response records), service and
    controller per table;
  - `common.BaseEntity` for generated `Long` keys, `common.PageResponse`, `common.Paging`, `ApiException` with the
    project's message keys, and `@PreAuthorize` with a permission `<module>.manage`;
  - typed list parameters (`q`, filters, `…From` / `…To`);
  - module-prefixed bean and JPA entity names, so they never clash with the project's own `Customer`, `Product`, ….
- A **Flyway migration** at the project's next free version:
  - The tables, created as `<module>_…` with PostgreSQL types from the mapping. Portable defaults are kept (literals,
    booleans, "now"); others are reported. Foreign keys come last, with indexes, plus the join tables.
  - The permission.
  - Optionally a grant to managers and menu entries (both off by default: least privilege, and no menu entries
    before the module has pages).
  - Or "the tables already exist".
- SpecForge writes new files only. `backend/specforge-<module>.md` lists the exact lines for the project's
  hand-written registries:
  - `Permissions`, `Authorize`, `FormConstraints`, `AuditedEntities.NOT_AUDITED` and the UI texts;
  - the project tests that pin those lists.
- `<Module>ModuleIT` is generated on the project's `IntegrationTest`: CRUD on every table, in the order the required
  relations need, and a 403 without the permission.
- **Pages (CONTRACT-6, P4)**, on by default for the module, in the conventions of `features/customers`. Per table:
  - a list with search, sorting and paging (`DataTable`, `useListParams`), plus edit and delete;
  - a create / edit dialog: the inputs follow the column types, constraints come from the server, field errors
    come from the API, and relations are selects over the target's list;
  - React Query hooks, TypeScript types and the route.
- The texts are literal `t()` keys under `<module>.<entity>`, given as one JSON object in the instructions, so the
  project's `messages.test.ts` checks them in English and French.
- Many-to-many ids and binary values are kept in the dialog and changed through the API.
- Not yet: composite keys and use-case controllers (left out with the reason).
- Verified: `AdminTemplateModuleIT` (`-Dspecforge.adminTemplate=<checkout>`; with
  `-Dspecforge.adminTemplate.frontend=true` it also installs the frontend and runs `tsc --noEmit`,
  `eslint --max-warnings 0` and `vitest` on the pages).
  1. Generates the module from PostgreSQL into a copy of the checkout.
  2. Applies the instructions.
  3. Runs the project's whole `mvn verify` (its 162 unit tests and 236 integration tests, plus the module's).
- Also: golden set `shop-h2-admin-template`, and `AdminTemplateModuleTest`.

### List endpoints and token sign-in (CONTRACT-3, CONTRACT-7, phase-output-presets P2)
- Settings → **List endpoints** (database flow, all off by default, so output is unchanged):
  - **Free-text search:** `q` searches the columns marked **Search** in Map, case-insensitive "contains", with `%`, `_`
    and `\` taken literally. Suggested for bounded text columns, never for names that look like passwords, tokens,
    hashes or salts, and never for unbounded text.
  - **Range filters:** `<property>From` / `<property>To`, inclusive, for the columns marked **Range** (numbers, dates,
    times; suggested for all of them).
  - **Unknown sort property:** answer 400 (as before) or ignore it. When ignored, the key is the default order and the
    last tie-breaker, so pages never overlap.
  - A parameter that would take another's name is left out with a warning.
- Settings → Security, **How do callers sign in?** (database flow): HTTP Basic with a development user (as before) or
  **access tokens (JWT)**.
  - Tokens are checked against `JWT_ISSUER_URI`, the Keycloak key set (or `JWT_JWK_SET_URI`), and `JWT_AUDIENCE`
    when it is set.
  - Roles come from the scopes or from **Keycloak realm roles** (`realm_access.roles`, also for the spec flow).
  - Optional role for reading and role for changing data (`ROLE_` is added by Spring; written without it).
- **Security fix:** in the database flow, "Endpoint access rules: @PreAuthorize on controller methods" generated a
  filter chain that permitted every request, because database-flow controllers carry no `@PreAuthorize`. The
  database flow now always generates URL rules. Output changes only for profiles that chose that option, and
  those were unprotected.
- New golden set `shop-h2-lists-jwt`. `ListsAndJwtIT` (PostgreSQL) checks tokens (role, audience, issuer, expiry,
  signature), search, ranges and sorting against the running app. `ListParametersTest` checks the suggestions.

### Code style presets (STYLE-6 … STYLE-11, phase-output-presets P1)
- Settings → **Code style**: two presets, **Modern** (suggested) and **Same as before**, and two questions you can
  answer one by one. Profiles saved without a style keep generating exactly the same files; the golden sets
  `petstore-*` are unchanged, and the new `shop-h2-default` set locks the database-flow output.
- **Error responses:** "Problem details" (RFC 9457) generates an `ApiExceptionHandler` that extends
  `ResponseEntityExceptionHandler` and returns `application/problem+json` with `status`, `title`, `detail` and,
  for invalid input, `errors: [{field, message}]` using the JSON property names. Framework errors (unknown path,
  wrong method, unreadable body, unknown filter or sort) get the same shape. A stale row
  (`ObjectOptimisticLockingFailureException`) is a 409. Warns on Spring Boot 2.x, which has no `ProblemDetail`.
- **Request and response records (database flow):** `XxxRequest` is accepted on create and update (an assigned key,
  writable columns, relation ids, many-to-many ids; never a generated key) and carries the validation;
  `XxxResponse` is returned. PATCH validates the patched row against the request's rules. Use-case aggregates take
  request records for the parent and the items. New template `db-request.java.ftl`; template sets with their own
  `manifest.json` get a warning until they add its entry.
- New golden sets: `petstore-modern`, `petstore-proxy-modern`, `shop-h2-modern`. `ModernStyleIT` builds and runs the
  Modern output on PostgreSQL (CRUD, every error status, aggregate create).

## 1.0.0 (2026-10-17)

First release: Swagger / OpenAPI and database-first generation on eight engines, the mapping editor,
use-case controllers and Spring Boot Studio.

### Every relational engine (Milestone 2)
- MySQL, MariaDB, SQL Server, Oracle, Db2 LUW, SQLite and H2 join PostgreSQL. Their drivers are bundled at the
  Spring Boot 4.1.1 BOM's versions (MySQL Connector/J without its X DevAPI protobuf dependency) and self-checked at
  startup ("database drivers: 8 of 8 loaded"); the Drivers panel lists artifact, version, license and status.
- One dialect per engine reads its own catalog in bulk (information_schema, sys.*, ALL_*, SYSCAT.*, SQLite
  pragma table functions); the JDBC defaults fall back to one call per table for drivers that need a table name.
- Read-only per engine: `START TRANSACTION READ ONLY` and statement timeouts (MySQL / MariaDB), read-only
  transaction (Oracle), lock timeouts and the read-only application intent (SQL Server, which the UI marks as not
  enforceable), SQLite opened without the create flag plus `PRAGMA query_only`, H2 with `ACCESS_MODE_DATA=r` and
  `IFEXISTS`. A test proves writes are refused on every engine that can enforce it.
- SSL / TLS options in each engine's own URL syntax. Engines are tiles with their controls and limits; SQLite and
  H2 take a file path. "Least-privilege login…" shows the SQL for a structure-only login per engine (never run).
- Sequence defaults of every engine are recognised (`NEXT VALUE FOR`, Oracle `.NEXTVAL`, MariaDB `nextval()`).
- Uppercase names (Oracle, Db2, H2) are inflected as words (`COUNTRIES` → `Country`).
- Measures: JAR 52.3 MB (was 16.6 MB); start-up including the driver self-check about 0.3 s.
- Decisions recorded in `docs/adr/003-per-engine-identifiers-and-types.md`.

### Mapping editor (R2, MAP-11) and use-case controllers (R2, OPS-3 … OPS-5)
- Map step: every suggested value is editable, marked "suggested" or "yours", with ↺ back to the suggestion:
  class name, API path, controller (on / off, class name), read-only, key strategy (identity, sequence with
  allocation size, UUID, assigned), per column the property name, Java type, include, filter and sort, per foreign
  key the relation and DTO names and an opt-in inverse collection, and join tables as entities of their own.
- API step: remove a controller and keep its entity, repository and service; use-case controllers composed from
  several tables: create a parent with its children in one transaction, update a parent and replace its children,
  read a parent with its children, list a parent's children (paged, sorted on the child's whitelist), and a table's
  own operations moved in (or kept in both places). Decisions that cannot work are reported, never half-generated:
  unknown tables, no foreign key between parent and child, composite parents, read-only children, path variables
  that do not match, and routes another controller already serves.
- The plan contract grows (decisions only; `docs/contracts/plan.schema.json`); existing plans load unchanged.

### Spring Boot Studio (R2, STU-1 … STU-7, STU-10, STU-13)
- Opens from Settings ("Customize code…") and from any file in Preview ("Customize the template behind this file").
- Template sets in `~/.specforge/templates/<set>/` hold only customized templates; statuses Default, Custom and
  "Custom, default changed since"; Customize, Save (Ctrl+S), Reset to default, Compare with default; ZIP export
  and import (flat, safe names, size limits); "Use for generation" selects the set in the settings and profile.
- Editor: vendored CodeMirror 5 with FreeMarker highlighted over Java, XML, properties or JSON, line numbers,
  search, bracket matching. Live preview while typing for a chosen sample from the current spec or database, with
  a line diff against the default template's output, template errors at their line, rendered Java syntax-checked
  with the JDK parser (ADR 004), a variable explorer that inserts `[=path]`, and "Verify by compiling" (Maven).
- Templates render in the existing sandbox with a 10 s timeout and a 4,000,000-character output limit.

### UI (R2, UIX-1)
- One Profile menu (name and an unsaved-changes dot) replaces the six profile buttons; keyboard operable.

### Generated code
- Non-default column types carry `@JdbcTypeCode` (`CHAR`, `NCHAR`, `NVARCHAR`, CLOB, BLOB, ...), so
  `ddl-auto=validate` passes on every engine; this also fixes PostgreSQL `char(n)` columns.
- Scale-0 numbers up to 19 digits map to `Long`; Oracle `DATE` maps to `LocalDateTime`.
- A foreign key not ending in `_id` (e.g. `country_code`) gives relation `country` and DTO `countryCode`.
- Oracle: `hibernate.auto_quote_keyword` instead of global quoting, names quoted one by one where needed.
- MySQL / MariaDB: entities are not schema-qualified. SQLite: compile-scoped driver, community dialect,
  `PRAGMA foreign_keys = ON`, and 409 for `SQLITE_CONSTRAINT_*`.
- Mappers, repositories and services import the key types they use (UUID / BigDecimal keys of related tables).
- A manifest entry's `when` is evaluated per item (`entity`, `useCase`, `record` are in scope); the entity
  controller is left out when the user removed it or moved all its operations away. Services offer the operations
  use cases call even when the entity's own controller does not.

### Database flow (Milestones 0 and 1 of `docs/prompts/phase-database-first.md`)
- New source: **Build from database** (PostgreSQL). Read-only connection (read-only transaction, statement and lock
  timeouts, rollback), bulk introspection of tables, columns, keys, unique constraints, foreign keys, sequences
  (with `OWNED BY`), identity/generated flags, comments and row estimates.
- Saved connections in `~/.specforge/connections.json`; passwords AES-GCM encrypted (key file or PBKDF2), never
  returned by the API (masked), redacted from errors and logs. Password from an environment variable as an option.
- Introspection runs as a job with progress and cancel; snapshots are cached by fingerprint.
- Suggestion engine (ruleset `2026.10`): join tables → many-to-many, keys from facts (identity / sequence / UUID /
  assigned / composite with `@MapsId`), one-time inflection, reserved-word handling, vendor-first type rules.
- Generation plan stores decisions only (selection patterns, class names, operations, keys, read-only).
  Contracts frozen in `docs/contracts/snapshot.schema.json` and `docs/contracts/plan.schema.json` (drift-tested).
- New templates `db-entity`, `db-dto`, `db-mapper`, `db-value-conversions`, `db-page-response`, `db-repository`,
  `db-service`, `db-controller`; manifest entries carry `flow` (SPEC | DATABASE | ANY).
- UI: Build-from choice (Swagger / Database) on step 1 and in the header; steps Source → Select → Map → API →
  Settings → Preview for the database flow; Spring Boot and Java pickers from the synced metadata.

### Generated code
- Database flow: JPA entities with `@DynamicInsert` for columns with database defaults and a refresh after create;
  record DTOs with validation; mappers using `EntityManager.getReference`; merge-patch; filtering by
  Specifications with a whitelist; sort whitelist; `PageResponse`; 409 on constraint violations;
  `ddl-auto=validate`; `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` placeholders; HTTP Basic dev security.
- `pom.xml`: starters and versions resolved from start.spring.io metadata (bundled `spring/metadata.json`);
  default Spring Boot **4.1.1** (Boot 4 starter names such as `spring-boot-starter-webmvc` and per-starter test
  companions; Lombok through `annotationProcessorPaths` on Boot 4, excluded from the jar on Boot 3).
- `SecurityConfig`: one `auth.requestMatchers(...)` statement per rule instead of one long chain (a long chain
  overflowed javac's stack on large specs such as Gitea).
- Proxy mode: `UpstreamClient` and `ApiExceptionHandler` no longer call `HttpHeaders.containsKey` / `get` / `put`,
  which Spring Framework 7 (Spring Boot 4) removed. They use `getFirst` and `addAll(name, getValuesAsList(name))`,
  which exist in Spring 6 and 7, so proxy-mode projects compile on Spring Boot 3 and 4 (VER-7).

### SpecForge
- Added a test suite (JUnit, AssertJ, Testcontainers) with golden-file tests for the spec flow, a Boot matrix
  (`-Dspecforge.matrix=true`), and PostgreSQL integration tests that build, start and call the generated projects
  (prior-art fixture and the querywise fixture).
- `--refresh-spring-metadata=FILE` re-syncs Boot versions, starters and driver versions from start.spring.io.
- PostgreSQL JDBC driver bundled (version pinned to the Boot BOM's, see `docs/adr/002-driver-versions-without-bom-import.md`);
  introspection engine decision in `docs/adr/001-introspection-engine.md`.
