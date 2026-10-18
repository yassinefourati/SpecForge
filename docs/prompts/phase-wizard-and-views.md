# Phase: Advanced wizard and database views

| | |
|---|---|
| **Audience** | The engineer (or AI coding agent) implementing this phase; the product owner reviews each milestone |
| **Builds on** | `phase-database-first.md` (UIX-1…12, INT-2, OPS-7, SEL-1); ADR 001, 003, 004 |
| **Principle** | The user picks every option; SpecForge only suggests (P-USER-CHOOSES). Every suggestion is visible, explained and reversible. |
| **Status** | Plan only. No code changes until the product owner accepts it. |

## 1. Why

The current UI is a step bar over panels (`index.html` sections `step1`, `stepSelect`, `stepMap`, `stepApi`,
`step2`, `step3`; `goStep` / `enableSteps` in `app.js:369-420`). It works, but:

1. Steps do not show their state: you cannot tell what is done, what has errors, or what changed since you left.
2. There is no persistent picture of "what will be generated" while you decide (UIX-4 still open).
3. Decisions are scattered: big forms, little explanation of consequences (UIX-9 still open).
4. Going back and changing something upstream (e.g. untick a table) silently invalidates downstream choices.
5. Large schemas make Select and Map slow (UIX-5 still open).
6. **Views are dropped entirely:** `JdbcConnector.readTables` asks only for `TABLE` / `BASE TABLE` /
   `PARTITIONED TABLE`, and `readColumns` skips view columns (`JdbcConnector.java:201`). A schema whose
   reporting layer lives in views produces nothing for it.

## 2. Target experience (the wizard)

### 2.1 Layout: three columns

```
┌──────────────┬──────────────────────────────────────────┬────────────────────┐
│ Step rail    │ Step content                              │ Live summary       │
│              │                                           │                    │
│ ✓ Source     │  Question title                           │ Boot 4.1.1 · Java17│
│ ✓ Select  12 │  One-line explanation                     │ 14 entities        │
│ ● Map     ⚠2 │  ┌ option cards / table / editor ┐        │  3 read-only (view)│
│ ○ API        │  └──────────────────────────────┘        │ 15 controllers     │
│ ○ Settings   │                                           │ 71 endpoints       │
│ ○ Preview    │  [Back]            [Skip to preview] [Next]│ 96 files           │
│              │                                           │ ⚠ 2 warnings  ›    │
└──────────────┴──────────────────────────────────────────┴────────────────────┘
```

- **Step rail** (replaces the horizontal bar; collapses to a top bar under 900 px, to a "Step 3 of 6 ▾" menu
  under 600 px). Each step shows: state icon, count badge (tables selected, errors), and a "changed" dot.
- **Live summary** (UIX-4): debounced 400 ms after any change, from `POST /api/generate/preview` with a new
  `summaryOnly=true` flag so it does not render file bodies. Each line is a link that jumps to where it is decided.
- **Footer actions**: Back, Next (primary), and "Skip to preview" once the minimum is valid. `Ctrl+Enter` = Next.

### 2.2 Step states (UIX-3, extended)

| State | Meaning | Rail look |
|---|---|---|
| `locked` | A previous step is not valid yet | grey, not clickable, tooltip says what is missing |
| `todo` | Reachable, not visited | hollow circle |
| `current` | Shown now | filled, `aria-current="step"` |
| `done` | Visited and valid | check |
| `error` | Has blocking problems | red, count |
| `stale` | An upstream change affected it | amber dot, "Review changes" link |

State is computed by one pure function `stepStates(state) -> {stepId: {status, count, reasons[]}}`, unit-tested,
called after every mutation. No step decides its own state ad hoc.

### 2.3 Change propagation (the "stale" mechanism)

When the user changes something upstream, downstream choices are **kept, never silently dropped**:

- Untick a table → its mapping edits and controllers are parked (not deleted). The API step shows
  "2 controllers refer to `order_item`, which is no longer selected — Remove / Re-select the table".
