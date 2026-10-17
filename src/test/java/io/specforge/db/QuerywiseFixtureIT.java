package io.specforge.db;

import io.specforge.config.GeneratorConfig;
import io.specforge.gen.GeneratedFile;
import io.specforge.mapper.DatabaseMapper;
import io.specforge.model.Model.ApiModel;
import io.specforge.plan.GenerationPlan;
import io.specforge.snapshot.Snapshot;
import io.specforge.source.ConnectionSettings;
import io.specforge.source.jdbc.JdbcConnector;
import io.specforge.support.GeneratedApp;
import io.specforge.support.Generation;
import io.specforge.support.Maven;
import io.specforge.support.Postgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.specforge.support.GeneratedApp.json;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Second real-world PostgreSQL fixture (querywise pack, MIT): assigned integer keys, CHECK constraints, multi-column
 * UNIQUE constraints and FK chains, through the default plan to a running generated application (M1 done-when).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QuerywiseFixtureIT {

    static ConnectionSettings cs;
    static Snapshot snap;
    static Path project;
    static Map<String, String> sources;
    static GeneratedApp app;

    @BeforeAll
    static void generate() throws Exception {
        cs = Postgres.database(List.of(Path.of("src/test/resources/fixtures/querywise/postgres-V001.sql")));
        snap = new JdbcConnector(cs).introspect(List.of("public"), null);
        project = Files.createTempDirectory("specforge-querywise");
        GeneratorConfig cfg = Generation.config(project);
        cfg.basePackage = "com.example.energy";
        ApiModel model = new DatabaseMapper().map(snap, new GenerationPlan(), cfg, "Energy", cs.jdbcUrl());
        List<GeneratedFile> files = Generation.generate(model, cfg);
        Maven.write(files);
        sources = Generation.byPath(files);
    }

    @AfterAll
    static void stop() {
        if (app != null) app.close();
    }

    private static String source(String suffix) {
        return sources.entrySet().stream().filter(e -> e.getKey().endsWith(suffix)).map(Map.Entry::getValue).findFirst()
            .orElseThrow(() -> new AssertionError("no generated file ending with " + suffix + " in " + sources.keySet()));
    }

    @Test
    @Order(1)
    void everyTableIsAnEntity() {
        assertThat(snap.tables).extracting(t -> t.name).containsExactlyInAnyOrder(
            "crews", "sites", "contracts", "turbines", "outages", "readings", "settlements", "work_orders");
        for (String cls : List.of("Crew", "Site", "Contract", "Turbine", "Outage", "Reading", "Settlement", "WorkOrder")) {
            assertThat(sources.keySet()).anyMatch(p -> p.endsWith("/" + cls + ".java"));
            assertThat(sources.keySet()).anyMatch(p -> p.endsWith("/" + cls + "Controller.java"));
        }
    }

    @Test
    @Order(1)
    void plainIntegerKeysAreAssigned() {
        // no identity, no sequence default: the client supplies the key (MAP-8)
        assertThat(source("/Site.java")).contains("@Id").doesNotContain("@GeneratedValue");
        assertThat(source("/SiteDto.java")).contains("Integer id");
    }

    @Test
    @Order(1)
    void constraintsAndRelationsComeFromFacts() {
        assertThat(source("/Reading.java")).contains("@UniqueConstraint(columnNames = {\"turbine_id\", \"observed_on\"})")
            .contains("Turbine turbine");
        assertThat(source("/Turbine.java")).contains("@UniqueConstraint(columnNames = {\"serial\"})").contains("Site site");
        assertThat(source("/WorkOrder.java")).contains("Outage outage").contains("Crew crew");
        assertThat(source("/Site.java")).contains("BigDecimal latitude");
    }

    @Test
    @Order(2)
    void generatedProjectBuildsAndStarts() throws Exception {
        app = GeneratedApp.buildAndStart(project, cs, "/sites");
    }

    @Test
    @Order(3)
    void crudAcrossAForeignKeyChain() throws Exception {
        HttpResponse<String> site = app.call("POST", "/sites", Map.of("id", 1, "name", "North Ridge", "region", "north",
            "latitude", 54.123, "longitude", -2.5));
        assertThat(site.statusCode()).as(site.body()).isEqualTo(201);
        assertThat(app.call("POST", "/sites", Map.of("id", 1, "name", "Again", "region", "north", "latitude", 1, "longitude", 1))
            .statusCode()).as("duplicate assigned key").isEqualTo(409);

        HttpResponse<String> turbine = app.call("POST", "/turbines", Map.of("id", 10, "siteId", 1, "serial", "T-10", "capacityKw", 3000));
        assertThat(turbine.statusCode()).as(turbine.body()).isEqualTo(201);
        assertThat(json(turbine).get("siteId").asInt()).isEqualTo(1);
        assertThat(app.call("POST", "/turbines", Map.of("id", 11, "siteId", 999, "serial", "T-11", "capacityKw", 3000))
            .statusCode()).as("unknown site").isIn(404, 409);
        assertThat(app.call("POST", "/turbines", Map.of("id", 12, "siteId", 1, "serial", "T-12", "capacityKw", 0))
            .statusCode()).as("CHECK capacity_kw > 0").isEqualTo(409);

        HttpResponse<String> reading = app.call("POST", "/readings", Map.of("id", 100, "turbineId", 10, "observedOn", "2026-10-01",
            "energyWh", 123456, "quality", "valid"));
        assertThat(reading.statusCode()).as(reading.body()).isEqualTo(201);
        assertThat(app.call("POST", "/readings", Map.of("id", 101, "turbineId", 10, "observedOn", "2026-10-01",
            "energyWh", 1, "quality", "valid")).statusCode()).as("UNIQUE (turbine_id, observed_on)").isEqualTo(409);

        assertThat(json(app.call("GET", "/readings?turbineId=10", null)).get("totalElements").asLong()).isEqualTo(1);
        HttpResponse<String> patched = app.call("PATCH", "/readings/100", Map.of("quality", "estimated"));
        assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
        assertThat(json(patched).get("energyWh").asLong()).isEqualTo(123456);

        assertThat(app.call("DELETE", "/readings/100", null).statusCode()).isEqualTo(204);
        assertThat(app.call("GET", "/readings/100", null).statusCode()).isEqualTo(404);
    }
}
