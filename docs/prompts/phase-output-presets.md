# Phase: Output presets, architectures and generated tests

| | |
|---|---|
| **Audience** | The engineer (or AI coding agent) implementing this phase; the product owner reviews each milestone |
| **Builds on** | `phase-database-first.md` (STYLE-1…6, MAP-2, GEN-*, STU-*), `phase-wizard-and-views.md` |
| **Inputs studied** | `D:\Workspace\projects\FullStack\admin-template` (Next.js 16 + MUI 9 admin UI, Boot 4.1.1 backend, Keycloak), `D:\Workspace\projects\Java\architecture_patterns` (15 pattern modules, Boot 3.3.5) |
| **Goal** | Shorten "database connected" → "working app" to minutes: output that plugs into a real frontend, looks hand-written, ships with tests and migrations, in the architecture the team uses. |
| **Principle** | Every item is an **opt-in question** with a suggested answer. With no answer, output is byte-identical to today (golden files unchanged). |
| **Status** | Plan only. No code changes until the product owner accepts it. |

## 0. Summary

| # | Item | Requirement group | Size | Value |
|---|---|---|---|---|
| 1 | admin-template contract (backend module + frontend feature) | CONTRACT-* | L | Highest: full-stack CRUD in one run |
| 2 | Code style presets (records, `from()` mappers, ProblemDetail) | STYLE-* (completes the existing group) | M | Output reads like the team's code |
| 3 | Generated tests and Flyway baseline | TEST-*, MIG-* | M | Removes the first day of manual work |
| 4 | Hexagonal architecture | ARCH-* | L | Most requested after layered |
| 5 | Modular monolith (feature-first + Spring Modulith) | ARCH-* | M | Cheap once ARCH-1 exists |

Order of delivery: **2 → 1 → 3 → ARCH-1 (layout engine) → 5 → 4**. Rationale in §8.

## 1. Baseline: what the generator already does (verified in the templates)

| Concern | Today | admin-template expects | Gap |
|---|---|---|---|
| List shape | `PageResponse<T>(content, page, size, totalElements, totalPages)` (`db-page-response.java.ftl`) | Identical record | **None** |
| Paging params | `page`, `size` (`@PageableDefault(size = 20)`), `sort=prop,dir` | Same, `size` clamped 1..100 | Clamp missing (`spring.data.web.pageable.max-page-size=100`) |
| Sort safety | `SORTABLE` allow-list, 400 on unknown (`db-service.java.ftl:54-110`) | Allow-list, unknown → default sort, `id` tie-breaker | Behaviour differs (reject vs. fall back); tie-breaker missing |
| Filters | Equality on `filterable` params | Named params + `q` free text + `from`/`to` ranges | `q` and ranges missing |
| Errors | Custom map `{status, error, message, details: ["field: msg"]}` (`ApiExceptionHandler.java.ftl:120-129`) | RFC 9457 `{status, title, detail, errors:[{field,message}]}` | **Format change needed** |
| Conflicts | `DataIntegrityViolationException` → 409 | Also `ObjectOptimisticLockingFailureException` → 409 | Add when `@Version` is mapped |
| Security (database flow) | Spec-driven options (`SecurityMapper`): API key, bearer, OAuth2 scopes | Keycloak JWT resource server, `realm_access.roles`, `@PreAuthorize` per permission | New mode |
| Create/Update/Delete | 201 + `Location`, 200, 204 | 201, 200, 204 | None |
| Versioning / audit columns | Not mapped (MAP-2 not done) | `BaseEntity` with `@Version`, `createdAt`, `updatedAt` | Needs MAP-2 |

Conclusion: about half of the admin-template contract is already met. The plan reuses that and does not fork the
database templates.

---

## 2. Item 2: Code style presets (do first)

This completes STYLE-1…6 from the phase doc, with defaults taken from `architecture_patterns/01-layered`, which
also matches admin-template.

### 2.1 Options (stored in `GeneratorConfig.style`, new class)

