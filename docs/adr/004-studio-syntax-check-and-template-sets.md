# ADR 004: Studio syntax check with the JDK parser; template sets as folders

- **Status:** Accepted (2026-10-17)
- **Deviates from:** STU-5 ("rendered Java syntax-checked (JavaParser)") and §8, which lists JavaParser as a new
  dependency for it
- **Keeps:** STU-5's intent (syntax errors of rendered Java shown before generation), STU-7 (named sets under
  `~/.specforge/templates/<set>/`, selectable per profile, ZIP export and import, existing `templatesDir` folders
  keep working)

## Context

The Studio checks the Java a template renders, on every preview (debounced while typing). JavaParser would add
about 1.5 MB and its own Java language level to keep current. SpecForge already runs on a JDK in practice
(Java 17+, and generated projects are compiled with the same JDK by Maven). The JDK ships a parser in
`jdk.compiler` (`JavacTask.parse()`), which parses exactly the language level the JDK supports, without type
checking and without a classpath.

For sets, profiles already carry `templatesDir`, the folder that overrides bundled templates file by file.

## Decision

- **Syntax check:** `JavacTask.parse()` on the rendered source; only `ERROR` diagnostics of the parse phase are
  reported with line and column. On a JRE without `jdk.compiler` the check says "unavailable", never fails.
- **Sets:** a set is a folder `~/.specforge/templates/<set>/` holding only the customized templates (plus
  `.studio.json`, the hash of the default each one started from). "Use for generation" sets the profile's
  `templatesDir` to that folder. Any other folder chosen as `templatesDir` keeps working as before.
- **Type checking** stays with "Verify by compiling" (STU-13), which runs Maven on the whole generated project and
  maps compiler errors back to the template that produced the file.

## Consequences

- No new dependency for STU-5; parse errors match `javac` exactly.
- A JRE-only installation loses the inline syntax check; "Verify by compiling" needs Maven on the PATH (and the
  network for dependencies on first use).
- A set folder is plain files: it can be versioned with Git or shared as a ZIP.
