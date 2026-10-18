# ADR 001: Relational introspection engine (SPIKE-1)

- **Status:** Accepted (2026-10-17)
- **Decides:** D13 in `docs/prompts/phase-database-first.md`
- **Requirements:** INT-1, INT-3, INT-7, S4

## Context

Relational metadata could come from SchemaCrawler (a library that already reads catalogs across most engines) or
from `java.sql.DatabaseMetaData` plus one `Dialect` class per engine for what JDBC misses. SPIKE-1 compared both
against the same PostgreSQL 16 container holding the prior-art fixture (`administration`, 10 tables) and a
generated `bulk` schema (2,000 tables, each with an identity key, a unique column, `numeric`, `timestamptz`,
`jsonb` and a foreign key to the previous table): 2,010 tables, 12,052 columns.

## Measurements

| | `DatabaseMetaData` bulk calls | SchemaCrawler 17.15.7 + PostgreSQL plugin, "standard" level |
|---|---|---|
| Time, warm (2 runs) | 957 ms, 729 ms | ~30,900 ms |
| Tables / columns / primary keys / foreign keys | 2,010 / 12,052 / all / all | same |
| Vendor type names (`jsonb`, `bigserial`, `timestamptz`) | yes | yes |
| Identity / auto-increment, `nextval(...)` defaults | yes | yes |
| Sequences | yes, one `pg_class` query | not loaded at this level (needs extra configuration) |
| Unique keys | no: `getIndexInfo` needs a table name, so one catalog query in the dialect | yes |
| Added footprint | none | ~4 MB; Jackson 3 (`tools.jackson`) and `jackson-annotations` 2.22 next to swagger-parser's Jackson 2; verbose `java.util.logging` output |
| License | n/a | EPL-2.0 / LGPL-3.0 / GPL-3.0 (choice): acceptable |

## Exit criteria (from the phase document)

1. Reads tables, columns, keys, indexes and sequences on the fixtures: **partly** (sequences need extra
   configuration; other engines not tried, the time box was spent on the decisive measurement).
2. 2,000 tables within S4 (< 60 s): **passes, but with little margin** (31 s on a fast local machine).
3. Works with a read-only connection: yes.
4. Footprint and dependency conflicts acceptable: **no** (a second Jackson line and a newer
   `jackson-annotations` beside swagger-parser's).

## Decision

Use `DatabaseMetaData` with bulk calls (schema-wide, never per table) as the baseline, plus one `Dialect` per
engine for what JDBC misses or gets wrong (unique keys, sequences, identity, view definitions, enums, routines).
SchemaCrawler is not added.

## Consequences

- About 30× faster introspection, comfortably inside S4 even for much larger schemas.
- No new runtime dependency or Jackson conflict.
- SpecForge owns the per-engine catalog queries. Each dialect is covered by a live test against its engine
  (milestones 1 and 2), and the querywise reference shows which catalog views to use.