| Key | Values (suggested first) | Affects |
|---|---|---|
| `dtoKind` | `RECORD` · `CLASS` · `LOMBOK_VALUE` | `db-dto`, `model` |
| `dtoSplit` | `SINGLE` (today: one DTO per table) · `REQUEST_RESPONSE` (`XxxRequest` without id/generated/audit columns, `XxxResponse` with them) | dto, mapper, service, controller |
| `dtoPlacement` | `PACKAGE` (`dto/`) · `GROUPED` (`XxxDtos` holder with nested records, admin-template style) | dto file layout |
| `mapper` | `CLASS` (today: `XxxMapper`) · `STATIC_FROM` (`XxxResponse.from(entity)` + `toEntity` in the service) · `MAPSTRUCT` | mapper, service, pom |
| `errorFormat` | `PROBLEM_DETAIL` (RFC 9457, Spring `ProblemDetail`) · `LEGACY_MAP` (today's shape) | exception handler |
| `serviceKind` | `CLASS` · `INTERFACE_IMPL` (naming pattern `I{Name}Service` / `{Name}ServiceImpl`) | service |
| `responseKind` | `PLAIN` · `ENVELOPE` (`code`, `message`, `payload`; HTTP status is still correct) | controller, handler |
| `entityAccessors` | `EXPLICIT` · `LOMBOK_GETTER_SETTER` (never `@Data`; GEN-7) | entity |
| `javadoc` | `SHORT` · `NONE` · `FULL` | all |

**Default handling:** for profiles that never set `style`, the effective values are the ones that reproduce today's
output (`SINGLE`, `CLASS` mapper, `LEGACY_MAP`). New profiles get the suggested values. `GoldenFilesTest`
therefore stays green, and a second golden set `petstore-style-modern` / `querywise-style-modern` locks in the new
defaults.

### 2.2 Presets (STYLE-6)

- **Modern** (suggested): `RECORD`, `REQUEST_RESPONSE`, `PACKAGE`, `STATIC_FROM`, `PROBLEM_DETAIL`, `CLASS`, `PLAIN`, `EXPLICIT`.
- **admin-template**: Modern + `GROUPED` (§3 sets it automatically).
- **Classic layered**: `CLASS` DTOs, `CLASS` mapper, `INTERFACE_IMPL`, `ENVELOPE`.
- **Today** (keeps old profiles stable).

The UI shows a preset as one question with a diff preview ("changes 14 files"), and an undo.

### 2.3 ProblemDetail handler (target output)

```java
@ExceptionHandler(MethodArgumentNotValidException.class)
ProblemDetail invalid(MethodArgumentNotValidException ex) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
    pd.setTitle("Invalid request");
    pd.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
        .map(fe -> Map.of("field", fe.getField(), "message", String.valueOf(fe.getDefaultMessage()))).toList());
    return pd;
}
```

Also set `spring.mvc.problemdetails.enabled=true` so framework errors (404 on an unknown path, 405) use the same
shape. The **field names in `errors[].field` must be the JSON property names**. They are already the DTO
property names; add a test that a renamed property (MAP edit) shows up renamed in the error.

### 2.4 Requirements

| ID | Requirement |
|---|---|
| STYLE-7 | `GeneratorConfig.style` with the keys above, validated, saved in profiles, exposed to templates as `style.*` and documented in `templates/README.txt`. |
| STYLE-8 | Effective defaults reproduce today's output for profiles without `style`; golden files unchanged. |
| STYLE-9 | `REQUEST_RESPONSE` split: the request omits the PK when it is generated, plus generated/computed columns and audit columns; the response includes them. |
| STYLE-10 | `PROBLEM_DETAIL` errors for every handler branch, including SQLite constraint mapping and the proxy upstream errors. |
| STYLE-11 | Every preset compiles and passes the CRUD IT on PostgreSQL in the Boot matrix (3.5, 4.0, 4.1). |

---

## 3. Item 1: admin-template contract

### 3.1 Key decision: module or standalone?

admin-template's frontend does not render unless the backend serves `/api/me`, `/api/navigation`,
`/api/reference`, `/api/public/app` and `/api/public/messages/{locale}`. Those carry the user, the permission
model, the menu, the translations and the form constraints, i.e. admin-template's whole platform.

| Option | What SpecForge generates | Pros | Cons |
|---|---|---|---|
| **A. Module for the admin-template backend (recommended first)** | Domain packages that drop into `admin-template/backend` (`com.admintemplate.<domain>`) plus Flyway migrations, plus a frontend feature folder | Reuses the platform as it is; small output; real full-stack in one run | Tied to admin-template's internal conventions (versioned, see §3.6) |
| B. Standalone backend compatible with the frontend | A, plus a stub platform (static `/me`, generated `/navigation`, `/reference`, messages) | Works without the admin-template backend | Duplicates a platform that already exists; large; drifts |

**Decision: build A first, as the question "Where will this code live?" → "In my admin-template project (path…)" / "A new standalone Spring Boot project" (today).**
B is an R3 option only if users ask for it.

### 3.2 Backend output (option A), per selected table

Following admin-template's `customer` package exactly:

```
backend/src/main/java/com/admintemplate/<domain>/
  <Entity>.java              extends common.BaseEntity when the table has id + version + created_at + updated_at;
                             otherwise a plain @Entity with the same accessors (no forced BaseEntity, P-USER-CHOOSES)
  <Entity>Repository.java    JpaRepository + JpaSpecificationExecutor
  <Entity>Service.java       SORTS allow-list map, DEFAULT_SORT, PageResponse.of(...), ApiException.notFound(...)
  <Entity>Controller.java    @RequestMapping("/api/<path>"), @PreAuthorize(Authorize.<AREA>_MANAGE) on writes
  <Entity>Dtos.java          nested records <Entity>Request (Bean Validation) / <Entity>Response (+ deletable)
backend/src/main/resources/db/migration/
  V<n>__<domain>_permissions_and_menu.sql   permission + role_permission + menu_item rows
backend/src/main/resources/i18n/ui/en.json, fr.json   ← keys to add (see 3.4)
```

Rules:
- `n` is the next free version, computed from the migration folder of the target project (read-only scan).
  If it can't be read, ask the user.
- **List endpoint:** `GET /api/<path>?q&<filters>&page&size&sort`. `q` is a case-insensitive LIKE (with escaped
  wildcards) over the columns the user marks "searchable" in Map. That's a new column flag, suggested for `varchar`
  columns named `name`, `title`, `email`, `code`, `description`. Range filters `<prop>From` / `<prop>To` are added
  for date/time and numeric filterable columns.
- **Sort behaviour:** an unknown sort field falls back to `DEFAULT_SORT`, and `id` is appended as a tie-breaker.
  This differs from today's 400, but it's what admin-template does; it's only active in this contract.
- **`deletable`:** off by default. If the user turns it on, it's computed with one `EXISTS` query per inbound FK,
  and the doc explains that cost. Otherwise `true` always.
- **Permissions:** `<area>.view` (optional; the default is "any authenticated user can read") and `<area>.manage`.
  The `area` is the camelCase resource name and is editable.

### 3.3 Shared registries: never rewrite hand-written files

admin-template keeps three registries in code that must agree with the data. The app **refuses to start** if
`Permissions.java` and the `permission` table disagree.

| Registry | File | What SpecForge does |
|---|---|---|
| Permission constants | `security/Permissions.java`, `security/Authorize.java` | Generates **INTEGRATION.md** with the exact lines; with UIX-6 (Preview diff) it can show a proposed patch the user applies with one click. Never written silently. |
| Form constraints | `reference/FormConstraints.FORMS` | Same: one line per request record in INTEGRATION.md / patch |
| UI messages | `i18n/ui/en.json`, `fr.json` | JSON merge of new keys only; existing keys are never touched; shown as a diff |

Preview marks these files `PATCH` (a new badge, next to NEW / EXISTS / IDENTICAL), and the write step requires an
explicit tick per patched file.

### 3.4 Frontend output (option A)

Following `features/customers`:

```
frontend/apps/web/src/app/(app)/<route>/page.tsx            thin wrapper, generateMetadata from nav.<key>
frontend/apps/web/src/features/<domain>/api.ts              use<Entities>(filters), use<Entity>(id), useSave<Entity>, useDelete<Entity>
frontend/apps/web/src/features/<domain>/<domain>-page.tsx   AppPage + Card + toolbar (SearchInput, filters, New) + DataTable
frontend/apps/web/src/features/<domain>/<entity>-form-modal.tsx  Modal + Grid + form-fields + useConstraints('<form>') + ApiError.fieldErrors
frontend/apps/web/src/lib/api/types.ts                      PATCH: Entity / EntityInput / EntityFilters interfaces appended
```

Column → UI mapping (the suggestion; editable in a new "Screens" sub-step of API):

| Column | Table cell | Form field |
|---|---|---|
| varchar ≤ 255 | text | `TextField` with `maxLength` from constraints |
| text / varchar > 255 | truncated text | `TextAreaField` |
| boolean | `Chip` yes/no | `SwitchField` |
| enum / check-in-list | colored chip via `useEnumEntry` | `SelectField` |
| numeric / money | right-aligned, `format.ts` | `TextField type=number` |
| date / timestamp | `format.ts` date | date input |
| FK (many-to-one) | referenced label column | `Autocomplete` over the parent list endpoint |
| generated / audit / version | shown (read-only) or hidden | never in the form |

Generated TS files follow the project's lint rules (ESLint 10, no `any`). Milestone C runs `pnpm lint`,
`pnpm typecheck` and the Playwright smoke test against the generated feature in CI.

### 3.5 Security mode for the standalone flow (independent of A/B)

New database-flow security option **"OAuth2 / Keycloak JWT resource server"**:
- issuer URI and audience become properties (`KEYCLOAK_ISSUER_URI`, `JWT_AUDIENCE`);
- a `JwtAuthenticationConverter` maps `realm_access.roles` to `ROLE_*`;
- per-controller `@PreAuthorize("hasRole(...)")` or `hasAuthority('<area>.manage')`, chosen by the user;
- CORS origins as a property, exposing `Content-Disposition`.

This works without admin-template too: it's the common "behind Keycloak" case.

### 3.6 Versioning the contract

admin-template's conventions will change. Store them as a **contract descriptor** inside the template set
(`contracts/admin-template@1.json`): package root, BaseEntity FQN, ApiException factory names, migration folder,
file paths, permission naming. The templates read `contract.*`. A future admin-template change is then a new
descriptor version, not a template rewrite. "Detect" reads the target project's `pom.xml` and
`package.json` and suggests the closest descriptor.

### 3.7 Requirements

| ID | Requirement |
|---|---|
| CONTRACT-1 | Question "Where will this code live?" with standalone (default) and "admin-template project at <path>" (path validated: `backend/pom.xml` with `com.admintemplate`). |
| CONTRACT-2 | Backend module output as in 3.2, compiled inside a checkout of admin-template in CI. |
| CONTRACT-3 | `q` free-text search over user-chosen searchable columns; range filters; fallback sort + `id` tie-breaker. |
| CONTRACT-4 | Flyway migration for permission, role_permission, menu_item with the next free version. |
| CONTRACT-5 | Registries handled as PATCH files with explicit consent; INTEGRATION.md always generated. |
| CONTRACT-6 | Frontend feature output as in 3.4; lint, typecheck and a Playwright list/create/edit/delete smoke test pass. |
| CONTRACT-7 | Keycloak JWT security mode for the standalone flow. |
| CONTRACT-8 | Contract descriptor versioned; templates read `contract.*` only. |

---

## 4. Item 3: Generated tests and Flyway

### 4.1 Tests (question "Generate tests?" → "Yes, with the real database engine (Testcontainers)" suggested · "Yes, H2 only" · "No")

Per entity with a controller:
- `<Entity>ControllerTest`: `@WebMvcTest` with a mocked service. It checks list paging params, 201 + `Location`,
  400 with `errors[].field` for each `@NotNull`/`@Size` column, 404 from the service exception, and 204 on delete.
  The data comes from a generated `<Entity>Fixtures` that builds a valid request from the column types and
  constraints, the same logic `UseCaseIT` already uses for its payloads.
- `<Entity>RepositoryIT`: `@DataJpaTest` +
  `@AutoConfigureTestDatabase(replace = NONE)` + `@Testcontainers` with a `@ServiceConnection` container for the
  source engine. It saves and reads a fixture and runs one specification filter. Skipped on SQLite/H2 (in-process).

Once per project:
- `ApplicationIT`: the context starts against the container, and `ddl-auto=validate` passes. This is the strongest
  single check that the mapping matches the schema.
- For the modular architecture: `ModularStructureTests` (§6).

The generated pom adds `spring-boot-testcontainers` and the engine's `org.testcontainers:<engine>` module, with
versions from the Boot BOM. No version is hard-coded (ADR 002 approach).

### 4.2 Flyway (question "Manage the schema with Flyway?" → "No (today)" · "Yes, baseline the existing database" · "Yes, and include the schema as V1")

The existing database already has the schema, so a migration must never try to create it there.

- **Baseline mode:** `spring.flyway.baseline-on-migrate=true`, `baseline-version=1`, an empty
  `db/migration/` with a README. Future changes start at V2. This is safe on every engine.
- **Schema-as-V1 mode:** `V1__baseline.sql` generated from the snapshot by a new `DdlWriter` per dialect (tables,
  PKs, unique keys, FKs, sequences, identity; no triggers, routines or grants), and `baseline-on-migrate` stays on.
  Existing databases skip V1; fresh environments (tests, a new developer's laptop) get it. The generated
  `RepositoryIT` then runs against a container built **by V1**, which tests the DDL for real.
  Delivered for PostgreSQL, MySQL/MariaDB and H2 first; the other engines get baseline mode with a note until
  their `DdlWriter` passes the round-trip test (§9).

### 4.3 Requirements

| ID | Requirement |
|---|---|
| TEST-1 | Controller slice test per controller, fixtures built from the column constraints. |
| TEST-2 | Repository IT per entity with Testcontainers for the source engine; `ApplicationIT` with `validate`. |
| TEST-3 | Generated tests pass (`mvn verify`) in SpecForge's own CI for the querywise and shop fixtures. |
| MIG-1 | Flyway baseline mode on every engine. |
| MIG-2 | `DdlWriter` for PostgreSQL, MySQL/MariaDB, H2: introspect → DDL → apply on an empty container → re-introspect gives an equivalent snapshot (round-trip). |
| MIG-3 | Engines without a passing round-trip are offered baseline mode only, with the reason shown. |

---

## 5. ARCH-1: package layout engine (prerequisite for 4 and 5)

Today a file's package is `basePackage + layer.subPackage`, the same for every entity. Feature-first and hexagonal
layouts need **per-entity** packages and **more file kinds** (port, adapter, JPA entity separate from the domain
model).

Design:
- `GeneratorConfig.architecture`: `LAYERED` (today) · `MODULAR` · `HEXAGONAL`.
- A Java `PackageLayout` strategy per architecture: `packageOf(fileKind, entity)`. It replaces the direct use of
  `layerPackage(key)` in `Generator`. `LAYERED` delegates to today's code, so the output is byte-identical.
- Templates receive the resolved packages (`pkg`, `entityPackage`, `modelPackage`, … already exist), plus new ones
  (`portInPackage`, `portOutPackage`, `adapterPackage`).
- Each architecture is a **bundled template set** (`templates/arch-hexagonal/`) with its own `manifest.json`. That
  uses the Studio set mechanism that already exists (ADR 004). A user's custom set records `basedOn: "<arch>@<version>"`,
  and the Studio warns when a custom set is used with a different architecture.
- Settings → Architecture is one question with a package-tree preview for one sample entity.

| ID | Requirement |
|---|---|
| ARCH-1 | `PackageLayout` strategy; `LAYERED` byte-identical (golden files). |
| ARCH-2 | Architecture question with a tree preview; incompatible options (e.g. `mapper=MAPSTRUCT` × hexagonal) are explained, not hidden. |

## 6. Item 5: Modular monolith

Following `architecture_patterns/08-modular-monolith`.

```
<base>/<module>/                       module = user-defined group of tables (suggested: FK-connected clusters, editable)
  package-info.java                    @ApplicationModule(displayName = "...", allowedDependencies = {...})
  <Entity>Controller.java              public
  <Module>Api.java                     public interface + nested record DTOs, used by other modules
  internal/<Entity>.java               package-private entity
  internal/<Entity>Repository.java
  internal/<Entity>Service.java        implements <Module>Api
<base>/<App>Application.java           @Modulithic
src/test/.../ModularStructureTests.java  ApplicationModules.of(...).verify() + Documenter
```

Hard parts, decided up front:
- **FKs across modules:** an entity can't hold a JPA relation to another module's `internal` entity. Suggested:
  the FK is mapped as an ID value (`Long customerId`) and the other module's `Api` is called for details. The
  alternative ("allow the relation, declare the dependency") is a per-FK question. `allowedDependencies` is derived
  from the user's answers, and cycles are reported before generation.
- **Module suggestion:** connected components of the FK graph among selected tables, split where a table is
  referenced by more than 3 clusters (a "shared kernel" candidate, asked).
- Dependency: the `spring-modulith-bom` version from bundled metadata, for each Boot line.

| ID | Requirement |
|---|---|
| ARCH-3 | Module assignment step (in Map) with the FK-cluster suggestion, drag between modules, and cycle detection. |
| ARCH-4 | Modular output as above; `ModularStructureTests` passes for the shop and querywise fixtures. |
| ARCH-5 | Cross-module FK policy per FK (ID value suggested / relation + dependency). |

## 7. Item 4: Hexagonal

Following `architecture_patterns/02-hexagonal`, adapted to generated CRUD:

```
<base>/<feature>/domain/model/<Entity>.java          pure Java: private ctor, create(...), rehydrate(...), getters, no JPA
<base>/<feature>/domain/model/<Entity>Id.java        record with validation (single or composite key)
<base>/<feature>/domain/port/in/<Entity>UseCases.java   Create/Read/Update/Delete/List use cases, nested command records
<base>/<feature>/domain/port/out/<Entity>RepositoryPort.java  save, findById, findAll(criteria, page), deleteById, existsById
<base>/<feature>/application/<Entity>Service.java    implements the use cases, @Transactional
<base>/<feature>/adapter/in/web/<Entity>Controller.java   + request/response records
<base>/<feature>/adapter/out/persistence/<Entity>JpaEntity.java, <Entity>SpringDataRepository.java, <Entity>PersistenceAdapter.java (toDomain/toEntity)
```

Decisions:
- **One `<Entity>UseCases` interface per entity** (suggested) instead of one interface per operation; for CRUD,
  five one-method interfaces are noise. "One interface per use case" is an option.
- **Paging crosses the port as SpecForge's own `PageQuery` / `PageResult`**, generated once in
  `shared/domain`, never Spring's `Pageable`, so the domain stays free of Spring. The adapter converts.
- **Relations:** the domain model holds the IDs of other aggregates. Owned children (composition, detected from
  `ON DELETE CASCADE` or the use-case AGGREGATE_* choice) become lists inside the model. This mirrors the
  `@ElementCollection` approach of modules 02/04, but with real tables.
- Filtering is passed as a generated `<Entity>Criteria` record. The adapter builds the JPA `Specification`,
  reusing today's spec code.

| ID | Requirement |
|---|---|
| ARCH-6 | Hexagonal output as above; domain packages have no `jakarta.persistence`, `org.springframework` or `lombok` imports (enforced by a generated ArchUnit test, `ArchitectureTest`). |
| ARCH-7 | CRUD IT (the existing `GeneratedApp` support) passes unchanged against hexagonal output: same HTTP contract as layered. |

## 7a. Status

**P1 delivered (2026-10-18):** STYLE-7 (for `errorFormat` and `dtoSplit`), STYLE-8, STYLE-9, STYLE-10, and STYLE-11 on
PostgreSQL with the current Boot default. The presets are "Modern" (suggested) and "Same as before" (`CURRENT`).

What was built:
- `GeneratorConfig.Style` and `GeneratorConfig.PRESETS`, served by `/api/info` as `stylePresets`.
- Template variables `style.*`, `entity.db.split`, `requestName` and `requestComponents()`.
- New template `db-request.java.ftl` and a branch in `ApiExceptionHandler.java.ftl` for problem details.
- A warning when a custom manifest lacks `db-request`.
- A "Code style" card in Settings.

Tests:
- `DatabaseGoldenFilesTest` (H2, no Docker), with the goldens `shop-h2-default` and `shop-h2-modern`.
- New goldens `petstore-modern` and `petstore-proxy-modern`.
- `ModernStyleIT` on PostgreSQL.

**Deviation:** §2.1 said "new profiles get the suggested values". Instead, every default stays `CURRENT`, and the UI
offers Modern as a marked suggestion that takes one click. The reasons:
1. Silently changing a fresh user's output contradicts P-USER-CHOOSES.
2. The spec-flow goldens would change with no change to the templates.

**P1b (still open):** these values from §2.1: `dtoKind`, `dtoPlacement`, `mapper` (`STATIC_FROM`, `MAPSTRUCT`),
`serviceKind`, `responseKind`, `entityAccessors` and `javadoc`; and the "Classic layered" preset. They don't block
P2 or P3. The admin-template contract needs only `errorFormat` and `dtoSplit`, plus `GROUPED` (which can come with P3).

The Boot matrix (3.5 / 4.0) for the Modern output is not run yet: `-Dspecforge.matrix=true` with a Modern scenario
still has to be added to `BootMatrixIT`.

**P2 delivered (2026-10-18):** CONTRACT-3 and CONTRACT-7 as specified, with these decisions:
- The list behaviour is a `lists` settings group (textSearch, rangeFilters, unknownSort), off by default, and is not
  part of the style presets.
- Search and Range are per-column Map decisions with suggestions.
- Sort fallback is opt-in (`IGNORE`), which answers open decision 2 for now: it is not a default for everyone.
- JWT: `security.databaseAuth=JWT`, `jwtRoles=KEYCLOAK`, `readRole` / `writeRole`.
- It is tested with a test issuer that signs RS256 tokens and serves a JWK set, not a Keycloak container. That
  exercises the resource server the same way, at a fraction of the CI time.
- Also fixed: in the database flow, METHOD access rules made every endpoint public. URL rules are now always
  generated there.

**P3 delivered (2026-10-18):** CONTRACT-1, 2, 4, 5 and 8 for option A (module). What was built:
- `contract.*` settings.
- The `AdminTemplateModule` view model, where all contract logic lives.
- `templates/contracts/admin-template/` with its own manifest, selected by `TemplateManager.contract()`.
- INTEGRATION.md as the PATCH mechanism for now. The one-click apply waits for UIX-6.

Decisions taken while verifying against the real project:
1. **Bean names:** Spring and JPA names carry the module, because admin-template has its own `Customer`,
   `CustomerController` and so on.
2. **Sorting:** `common.Paging` breaks ties on `id`, so tables keyed otherwise build their own sort.
3. **LOB type codes:** source-engine LOB codes are dropped for created tables (`text` / `bytea`).
4. **Least privilege:** the MANAGER grant and the menu entries are opt-in. The menu waits for P4's pages. The
   project's snapshot tests (menu, roles) then stay untouched.
5. **Audit:** `NOT_AUDITED` and the tests that pin the permission and form lists are part of the instructions.
   `AdminTemplateModuleIT` proves the instructions are complete by applying them and running the project's whole
   build.

Not yet: composite keys and use cases in modules (left out with a warning), and EXISTING tables verified against a
real database.

**P4 delivered (2026-10-18):** CONTRACT-6 without the Playwright smoke test. The pages, in the conventions of
`features/customers`, are:
- the list (`DataTable`, `useListParams`, `SearchInput`, edit and delete);
- the create / edit dialog (`Modal`, `form-fields`, `useConstraints`, `ApiError.fieldErrors`, relation selects);
- React Query hooks, types and the route.

The TypeScript expressions per field (state in, request out, cell) are computed in `AdminTemplateModule`, not in
FreeMarker.

How it was verified:
- Against the real project: `tsc --noEmit`, `eslint --max-warnings 0`, and the project's 144 unit tests, including
  `messages.test.ts`, which checks every text key the pages use in en and fr.
- What the verification found:
  1. The frontend's `Permission` type is a registry too; it was added to the instructions.
  2. A single-field form needs a key-typed setter.
  3. Imports must follow the enabled operations.
- **Not done:** the Playwright smoke test. admin-template's e2e runs against the whole stack (`docker compose up`:
  Keycloak, API, database, Next.js). No spec is generated that has not been run. Open item: a nightly job with the
  compose stack.

**P5 delivered (2026-10-18):** TEST-1 and TEST-2 (the schema test is TEST-2's `ApplicationIT`, named
`DatabaseSchemaTests`), TEST-3 via `MigrationsAndTestsIT`, and MIG-1 … MIG-3.

Decisions:
1. **The DDL comes from the snapshot, not from Java types.** The target is the same engine as the source, so
   vendor types and defaults are reproduced exactly.
2. **Round-trip criterion:** byte-identical regenerated Java, stricter than the snapshot normaliser of §9. It holds
   on PostgreSQL, MySQL, MariaDB and H2.
3. **Naming:** the schema test is named `*Tests` and is skipped without Docker. The generated pom has no Failsafe,
   and a fresh checkout must build anywhere.
4. **Flyway schemas:** `spring.flyway.schemas` is set to the source schemas, so Flyway sees an existing database
   that is not in its default schema.
5. **Not covered:** SQL Server, Oracle and Db2 get the baseline only, and SQLite gets no Flyway (MIG-3, with the
   reason shown).

**P6 delivered (2026-10-18):** ARCH-1 and ARCH-2, with FEATURE (package by table) as the first non-layered
layout, so that the engine is exercised end to end before MODULAR and HEXAGONAL build on it.

Template sets are not per architecture yet. LAYERED and FEATURE share the default templates, which now use
`pkgOf` and the shared package variables; MODULAR and HEXAGONAL add their own files. Cross-package access
required one visible change: `XxxService.sorted` is public outside LAYERED.

**P7 delivered (2026-10-18):** ARCH-3, ARCH-4, and ARCH-5 with the suggested policy (a cross-module foreign key
becomes an id).

Not built:
- Drag and drop between modules: Map has a Module field per table instead.
- Cycle reporting before generation: the id policy cannot create a cycle, and `ModularStructureTests` reports any.
- The per-FK "relation + declared dependency" option.

The Modulith release per Boot line was read from each release's own POM (spring-modulith-core declares the Boot it
is built against).

