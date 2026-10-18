package io.specforge.gen;

import io.specforge.config.GeneratorConfig;
import io.specforge.ddl.DdlWriter;
import io.specforge.model.Model.ApiModel;
import io.specforge.model.Model.Msg;
import io.specforge.snapshot.Snapshot;
import io.specforge.source.SourceKind;
import io.specforge.spring.VersionUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the generated project gets for MIG-1 … MIG-3 and TEST-1 / TEST-2, from the user's choices and what the source
 * allows: the effective Flyway mode (SCHEMA falls back to BASELINE, with the reason, when the DDL cannot be written),
 * the engine's Flyway module, and how the application test gets its database (a Testcontainers container of the
 * source engine and version, or H2 in memory). Exposed to templates as {@code checks}.
 */
public final class ProjectChecks {

    /** NONE | BASELINE | SCHEMA, after fallbacks */
    public String flyway = "NONE";
    /** V1__baseline.sql, when SCHEMA */
    public String ddl = "";
    /** the Flyway module of the engine, "group:artifact", "" when core covers it */
    public String flywayModule = "";
    /** schemas Flyway checks for an existing database (and keeps its history in), "" = the connection's default */
    public String flywaySchemas = "";
    public boolean controllerTests;
    public boolean applicationTest;
    /** the application test runs on H2 in memory instead of a container */
    public boolean inMemory;
    /** Testcontainers: container class (simple and qualified name), image, and its Maven artifacts */
    public String containerClass = "";
    public String containerImport = "";
    public String containerImage = "";
    public final List<String> testArtifacts = new ArrayList<>();
    /** modular architecture: the Spring Modulith BOM version for the Boot line, "" otherwise (ARCH-4) */
    public String modulithVersion = "";
    /** Boot 4 moved the web test slice to its own module */
    public String webMvcTestImport = "";
    public String autoConfigureMockMvcImport = "";

    private static final Pattern VERSION = Pattern.compile("(\\d+)(?:\\.(\\d+))?");

    static ProjectChecks of(ApiModel api, GeneratorConfig cfg, List<Msg> messages) {
        ProjectChecks c = new ProjectChecks();
        int boot = VersionUtil.major(cfg.options.springBootVersion);
        c.webMvcTestImport = boot >= 4 ? "org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest"
            : "org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest";
        c.autoConfigureMockMvcImport = boot >= 4 ? "org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc"
            : "org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc";
        boolean db = "DATABASE".equals(api.source.kind) && !cfg.contract().adminTemplate();
        c.controllerTests = db && cfg.tests != null && cfg.tests.controllers;
        if (!db) return c;
        if (!api.modules.isEmpty()) c.modulithVersion = modulith(cfg.options.springBootVersion, messages);

        SourceKind kind = SourceKind.of(api.source.engine);
        GeneratorConfig.Migrations mig = cfg.migrations == null ? new GeneratorConfig.Migrations() : cfg.migrations;
        if (mig.enabled()) {
            c.flywayModule = switch (kind) {
                case POSTGRESQL -> "org.flywaydb:flyway-database-postgresql";
                case MYSQL, MARIADB -> "org.flywaydb:flyway-mysql";
                case SQLSERVER -> "org.flywaydb:flyway-sqlserver";
                case ORACLE -> "org.flywaydb:flyway-database-oracle";
                case DB2 -> "org.flywaydb:flyway-database-db2";
                default -> "";
            };
            if (kind == SourceKind.SQLITE) {
                messages.add(new Msg("WARN", "Flyway is not set up for SQLite; the project manages its schema as before."));
            } else {
                c.flyway = "BASELINE";
                if (kind == SourceKind.POSTGRESQL || kind == SourceKind.H2 || kind == SourceKind.SQLSERVER || kind == SourceKind.DB2)
                    c.flywaySchemas = String.join(",", api.source.schemas);
            }
            if (mig.schema() && c.flyway.equals("BASELINE")) {
                Snapshot snap = api.snapshot();
                DdlWriter.Result ddl = snap == null ? null : DdlWriter.write(snap, api.selectedTables());
                if (ddl != null && ddl.ok()) {
                    c.flyway = "SCHEMA";
                    c.ddl = ddl.sql;
                } else {
                    messages.add(new Msg("WARN", "No V1__baseline.sql: " + (ddl == null ? "the schema is not known" : String.join("; ", ddl.problems))
                        + ". Flyway takes the existing database as version 1 instead (MIG-3)."));
                }
            }
        }

        if (cfg.tests != null && cfg.tests.application) {
            if (!c.flyway.equals("SCHEMA")) {
                messages.add(new Msg("WARN", "The application test needs Flyway with the schema (V1__baseline.sql) to create its database; it is not generated."));
            } else {
                c.applicationTest = true;
                container(c, kind, api.snapshot() == null ? "" : api.snapshot().serverVersion, boot);
            }
        }
        return c;
    }

