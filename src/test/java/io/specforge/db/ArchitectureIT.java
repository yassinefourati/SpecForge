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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static io.specforge.support.GeneratedApp.json;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ARCH-7: every architecture serves the same HTTP contract. The same scenario (CRUD, a relation, a many-to-many,
 * an aggregate use case across two tables, 404, 400) runs against each layout's generated project on PostgreSQL.
 */
class ArchitectureIT {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LAYERED", "FEATURE"})
    void sameContractInEveryArchitecture(String architecture) throws Exception {
        run(architecture, cfg -> cfg.architecture = architecture, null);
    }

    /** ARCH-3 … ARCH-5: catalog and sales as Spring Modulith modules; order items refer to products by id. */
    @org.junit.jupiter.api.Test
    void modularMonolith() throws Exception {
        run("MODULAR", cfg -> cfg.architecture = "MODULAR", p -> {
            for (String t : List.of("products", "tags", "countries")) p.modules.put("public." + t, "catalog");
            for (String t : List.of("customers", "orders", "order_items")) p.modules.put("public." + t, "sales");
        });
    }

    /**
     * ARCH-6, ARCH-7: ports and adapters serve the same contract. Use cases (and the composite-key table they need)
     * are not generated in the hexagonal architecture yet, so the scenario skips that part, and only that part.
     */
    @org.junit.jupiter.api.Test
    void hexagonal() throws Exception {
        run("HEXAGONAL", cfg -> cfg.architecture = "HEXAGONAL", null, false);
    }

    /** Generates the shop with a Checkout use case in the given layout, builds and starts it, and runs the scenario. */
    static void run(String name, Consumer<GeneratorConfig> layout, Consumer<GenerationPlan> plan) throws Exception {
        run(name, layout, plan, true);
    }

    static void run(String name, Consumer<GeneratorConfig> layout, Consumer<GenerationPlan> plan, boolean useCases) throws Exception {
        ConnectionSettings cs = Engines.database(SourceKind.POSTGRESQL, Path.of("src/test/resources/fixtures/engines/shop-postgresql.sql"));
        Snapshot snap = new JdbcConnector(cs).introspect(List.of("public"), null);
        Path project = Files.createTempDirectory("specforge-arch-" + name.toLowerCase());
        GeneratorConfig cfg = Generation.config(project);
        cfg.basePackage = "com.example.shop";
        Generation.style(cfg, "MODERN");
        layout.accept(cfg);
        GenerationPlan p = new GenerationPlan();
        ControllerDecision checkout = new ControllerDecision();
        checkout.name = "Checkout";
        checkout.path = "checkout";
        for (String kind : List.of("AGGREGATE_CREATE", "WITH_CHILDREN", "CHILDREN")) {
            UseCaseOperation o = new UseCaseOperation();
            o.kind = kind;
            o.entity = "public.orders";
            o.child = "public.order_items";
            checkout.operations.add(o);
        }
        p.controllers.add(checkout);
        if (plan != null) plan.accept(p);
        ApiModel model = new DatabaseMapper().map(snap, p, cfg, "Shop", cs.jdbcUrl());
        List<GeneratedFile> files = Generation.generate(model, cfg);
        Maven.write(files);
        try (GeneratedApp app = GeneratedApp.buildAndStart(project, cs, "/customers")) {
            scenario(app, useCases);
        }
    }

    static void scenario(GeneratedApp app, boolean useCases) throws Exception {
        HttpResponse<String> customer = app.call("POST", "/customers", Map.of("email", "arch@example.com", "fullName", "Arch"));
        assertThat(customer.statusCode()).as(customer.body()).isEqualTo(201);
        long customerId = json(customer).get("id").asLong();
        assertThat(app.call("POST", "/countries", Map.of("code", "TN", "name", "Tunisia")).statusCode()).isEqualTo(201);
        long tag = json(app.call("POST", "/tags", Map.of("label", "lamp"))).get("id").asLong();
        HttpResponse<String> product = app.call("POST", "/products", Map.of("sku", "ARCH-1", "name", "Lamp", "price", 12,
            "countryCode", "TN", "tagIds", List.of(tag)));
        assertThat(product.statusCode()).as(product.body()).isEqualTo(201);
        JsonNode p = json(product);
        assertThat(p.get("countryCode").asText()).isEqualTo("TN");
        assertThat(p.get("tagIds").get(0).asLong()).isEqualTo(tag);

        if (useCases) {
            HttpResponse<String> order = app.call("POST", "/checkout/orders", Map.of(
                "order", Map.of("customerId", customerId, "orderedOn", "2026-10-18"),
                "orderItems", List.of(Map.of("lineNo", 1, "productId", p.get("id").asLong(), "quantity", 3))));
            assertThat(order.statusCode()).as(order.body()).isEqualTo(201);
            long orderId = json(order).get("order").get("id").asLong();
            assertThat(json(app.call("GET", "/checkout/orders/" + orderId, null)).get("orderItems")).hasSize(1);
            assertThat(json(app.call("GET", "/checkout/orders/" + orderId + "/order-items?sort=quantity,desc", null)).get("totalElements").asLong()).isEqualTo(1);
        } else {
            HttpResponse<String> order = app.call("POST", "/orders", Map.of("customerId", customerId, "orderedOn", "2026-10-18"));
            assertThat(order.statusCode()).as(order.body()).isEqualTo(201);
            assertThat(json(order).get("status").asText()).as("database default read back").isEqualTo("NEW");
        }
        HttpResponse<String> patched = app.call("PATCH", "/products/" + p.get("id").asLong(), Map.of("name", "Desk lamp"));
        assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
        assertThat(json(patched).get("name").asText()).isEqualTo("Desk lamp");
        assertThat(json(patched).get("sku").asText()).as("absent properties kept").isEqualTo("ARCH-1");
        assertThat(app.call("GET", "/products?nope=1", null).statusCode()).as("unknown filter").isEqualTo(400);

        assertThat(json(app.call("GET", "/products?sort=name,asc", null)).get("totalElements").asLong()).isEqualTo(1);
        assertThat(app.call("PUT", "/customers/" + customerId, Map.of("email", "arch@example.com", "fullName", "Arch 2")).statusCode()).isEqualTo(200);
        assertThat(app.call("GET", "/customers/999999", null).statusCode()).isEqualTo(404);
        assertThat(app.call("POST", "/customers", Map.of("fullName", "no email")).statusCode()).isEqualTo(400);
        assertThat(app.call("DELETE", "/tags/" + tag, null).statusCode()).as("still used by a product").isEqualTo(409);
    }
}