**P8 delivered (2026-10-18):** ARCH-6 and ARCH-7 for single-column keys. Decisions:
1. **Overlay, not a fork:** the hexagonal set is an overlay manifest that removes the layered per-table templates
   and adds its own, so global templates added later reach it.
2. **Port records:** they live in `domain.port.in`. Bean Validation is allowed in the domain; Spring, JPA, Hibernate
   and Lombok are not.
3. **PATCH:** it goes through the application service, so the web adapter stays thin.
4. **Ports:** one `UseCases` interface per table, as §7 suggested.

Not yet: composite keys, use cases, controller tests and `<Entity>Id` records (keys are their Java type).

**All of P1 … P8 delivered.** The open items are P1b (more style options), the Playwright smoke test (P4), and the
items listed per phase above.

**Queued after P8 (product owner, 2026-10-18):** new source families, each a phase of its own:
- MongoDB (INT-4).
- Elasticsearch (INT-5).
- Cassandra (new; keyspaces and tables from `system_schema`, close to relational).
- Redis (new). It has no schema, so it is partial: key patterns and value shapes are chosen by the user, and
  SpecForge suggests them from a sample only on opt-in (P4).

Every one of them needs its own phase doc before code.

## 8. Delivery order and milestones

| # | Content | Done when |
|---|---|---|
| P1 | STYLE-7…11 (item 2) | Old golden files unchanged; new `*-style-modern` goldens; matrix green |
| P2 | CONTRACT-3, CONTRACT-7 (search, sort fallback, Keycloak). These are useful standalone | CRUD IT covers `q`, ranges, sort fallback; JWT IT with a Keycloak container |
| P3 | CONTRACT-1, 2, 4, 5, 8 (backend module) | Generated module compiles and its migration applies inside an admin-template checkout in CI |
| P4 | CONTRACT-6 (frontend feature) | `pnpm lint`, `typecheck` and the Playwright smoke test pass on the generated feature |
| P5 | TEST-1…3, MIG-1…3 | Generated tests pass for every fixture; DDL round-trip on 3 engines |
| P6 | ARCH-1, ARCH-2 | Layout engine with LAYERED byte-identical |
| P7 | ARCH-3…5 (modular) | `ModularStructureTests` green on the fixtures |
| P8 | ARCH-6, ARCH-7 (hexagonal) | Same CRUD IT green; ArchUnit green |