    /**
     * Spring Modulith's release for a Boot line (each is built against one: 2.1.1 → Boot 4.1.1, 2.0.8 → 4.0.8,
     * 1.4.13 → 3.5.16); other lines get the nearest, with a warning.
     */
    static String modulith(String boot, List<Msg> messages) {
        String v = boot == null ? "" : boot.trim();
        if (v.startsWith("4.1")) return "2.1.1";
        if (v.startsWith("4.0")) return "2.0.8";
        if (v.startsWith("3.5")) return "1.4.13";
        String pick = v.startsWith("4") ? "2.1.1" : "1.4.13";
        messages.add(new Msg("WARN", "Spring Modulith has no release tested with Spring Boot " + v + "; " + pick + " is used."));
        return pick;
    }

    /** The container of the source engine at the source's version (Testcontainers 2 with Boot 4, 1.x with Boot 3). */
    private static void container(ProjectChecks c, SourceKind kind, String serverVersion, int boot) {
        boolean tc2 = boot >= 4;
        String module;
        switch (kind) {
            case POSTGRESQL -> {
                c.containerClass = "PostgreSQLContainer";
                c.containerImage = "postgres:" + version(serverVersion, 1, "16") + "-alpine";
                module = "postgresql";
            }
            case MYSQL -> {
                c.containerClass = "MySQLContainer";
                c.containerImage = "mysql:" + version(serverVersion, 2, "8.4");
                module = "mysql";
            }
            case MARIADB -> {
                c.containerClass = "MariaDBContainer";
                c.containerImage = "mariadb:" + version(serverVersion, 2, "11.4");
                module = "mariadb";
            }
            default -> {
                c.inMemory = true;
                return;
            }
        }
        c.containerImport = (tc2 ? "org.testcontainers." + module + "." : "org.testcontainers.containers.") + c.containerClass;
        c.testArtifacts.add("org.springframework.boot:spring-boot-testcontainers");
        c.testArtifacts.add("org.testcontainers:" + (tc2 ? "testcontainers-junit-jupiter" : "junit-jupiter"));
        c.testArtifacts.add("org.testcontainers:" + (tc2 ? "testcontainers-" : "") + module);
    }

    /** "16.4 (Debian…)" → "16" (parts = 1) or "8.4.3" → "8.4" (parts = 2); the fallback when there is no version. */
    static String version(String serverVersion, int parts, String fallback) {
        Matcher m = VERSION.matcher(serverVersion == null ? "" : serverVersion);
        if (!m.find()) return fallback;
        return parts == 1 || m.group(2) == null ? m.group(1) : m.group(1) + "." + m.group(2);
    }

    /** Whether this is the generic (Boot 3, Testcontainers 1) container class, used as {@code Container<?>}. */
    public boolean genericContainer() {
        return containerImport.startsWith("org.testcontainers.containers.");
    }

    @Override
    public String toString() {
        return "checks(" + flyway.toLowerCase(Locale.ROOT) + ")";
    }
}
