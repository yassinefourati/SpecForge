# SpecForge

Builds a Spring Boot / Java 17 project from either source you pick:

- **Swagger / OpenAPI**: a Swagger 2.0 / OpenAPI 3.x definition (JSON or YAML) becomes entities, repositories,
  services and controllers (or, in proxy mode, a service that calls the existing API).
- **Database**: PostgreSQL, MySQL, MariaDB, SQL Server, Oracle, Db2, SQLite or H2 (all drivers bundled).
  SpecForge connects read-only, reads the schema (tables, keys, unique constraints, foreign keys, sequences,
  comments) and generates JPA entities, DTOs, mappers, repositories, services and controllers with full CRUD,
  filtering, paging and sorting. You choose the tables, edit every mapping, and compose controllers by use case.

Both flows add security config, exception handling and a `pom.xml` whose starters and versions follow
[start.spring.io](https://start.spring.io) (bundled metadata; refresh with `--refresh-spring-metadata`).
One runnable JAR; it serves a browser UI on a free port. **Spring Boot Studio** edits the templates behind every
generated file, with a live preview on your own data. The plan is in `docs/prompts/phase-database-first.md`; this
build delivers milestones 0–2 (R1 engines), the mapping editor and use-case controllers (R2 milestones 4–5, without
views and routines), the Studio core (R2 milestone 8, without code-style presets) and the Profile menu (UIX-1).

## Build and run

    mvn -DskipTests package                 # needs JDK 17+ and Maven; produces target/specforge.jar
    java -jar target/specforge.jar          # free port chosen automatically, URL printed, browser opened
    java -jar target/specforge.jar 9090     # fixed port (also --port=9090)
    java -jar target/specforge.jar --no-browser --templates=/path/to/templates --home=/path/to/state
    java -jar target/specforge.jar --refresh-spring-metadata=src/main/resources/spring/metadata.json   # maintainers: re-sync Boot versions and starters

State lives in `~/.specforge` (profiles in `profiles/`, templates in `templates/`, encrypted saved connections in
`connections.json` with the key in `secret.key`, snapshots in `snapshots/`). The server binds to loopback only.

## Tests

    mvn verify -DskipITs                    # unit + golden-file tests (no Docker)
    mvn verify                              # + integration tests via Testcontainers (needs Docker): PostgreSQL, MySQL,
                                            #   MariaDB, SQLite, H2; builds, starts and calls the generated projects
    mvn verify -Dspecforge.engines=all      # + SQL Server, Oracle and Db2 (large images; Db2 takes minutes to start)
    mvn verify -Dspecforge.matrix=true      # + Boot matrix: generated projects compiled on Boot 3.5 and 4.x (slow)
    mvn test -Dtest=GoldenFilesTest -DupdateGolden=true   # accept a deliberate change to generated output (log it in CHANGELOG.md)

## Using it

Pick **Build from** on the first step (also in the header).

Swagger / OpenAPI:

1. **Source**: paste, upload or give a URL, then Analyze. You see counts, warnings, resources and endpoints, and schemas where you can flip Entity/DTO.
2. **Settings**: target dir, base package, per-layer enable/sub-package/folder, naming patterns (`{Name}`), Lombok, validation, Jakarta/javax, DB, security. Save as a profile.
3. **Preview & generate**: file tree with highlighted contents; write to disk (if a file exists: skip, back up, or overwrite; default skip) or download a ZIP.

Database:

1. **Source**: pick an engine tile, then host, port, database, schemas, user, password (or an environment
   variable name), or a file for SQLite / H2. Test, save (the password is stored AES-GCM encrypted, never shown
   again), then **Read database**. "Least-privilege login…" gives your DBA the SQL for a structure-only login.
2. **Select**: every table is selected; search, untick, select all/none. Join tables are folded into many-to-many.
3. **Map**: every suggestion is editable (class, path, controller, key strategy, each column's property, type,
   inclusion, filter and sort, each relation's names and inverse collection); "yours" marks your choices, ↺ resets.
4. **API**: one controller per table; untick operations or remove a controller. **Use-case controllers** combine
   tables: create / update a parent with its children in one transaction, read a parent with its children, list a
   parent's children, or move a table's operations in.
5. **Settings** and 6. **Preview & generate** as above. The generated `application.properties` reads
   `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`; it never contains the password. `ddl-auto=validate`: Hibernate checks the
   entities against the real schema and never changes it.

The connection is read-only on every level: read-only transaction, statement/lock timeouts, catalog queries only.

## Code style

Settings → **Code style** offers two presets and lets you change each choice afterwards:

- **Modern** (suggested): errors as RFC 9457 problem details (`status`, `title`, `detail`, `errors[{field, message}]`),
  and in the database flow separate `XxxRequest` (input, validated, no generated values) and `XxxResponse` (output)
  records.
- **Same as before**: the output of earlier versions (simple error JSON, one `XxxDto` per table).

In the database flow, Settings → **List endpoints** adds free-text search (`q`), range filters (`priceFrom` /
`priceTo`) and lenient sorting, for the columns you mark **Search** and **Range** in Map. Settings → Security can switch
sign-in from a development user to **JWT access tokens** (e.g. Keycloak), optionally with a read and a write role.

### admin-template modules

Settings → **Where will this code live?** → "A module of my admin-template project" generates the selected
tables as `com.admintemplate.<module>` in that project's conventions, plus:
- a Flyway migration (tables, permission);
- a `<Module>ModuleIT`;
- the pages: a list and a create / edit dialog per table, in the project's frontend;
- `backend/specforge-<module>.md` with the few lines to add to the project's own registries.

Nothing in the project is edited.

### Tests and migrations

Settings → **Tests and migrations** (database flow) adds:
- Flyway: the existing database becomes version 1, optionally with `V1__baseline.sql` holding the schema for empty
  databases (PostgreSQL, MySQL, MariaDB, H2);
- a web-layer test per controller;
- a schema test that runs the app against a database created from V1 (a container of your engine, or H2 in memory).

### Architecture

Settings → **Architecture** (database flow):
- **by layer** (default);
- **by table** (one package per table);
- **modules (Spring Modulith)**: groups of tables (suggested from foreign keys, changeable in Map), each with an
  API package and an `internal` one, a `package-info.java`, and a generated `ModularStructureTests`. A foreign key
  to another module's table becomes its id;
- **ports and adapters (hexagonal)**: a plain domain model, inbound and outbound ports, an application service,
  and web and JPA adapters per table, with a generated ArchUnit `ArchitectureTest`.

## Defaults (all changeable in Settings)

Spring Boot 4.1.1 (3.5.x and 4.0.x selectable), Java 17, Jakarta, H2 (spec flow) or the source database (database flow), no Lombok, validation on, `OffsetDateTime`, resources grouped by tag, plural snake_case tables,
security rules as request matchers, existing files skipped, base package `com.example.api`, target `./generated`.

## Templates and Spring Boot Studio

**Settings → Customize code… (Studio)**, or "Customize the template behind this file" in Preview. Pick or create a
template set, Customize a template, edit it with the live preview of your own spec or database beside it (diff
against the default, errors at their line, Java syntax check, variable explorer), Save, and "Use for generation".
Reset to default undoes a customization. Sets live in `~/.specforge/templates/<set>/` and export / import as ZIP.

Without the Studio: click "Export default templates" in Settings, edit the `.ftl` files and `manifest.json` in that folder. Files there override the bundled ones one by one.
Directives are `<#if>`; interpolation is `[=expr]` so `${...}` passes through. Variables are listed in `templates/README.txt`.

## Mapping rules (short, spec flow)

- A schema is an entity when it is the main body of a resource or has an id-like property; otherwise a DTO. Override in step 1.
- `allOf` is flattened; `oneOf`/`anyOf` become one union class with optional fields (warning).
- Nested objects/maps are stored as JSON columns; entity references become `@ManyToOne`/`@ManyToMany`; scalar lists `@ElementCollection`.
- CRUD-shaped operations get real implementations; others become stubs returning HTTP 501.
- Security schemes: apiKey (filter), http basic, bearer/OAuth2/OIDC (JWT resource server); per-operation rules from `security` + scopes.

## Mapping rules (short, database flow)

- Keys come from facts: identity column → `IDENTITY`; `nextval('seq')` default → `SEQUENCE` with that sequence and its
  real increment; UUID with a default → `UUID`; otherwise the key is assigned by the client. Composite keys use
  `@EmbeddedId` plus `@MapsId` for key columns that are also foreign keys.
- Foreign keys become `@ManyToOne` (DTOs carry the id, e.g. `siteId`); pure join tables become `@ManyToMany`.
- Names are inflected once (`people` → `Person`), Java/SQL reserved words get a suffix, paths come from the table.
- Types: vendor type first (`jsonb` → `String` with `SqlTypes.JSON`, `timestamptz` → `OffsetDateTime`, ...);
  unsupported types are left out with a warning. Columns with a database default are not sent on insert.
- Unique constraints, `NOT NULL`, lengths and precision become annotations and validations; constraint
  violations answer 409.

## Example output for `samples/petstore.yaml` (expected layout)

    generated/
      pom.xml
      src/main/resources/application.properties
      src/main/java/com/example/api/
        PetStoreApplication.java
        entity/        Pet.java  Tag.java
        model/enums/ or enums/   PetStatus.java
        repository/    PetRepository.java  TagRepository.java
        service/       PetService.java
        controller/    PetController.java
        security/      SecurityConfig.java  ApiKeyAuthFilter.java
        exception/     ResourceNotFoundException.java  ApiExceptionHandler.java
