# Third-party notices

SpecForge bundles the libraries below in `specforge.jar`. Every library added to SpecForge is listed here with
its license (rule 8 of `docs/prompts/phase-database-first.md` §0). Versions are those of the current build.

## Bundled in the JAR

| Library | Purpose | License |
|---|---|---|
| swagger-parser (`io.swagger.parser.v3`) and its dependencies (swagger-core, Jackson 2) | Reading Swagger/OpenAPI specs | Apache-2.0 |
| FreeMarker (`org.freemarker:freemarker`) | Templates | Apache-2.0 |
| SLF4J (`org.slf4j:slf4j-nop`) | Silences library logging | MIT |
| PostgreSQL JDBC driver (`org.postgresql:postgresql`) | Connecting to PostgreSQL | BSD-2-Clause |
| MySQL Connector/J (`com.mysql:mysql-connector-j`, without its optional protobuf dependency) | Connecting to MySQL | GPL-2.0 with the Universal FOSS Exception |
| MariaDB Connector/J (`org.mariadb.jdbc:mariadb-java-client`) | Connecting to MariaDB | LGPL-2.1 |
| Microsoft JDBC Driver for SQL Server (`com.microsoft.sqlserver:mssql-jdbc`) | Connecting to SQL Server | MIT |
| Oracle JDBC (`com.oracle.database.jdbc:ojdbc17`, `com.oracle.database.nls:orai18n`) | Connecting to Oracle | Oracle Free Use Terms and Conditions |
| IBM Data Server Driver for JDBC (`com.ibm.db2:jcc`) | Connecting to Db2 | IBM International Program License |
| SQLite JDBC (`org.xerial:sqlite-jdbc`, with native libraries) | Reading SQLite files | Apache-2.0 |
| H2 (`com.h2database:h2`) | Reading H2 databases | MPL-2.0 or EPL-1.0 |
| Evo Inflector (`org.atteo:evo-inflector`) | Singular and plural names | Apache-2.0 |
| highlight.js (`web/vendor/highlight.min.js`) | Code highlighting in the UI | BSD-3-Clause |
| CodeMirror 5.65.18 (`web/vendor/codemirror.js`, `codemirror.css`; core, modes clike / xml / properties / javascript, addons overlay, matchbrackets, search, dialog) | Studio editor | MIT |

Driver versions follow the Spring Boot BOM (VER-4, ADR 002) and are listed in the app under Drivers. **Open
question Q1 for the product owner before release:** confirm the redistribution conditions of MySQL Connector/J
(GPL with FOSS exception), Oracle `ojdbc` (Free Use Terms) and IBM `jcc` (IPLA). MariaDB Connector/J can also
connect to MySQL if Connector/J cannot be shipped.

## Data bundled in the JAR

| Data | Source | License |
|---|---|---|
| `spring/metadata.json` | Spring Boot versions and coordinates from start.spring.io and Maven Central (facts, not code) | n/a |

## Test-only (not distributed)

| Item | Source | License |
|---|---|---|
| JUnit, AssertJ, Testcontainers (PostgreSQL, MySQL, MariaDB, SQL Server, Oracle Free, Db2 modules) | Test libraries | EPL-2.0, Apache-2.0, MIT |
| `src/test/resources/specs/gitea.json` | Gitea API definition, https://gitea.com/swagger.v1.json | MIT |
| `src/test/resources/fixtures/prior-art/` | Rebuilt from the product owner's own earlier project | Owner's |
| `src/test/resources/fixtures/querywise/` | querywise `pack/postgres/V001__schema.sql` (product owner's project) | MIT |