- Rename a class in Map → controllers and use-cases follow the rename (they reference by table id, not by name).
- Re-read the database (schema changed) → a diff dialog: added / removed / changed tables and columns, with
  each user override marked "still applies" / "orphaned". The user accepts per item. (This pulls PLAN-3 forward
  in a minimal form.)

Implementation: overrides are already keyed by table/column identity; add a `parked` bucket in the plan and a
`reconcile(snapshot, plan) -> {plan, conflicts[]}` function on the server (`plan/GenerationPlan.java`), tested with
golden plans.

### 2.4 One question style (UIX-9)

Every decision is rendered by one component, `question({id, title, help, options[], value, recommended})`:

- Title as a question ("How should IDs be generated for `orders`?").
- Options as cards: label, one-line consequence, and a **Suggested** chip on the suggestion with its reason
  ("because `id` is `bigserial`"). The suggestion is pre-selected but never hidden; the user's pick shows **Yours**
  and a ↺ reset.
- "Why?" expander with the rule that produced the suggestion (from `SuggestionEngine`, which should return a
  `reason` string with every suggestion — small server change).

### 2.5 Per-step improvements

**Source**
- Engine tiles with recent connections first ("Last used 2 days ago · 48 tables").
- Connection test shows a checklist: reachable → authenticated → read-only guard applied → schemas visible,
  each with a fix hint on failure (UIX-8).
- "Try with a sample database" (UIX-10).

**Select**
- Virtualized tree (UIX-5): schema → Tables / **Views** / Sequences, 10,000 rows smooth. Render only visible rows
  (fixed row height, a single scroll container, ~40 lines of plain JS; no library needed).
- Filter chips: `has PK`, `no PK`, `join table`, `view`, `empty`, `has comment`; text search with `/`.
- Pattern select (SEL-2): "include `app_*`, exclude `*_audit`" with a live match count.
- "With related tables": selecting `orders` offers to add `customers`, `order_items` (via FKs), with a mini graph.
- Detail panel: columns, keys, relations, row estimate, and for views the definition (see §3).

**Map**
- Split view: entity list left, editor right; a **relationship diagram** tab (inline SVG, boxes + FK lines,
  draggable, only selected tables, click a box to edit). No library: a simple layered layout by FK depth.
- Bulk edits: "Apply to all tables: ID strategy / type for `timestamptz` / exclude columns named `created_by`".
- Inline validation: duplicate class names, Java keywords, path collisions — shown on the field and counted on the rail.

**API**
- Endpoint matrix: rows = controllers, columns = list / get / create / update / patch / delete / search, toggles in
  cells, column header toggles all. Views show write columns disabled with a "make writable…" action (§3.4).
- Use-case builder as a guided sub-wizard (pick parent → pick children → pick operations → name) with a preview of
  the resulting endpoints and request JSON.

**Settings** (UIX-2): left section nav, scroll-spy, per-section "Reset", "differs from default" markers.

**Preview** (UIX-6): NEW / EXISTS / IDENTICAL badges, status filter, side-by-side diff, "Open folder".

### 2.6 Wizard persistence

- The whole wizard state (source ref, selection, plan, settings, current step) autosaves to
  `~/.specforge/sessions/<id>.json` (server side, never the password) every change, debounced 1 s.
- On start: "Resume *shop on localhost* — Map step, 2 hours ago" / "Start new".
- Undo / redo (`Ctrl+Z` / `Ctrl+Shift+Z`) over plan mutations: keep a bounded stack (100) of plan patches.

### 2.7 Front-end structure

`app.js` is 1,933 lines in one file. Before adding the above, split it (no build step, keep plain JS) into ES
modules served from `web/js/`: `state.js` (store + subscribe), `steps.js` (stepStates, rail), `components/`
(question, vlist, diagram, matrix, toast), and one file per step. Load with `<script type="module">`.
The store is a single object with `update(fn)` that notifies subscribers — enough for the rail, summary and
autosave to react without each step calling them.

## 3. Database views

### 3.1 Introspection (INT-2, views part)

- `readTables` also requests `VIEW` (and `MATERIALIZED VIEW` where the driver reports it) and records
  `kind: TABLE | VIEW | MATERIALIZED_VIEW`.
