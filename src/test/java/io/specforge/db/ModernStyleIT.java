package io.specforge.db;

import com.fasterxml.jackson.databind.JsonNode;
import io.specforge.config.GeneratorConfig;
import io.specforge.gen.GeneratedFile;
import io.specforge.mapper.DatabaseMapper;
import io.specforge.model.Model.ApiModel;
import io.specforge.plan.GenerationPlan;
import io.specforge.plan.GenerationPlan.ControllerDecision;
import io.specforge.plan.GenerationPlan.UseCaseOperation;
import io.specforge.snapshot.Snapshot;
import io.specforge.source.ConnectionSettings;
import io.specforge.source.SourceKind;
import io.specforge.source.jdbc.JdbcConnector;
import io.specforge.support.Engines;
import io.specforge.support.GeneratedApp;
import io.specforge.support.Generation;
import io.specforge.support.Maven;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.specforge.support.GeneratedApp.json;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "Modern" style preset end to end on PostgreSQL (STYLE-9, STYLE-10, STYLE-11): XxxRequest in, XxxResponse out,
 * RFC 9457 errors with JSON property names, and a use-case aggregate built from request records.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ModernStyleIT {

    static ConnectionSettings cs;
    static Map<String, String> sources;
    static Path project;
    static GeneratedApp app;

    @BeforeAll
    static void generate() throws Exception {
        cs = Engines.database(SourceKind.POSTGRESQL, Path.of("src/test/resources/fixtures/engines/shop-postgresql.sql"));
        Snapshot snap = new JdbcConnector(cs).introspect(List.of("public"), null);
        project = Files.createTempDirectory("specforge-modern");
        GeneratorConfig cfg = Generation.config(project);
        cfg.basePackage = "com.example.shop";
        Generation.style(cfg, "MODERN");
        GenerationPlan plan = new GenerationPlan();
        ControllerDecision checkout = new ControllerDecision();
        checkout.name = "Checkout";
        checkout.path = "checkout";
        checkout.operations.add(op("AGGREGATE_CREATE"));
        checkout.operations.add(op("WITH_CHILDREN"));
        plan.controllers.add(checkout);
        ApiModel model = new DatabaseMapper().map(snap, plan, cfg, "Shop", cs.jdbcUrl());
        List<GeneratedFile> files = Generation.generate(model, cfg);
        Maven.write(files);
        sources = Generation.byPath(files);
    }

    private static UseCaseOperation op(String kind) {
        UseCaseOperation o = new UseCaseOperation();
        o.kind = kind;
        o.entity = "public.orders";
        o.child = "public.order_items";
        return o;
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
    void requestOmitsGeneratedKeyResponseKeepsIt() {
        assertThat(source("/CustomerRequest.java")).doesNotContain("Long id").contains("@NotNull");
        assertThat(source("/CustomerResponse.java")).contains("Long id").doesNotContain("@NotNull");
        assertThat(sources.keySet()).noneMatch(p -> p.endsWith("Dto.java"));
        assertThat(source("/ApiExceptionHandler.java")).contains("extends ResponseEntityExceptionHandler").contains("ProblemDetail");
    }

    @Test
    @Order(2)
    void generatedProjectBuildsAndStarts() throws Exception {
        app = GeneratedApp.buildAndStart(project, cs, "/customers");
    }

    @Test
    @Order(3)
    void crudWithRequestAndResponse() throws Exception {
        HttpResponse<String> created = app.call("POST", "/customers", Map.of("email", "ada@example.com", "fullName", "Ada"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        long id = json(created).get("id").asLong();
        assertThat(json(created).get("createdAt").isNull()).as("database default read back").isFalse();

        // a client-sent id is not part of the request and is ignored
        HttpResponse<String> withId = app.call("POST", "/customers", Map.of("id", 999999, "email", "b@example.com", "fullName", "B"));
        assertThat(withId.statusCode()).as(withId.body()).isEqualTo(201);
        assertThat(json(withId).get("id").asLong()).isNotEqualTo(999999);

        HttpResponse<String> updated = app.call("PUT", "/customers/" + id, Map.of("email", "ada@example.com", "fullName", "Ada L."));
        assertThat(updated.statusCode()).as(updated.body()).isEqualTo(200);
        assertThat(json(updated).get("fullName").asText()).isEqualTo("Ada L.");
    }

    @Test
    @Order(3)
    void errorsAreProblemDetails() throws Exception {
        HttpResponse<String> invalid = app.call("POST", "/customers", Map.of("fullName", "x".repeat(101)));
        assertProblem(invalid, 400);
        List<String> fields = new ArrayList<>();
        json(invalid).get("errors").forEach(e -> fields.add(e.get("field").asText()));
        assertThat(fields).contains("email", "fullName");

        // patch is validated against the request's rules
        long id = json(app.call("POST", "/customers", Map.of("email", "p@example.com", "fullName", "P"))).get("id").asLong();
        HttpResponse<String> patched = app.call("PATCH", "/customers/" + id, Map.of("fullName", "y".repeat(101)));
        assertProblem(patched, 400);
        assertThat(json(patched).get("errors").get(0).get("field").asText()).isEqualTo("fullName");

        assertProblem(app.call("GET", "/customers/999999", null), 404);
        assertProblem(app.call("GET", "/customers?nope=1", null), 400);
        assertProblem(app.call("POST", "/customers", Map.of("email", "p@example.com", "fullName", "dup")), 409);
        assertProblem(app.call("DELETE", "/customers/999999", null), 404);
    }

    @Test
    @Order(3)
    void aggregateCreateUsesRequestRecords() throws Exception {
        long customer = json(app.call("POST", "/customers", Map.of("email", "agg@example.com", "fullName", "Agg"))).get("id").asLong();
        long product = json(app.call("POST", "/products", Map.of("sku", "SKU-9", "name", "Lamp", "price", 10))).get("id").asLong();
        HttpResponse<String> created = app.call("POST", "/checkout/orders", Map.of(
            "order", Map.of("customerId", customer, "orderedOn", "2026-10-18"),
            "orderItems", List.of(Map.of("lineNo", 1, "productId", product, "quantity", 2))));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode body = json(created);
        long orderId = body.get("order").get("id").asLong();
        assertThat(body.get("orderItems").get(0).get("orderId").asLong()).isEqualTo(orderId);
        assertThat(json(app.call("GET", "/checkout/orders/" + orderId, null)).get("orderItems")).hasSize(1);

        HttpResponse<String> bad = app.call("POST", "/checkout/orders", Map.of("order", Map.of("customerId", customer, "orderedOn", "2026-10-18")));
        assertProblem(bad, 400);
    }

    private static void assertProblem(HttpResponse<String> r, int status) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("application/problem+json"));
        JsonNode b = json(r);
        assertThat(b.get("status").asInt()).isEqualTo(status);
        assertThat(b.has("title")).as(r.body()).isTrue();
    }
}
