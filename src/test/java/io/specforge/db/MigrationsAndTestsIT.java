package io.specforge.db;

import io.specforge.config.GeneratorConfig;
import io.specforge.gen.GeneratedFile;
import io.specforge.mapper.DatabaseMapper;
import io.specforge.model.Model.ApiModel;
import io.specforge.plan.GenerationPlan;
import io.specforge.snapshot.Snapshot;
import io.specforge.source.ConnectionSettings;
import io.specforge.source.SourceKind;
import io.specforge.source.jdbc.JdbcConnector;
import io.specforge.support.Engines;
import io.specforge.support.GeneratedApp;
import io.specforge.support.Generation;
import io.specforge.support.Maven;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-1, MIG-2, TEST-1, TEST-2 on PostgreSQL, on both Boot lines (Testcontainers 2 with Boot 4, 1.x with Boot 3):
 * the generated project's own tests pass (a web-layer test per controller; DatabaseSchemaTests creates a container
 * from V1__baseline.sql and Hibernate validates against it), and against the existing database Flyway only records
 * the baseline: the app starts and serves it unchanged.
 */
class MigrationsAndTestsIT {

    @ParameterizedTest(name = "Boot {0}")
    @ValueSource(strings = {"4.1.1", "3.5.16"})
    void generatedTestsPassAndTheExistingDatabaseIsBaselined(String boot) throws Exception {
        ConnectionSettings cs = Engines.database(SourceKind.POSTGRESQL, Path.of("src/test/resources/fixtures/engines/shop-postgresql.sql"));
        Snapshot snap = new JdbcConnector(cs).introspect(List.of("public"), null);
        Path project = Files.createTempDirectory("specforge-mig");
        GeneratorConfig cfg = Generation.config(project);
        cfg.basePackage = "com.example.shop";
        cfg.options.springBootVersion = boot;
        Generation.style(cfg, "MODERN");
        cfg.migrations.flyway = "SCHEMA";
        cfg.tests.controllers = true;
        cfg.tests.application = true;
        ApiModel model = new DatabaseMapper().map(snap, new GenerationPlan(), cfg, "Shop", cs.jdbcUrl());
        List<GeneratedFile> files = Generation.generate(model, cfg);
        Map<String, String> sources = Generation.byPath(files);
        assertThat(sources).containsKeys("src/main/resources/db/migration/V1__baseline.sql",
            "src/test/java/com/example/shop/DatabaseSchemaTests.java",
            "src/test/java/com/example/shop/controller/CustomerControllerTest.java");
        assertThat(sources.get("src/main/resources/application.properties")).contains("spring.flyway.baseline-on-migrate=true");
        Maven.write(files);

        // the project's own tests: web-layer tests, and the schema test on a container created by Flyway from V1
        Maven.Result build = Maven.run(project, "verify");
        assertThat(build.ok()).as("the generated project's tests fail:%n%s", build.output()).isTrue();
        Path reports = project.resolve("target/surefire-reports");
        assertThat(Files.readString(reports.resolve("TEST-com.example.shop.DatabaseSchemaTests.xml")))
            .as("the schema test ran on a container").contains("tests=\"1\"").contains("skipped=\"0\"").contains("failures=\"0\"");
        assertThat(Files.readString(reports.resolve("TEST-com.example.shop.controller.CustomerControllerTest.xml")))
            .as("list, 404, invalid body, delete").contains("tests=\"4\"").contains("skipped=\"0\"").contains("failures=\"0\"").contains("errors=\"0\"");

        // the existing database: Flyway records version 1 as the baseline and runs nothing
        try (GeneratedApp app = GeneratedApp.buildAndStart(project, cs, "/customers")) {
            assertThat(app.call("GET", "/products", null).statusCode()).isEqualTo(200);
        }
        try (Connection c = DriverManager.getConnection(cs.jdbcUrl(), cs.user, cs.password);
             ResultSet r = c.createStatement().executeQuery("select version, type from public.flyway_schema_history order by installed_rank")) {
            assertThat(r.next()).isTrue();
            assertThat(r.getString("version")).isEqualTo("1");
            assertThat(r.getString("type")).isEqualTo("BASELINE");
            assertThat(r.next()).as("nothing else ran").isFalse();
        }
    }
}