- `readColumns` keeps view columns (remove the skip at `JdbcConnector.java:201` for views).
- New dialect method `readViews(Connection, Snapshot, Set<String> schemas)` (already sketched in the phase doc) for
  what `DatabaseMetaData` lacks, in bulk:

| Engine | Definition | Updatable | Materialized |
|---|---|---|---|
| PostgreSQL | `pg_views.definition` | `information_schema.views.is_updatable` / `is_insertable_into` | `pg_matviews` |
| MySQL / MariaDB | `information_schema.VIEWS.VIEW_DEFINITION` | `IS_UPDATABLE` | — |
| SQL Server | `sys.sql_modules.definition` | `information_schema.VIEWS` (approximate) | indexed views: `sys.indexes` on view |
| Oracle | `ALL_VIEWS.TEXT` | `ALL_UPDATABLE_COLUMNS` | `ALL_MVIEWS` |
| Db2 | `SYSCAT.VIEWS.TEXT` | `SYSCAT.VIEWS.READONLY` | `SYSCAT.TABLES TYPE='S'` |
| SQLite | `sqlite_master.sql` | never (unless INSTEAD OF triggers; report as "triggers present") | — |
| H2 | `INFORMATION_SCHEMA.VIEWS.VIEW_DEFINITION` | `IS_UPDATABLE` | — |

  A definition the login may not read (permissions) is `null` with a warning, never a failure
  (`INTROSPECTION_PARTIAL`). Still catalog queries only; no `SELECT` on the view itself.

### 3.2 Contract change (`snapshot.schema.json`)

Add to `table`: `kind` (enum, default `TABLE` so old snapshots stay valid), `definition` (string|null),
`updatable` (boolean|null), `materialized` (boolean). Bump the schema version; `ContractsTest` covers both the old
and new shape.

### 3.3 Suggestions for views

Views have no primary key. The mapper must not invent one silently:

1. If a unique index exists (materialized/indexed views) → suggest it as the ID.
2. Else, if a column named `id` or `<view>_id` is non-null → suggest it, with reason "assumed unique — verify".
3. Else, ask (question style): "Which columns identify a row of `v_order_totals`?" with options:
   pick columns / **No identity: list and search only** (suggested) / composite of all columns (warned).

Generated code for a view (OPS-7):
- Entity with `@Immutable` (Hibernate) and `@Table(name = "<view>")`; `@Id` (or `@EmbeddedId`) from the choice;
  with "no identity", a `@Subselect`-free approach: `@Id` on a synthetic choice is not allowed, so generate a
  **projection repository** (`JdbcClient` / interface projection) instead of a JPA entity.
- Repository extends a read-only base (`Repository<T, ID>` with `findAll(Pageable)`, `findById`, specification
  search) — no `save` / `delete` exposed.
- Controller from `controllerView.ftl`: `GET /x` (paged, filter, sort), `GET /x/{id}` when an identity exists.
- `ddl-auto=validate` works for views on Hibernate; the matrix test confirms per engine.
- Materialized views: optionally a `POST /x/refresh` (off by default, PostgreSQL / Oracle only, clearly warned:
  it is the one write SpecForge would generate against a view and it runs in *the generated app*, not in SpecForge).

### 3.4 Writable views

Writes on a view are generated only if the user turns them on per view, after a confirmation that shows
the engine's `updatable` answer ("PostgreSQL says this view is auto-updatable" / "unknown — writes may fail at
runtime"). Then the view maps like a table and `@Immutable` is dropped.

### 3.5 UI for views

- Select tree: a **Views** group per schema, an eye icon, `materialized` / `updatable` tags. Views are
  **not** selected by default (suggestion), unlike tables.
- Detail panel: columns plus the SQL definition in read-only CodeMirror (already bundled), and "depends on"
  tables parsed loosely from the definition (best effort, labelled as such).
- Map and API treat views as read-only entities with the identity question at the top.

### 3.6 Open decision: a data viewer?