Why this order:
- Style comes first because the contract and architectures all render through the style variables, and building
  them on today's DTO shape would mean doing it twice.
- Search, sort and security come before the module because they are independent wins for every user.
- Tests come before architectures because the generated tests are what proves an architecture's output works.
- Modular comes before hexagonal because it reuses the layered templates with a new layout, which proves ARCH-1
  cheaply.

## 9. Verification strategy

- **Golden files:** one set per preset × architecture on petstore + querywise (8 sets max). They change only
  through `-DupdateGolden=true` with a CHANGELOG entry.
- **Behavioural equivalence:** every architecture and style passes the **same** `GeneratedApp` CRUD scenario. The
  HTTP contract is independent of the internal structure, and that's the test that guarantees it.
- **External repos in CI:** admin-template is cloned at a pinned commit in a nightly job (not the fast stage). The
  generated module is copied in, then `mvn -pl backend verify` and `pnpm -C frontend lint typecheck` run, plus
  Playwright against `docker compose up`. The commit pin goes with the contract descriptor version.
- **DDL round-trip (MIG-2):** snapshot A → DDL → fresh container → snapshot B; compare A and B with a
  normaliser (ignoring names the engine generates, such as unnamed constraints).
- **Secrets:** INTEGRATION.md and the migrations never contain connection details (secret-leak test extended).

