# ADR 002: Bundled driver versions without importing the Spring Boot BOM

- **Status:** Accepted (2026-10-17)
- **Deviates from:** VER-4 as written ("import `spring-boot-dependencies` in `dependencyManagement`, versions only")
- **Keeps:** VER-4's intent, that SpecForge connects with the same driver versions generated applications use

## Context

Importing the Spring Boot 4.1.1 BOM into SpecForge's `dependencyManagement` changed more than the drivers. The
runtime dependency list before and after the import showed swagger-parser's transitive dependencies moving
across major versions: `jakarta.xml.bind-api` 2.3.3 → 4.0.5 (a package rename from `javax.xml.bind`),
`jakarta.validation-api` 2.0.2 → 3.1.1, `jakarta.activation-api` 1.2.2 → 2.1.4, and Jackson 2.18 → 2.21. Any of
these can break spec parsing at runtime, and pinning each of them back by hand would be fragile.

## Decision

- Do not import the BOM.
- Pin each bundled driver with a version property named like the BOM's (`postgresql.version`, …).
- The metadata snapshot tool records the default line's BOM driver versions in `spring/metadata.json`
  (`bomDriverVersions`).
- `BundledDriverVersionsTest` fails when a pinned version differs from the recorded BOM version.

## Consequences

- Driver alignment is enforced by a test instead of by Maven; refreshing the metadata snapshot can make the test
  fail, which is the reminder to update the pinned versions.
- swagger-parser keeps the dependency versions it was built and tested with.