"Viewer" could also mean browsing *rows* (a sample of data per table/view). That breaks today's rule
"catalog queries only" (RO-1, SEC-*): reading rows means data leaves the database into the UI and possibly
into logs. If wanted, the safe shape is: off by default, per-connection opt-in, `SELECT … FETCH FIRST 20 ROWS`
inside the same read-only rolled-back transaction, never stored in snapshots or sessions, values redacted for
columns whose names match the secret patterns in `Redactor`. **Product owner to decide** before any work.

## 4. Requirements

| ID | Requirement |
|---|---|
| WIZ-1 | Three-column layout (rail, content, summary), responsive at 360 / 600 / 900 px. |
| WIZ-2 | `stepStates` pure function drives every step state (locked, todo, current, done, error, stale). |
| WIZ-3 | Live summary with `summaryOnly` preview, debounced, each line links to its decision. |
| WIZ-4 | Upstream changes park downstream choices; `reconcile` reports conflicts; nothing is lost silently. |
| WIZ-5 | Re-read diff dialog with per-item accept. |
| WIZ-6 | `question` component used for every decision; suggestions carry a `reason`. |
| WIZ-7 | Virtualized tree and filter chips in Select; pattern select; "with related tables". |
| WIZ-8 | Relationship diagram in Map; bulk edits; inline validation counted on the rail. |
| WIZ-9 | Endpoint matrix and use-case sub-wizard in API. |
| WIZ-10 | Session autosave, resume, undo/redo. |
| WIZ-11 | `app.js` split into ES modules with a single store, before WIZ-1…10. |
| VIEW-1 | Views and materialized views introspected on all 8 engines with kind, columns, definition, updatable. |
| VIEW-2 | Snapshot contract extended backward-compatibly. |
| VIEW-3 | Identity question for views; no silent invented keys. |
| VIEW-4 | Read-only generation (`@Immutable`, read-only repository, `controllerView.ftl`); projection path when no identity. |
| VIEW-5 | Opt-in writable views with the engine's updatable answer shown. |
| VIEW-6 | Views in the Select tree, detail panel with definition, unselected by default. |

## 5. Milestones

| # | Content | Done when |
|---|---|---|
| A | WIZ-11 refactor only, no behaviour change | Every existing IT and a manual click-through pass unchanged |
| B | VIEW-1, VIEW-2 | `EngineMatrixIT` fixtures gain 2 views + 1 materialized view each; snapshots contain them on all engines |
| C | VIEW-3, VIEW-4, VIEW-6 | Generated app serves `GET` on views, starts with `ddl-auto=validate` on PostgreSQL, MySQL, Oracle; golden files added |
| D | WIZ-1, WIZ-2, WIZ-3, WIZ-6 | Rail states unit-tested; summary updates < 500 ms on the querywise fixture |
| E | WIZ-4, WIZ-5, WIZ-10 | Reconcile golden tests (rename, drop, add column); resume after restart |
| F | WIZ-7, WIZ-8, WIZ-9, VIEW-5 | 10,000-table synthetic snapshot scrolls smoothly; axe-core passes every step |

Order rationale: A first because every later UI item needs the store; B/C before the big UI work because
views add a new object kind the rail, tree and summary must already account for.

## 6. Tests

- Unit: `stepStates`, `reconcile`, view suggestion rules (`SuggestionRulesTest`), dialect parsing of `updatable`.
- Contract: old and new snapshots validate.
- Integration: per-engine view fixtures in `src/test/resources/fixtures/engines/shop-*.sql`
  (`v_order_totals` with aggregate = not updatable, `v_active_customers` simple filter = updatable, one
  materialized view where supported); generated app `GET`s return rows.
- UI: Playwright flow per source, axe-core per step, keyboard-only run.
- Security: `ReadOnlyProbe` still passes; secret-leak test covers session files.

## 7. Risks

| Risk | Mitigation |
|---|---|
| Hibernate `validate` on views differs per engine | Matrix test in milestone C; fall back to projection path where it fails |
| View definitions are huge or unreadable | Store truncated (64 KB) with a note; `null` + warning when not permitted |
| Refactor (A) regresses existing flows | No behaviour change allowed in A; ITs plus click-through checklist |
| Reconcile complexity | Identity-keyed overrides only; conflicts always surfaced to the user, never auto-resolved |