## 10. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| admin-template internals change | Generated module stops compiling | Versioned contract descriptor + pinned nightly CI; a mismatch is reported as "descriptor @1 vs project", not as a silent failure |
| Option combinations explode (presets × architectures × flows) | Untested output | Allowed-combination table in `GeneratorConfig.validate()`; the matrix tests every allowed combination on one fixture, and the full fixture set only for the suggested ones |
| DDL generation is subtly wrong | Broken fresh environments | Round-trip test per engine; engines without it get baseline mode only (MIG-3) |
| Patching hand-written files | User code damaged | Never automatic: PATCH badge, diff, explicit consent, backup (existing "back up" write option) |
| Hexagonal output too heavy for CRUD | Users reject it | One `UseCases` interface per entity by default; tree preview before choosing |
| Template sets diverge from each other | Fixes applied in one set only | Shared partials (STU-11) for entity/DTO/validation fragments; one CRUD scenario for all sets |

## 11. Open decisions for the product owner

1. **CONTRACT option B** (standalone platform stub): build later, or never?
2. **Sort fallback vs. 400:** only in the admin-template contract (as proposed), or the new default for everyone?
3. **Frontend generation scope:** list + form modal only (proposed), or also detail pages (`orders/[id]` style)
   and CSV export?
4. **MapStruct** as a mapper option: worth the annotation-processor setup in generated projects?
5. **Other `architecture_patterns` styles** (Clean, DDD, CQRS): proposed out of scope, because they need business
   decisions a schema doesn't contain. Confirm.
