package io.specforge.db;

import com.fasterxml.jackson.databind.JsonNode;
import io.specforge.config.GeneratorConfig;
import io.specforge.gen.GeneratedFile;
import io.specforge.mapper.DatabaseMapper;
import io.specforge.model.DbModel.DbEntity;
import io.specforge.model.Model.ApiModel;
import io.specforge.plan.GenerationPlan;
import io.specforge.snapshot.Snapshot;
import io.specforge.source.ConnectionSettings;
import io.specforge.source.SourceKind;
import io.specforge.source.jdbc.JdbcConnector;
import io.specforge.source.jdbc.ReadOnlyProbe;
import io.specforge.suggest.SuggestionEngine;
import io.specforge.support.Engines;
import io.specforge.support.GeneratedApp;
import io.specforge.support.Generation;
import io.specforge.support.Maven;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.specforge.support.GeneratedApp.json;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 2: the same "shop" model on every relational engine (fixtures/engines/shop-*.sql) → snapshot facts →
 * default plan → generated project that builds, starts with ddl-auto=validate against that engine, and passes CRUD
 * over identity, sequence, assigned, composite and many-to-many keys. Engines: see {@link Engines#selected()}.
 */
class EngineMatrixIT {

    /** Engines whose fixture draws the products key from a sequence; the others use identity / auto-increment. */
    private static final Set<SourceKind> SEQUENCE_KEYS = Set.of(SourceKind.POSTGRESQL, SourceKind.SQLSERVER, SourceKind.ORACLE, SourceKind.H2);

    @TestFactory
    List<DynamicNode> everyEngine() {
        List<DynamicNode> out = new ArrayList<>();
        for (SourceKind kind : Engines.selected()) {
            Run run = new Run(kind);
            out.add(DynamicContainer.dynamicContainer(kind.label, List.of(
                DynamicTest.dynamicTest("introspects keys, constraints and sequences", run::introspect),
                DynamicTest.dynamicTest("connection refuses writes where the engine can enforce it", run::readOnly),
                DynamicTest.dynamicTest("suggests mappings from facts", run::suggest),
                DynamicTest.dynamicTest("generated project builds and starts (ddl-auto=validate)", run::buildAndStart),
                DynamicTest.dynamicTest("CRUD over every key style", run::crud),
                DynamicTest.dynamicTest("stops", run::stop))));
        }
        return out;
    }

    private static final class Run {
        final SourceKind kind;
        ConnectionSettings cs;
        Snapshot snap;
        Path project;
        GeneratedApp app;

        Run(SourceKind kind) {
            this.kind = kind;
        }

        void introspect() throws Exception {
            String file = "shop-" + kind.name().toLowerCase(Locale.ROOT) + ".sql";
            cs = Engines.database(kind, Path.of("src/test/resources/fixtures/engines", file));
            JdbcConnector.ConnectionInfo info = new JdbcConnector(cs).test();
            assertThat(info.serverVersion).isNotBlank();
            snap = new JdbcConnector(cs).introspect(cs.schemas, null);

            assertThat(snap.tables).extracting(t -> t.name.toLowerCase(Locale.ROOT)).containsExactlyInAnyOrder(
                "countries", "customers", "products", "orders", "order_items", "tags", "product_tags");
            assertThat(lower(table("customers").primaryKey)).containsExactly("id");
            assertThat(lower(table("countries").primaryKey)).containsExactly("code");
            assertThat(lower(table("order_items").primaryKey)).containsExactly("order_id", "line_no");
            assertThat(table("order_items").foreignKeys).hasSize(2);
            assertThat(table("product_tags").foreignKeys).extracting(fk -> fk.refTable.toLowerCase(Locale.ROOT))
                .containsExactlyInAnyOrder("products", "tags");
            assertThat(table("customers").uniqueKeys).extracting(u -> lower(u.columns)).containsExactly(List.of("email"));
            assertThat(table("tags").uniqueKeys).extracting(u -> lower(u.columns)).containsExactly(List.of("label"));
            Snapshot.Column customerId = column("customers", "id");
            assertThat(customerId.identity || customerId.autoIncrement).as("customers.id is identity / auto-increment").isTrue();
            assertThat(column("customers", "created_at").defaultValue).as("created_at default").isNotBlank();
            assertThat(column("customers", "email").nullable).isFalse();
            if (SEQUENCE_KEYS.contains(kind)) {
                assertThat(snap.sequences).extracting(s -> s.name.toLowerCase(Locale.ROOT)).contains("products_seq");
                assertThat(column("products", "id").defaultValue).containsIgnoringCase("products_seq");
            }
        }

        void readOnly() throws Exception {
            java.sql.SQLException refused = ReadOnlyProbe.write(cs, "INSERT INTO countries (code, name) VALUES ('ZZ', 'Nowhere')");
            // SQL Server and Db2 cannot make a session read-only (RO-2): the UI says so and offers a least-privilege login
            if (kind == SourceKind.SQLSERVER || kind == SourceKind.DB2) return;
            assertThat(refused != null).as("%s accepted a write through SpecForge's connection", kind.label).isTrue();
        }

        void suggest() {
            SuggestionEngine.Result r = new SuggestionEngine().build(snap, new GenerationPlan());
            Map<String, DbEntity> byClass = new LinkedHashMap<>();
            for (DbEntity e : r.entities) byClass.put(e.className, e);
            assertThat(byClass).containsOnlyKeys("Country", "Customer", "Product", "Order", "OrderItem", "Tag");
            assertThat(byClass.get("Customer").id.strategy).isEqualTo("IDENTITY");
            assertThat(byClass.get("Country").id.strategy).isEqualTo("ASSIGNED");
            assertThat(byClass.get("Product").id.strategy).isEqualTo(SEQUENCE_KEYS.contains(kind) ? "SEQUENCE" : "IDENTITY");
            if (SEQUENCE_KEYS.contains(kind)) assertThat(byClass.get("Product").id.sequenceName).isEqualToIgnoringCase("products_seq");
            assertThat(byClass.get("OrderItem").id.composite()).isTrue();
            assertThat(byClass.get("Product").manyToMany).extracting(m -> m.targetClass).containsExactly("Tag");
            assertThat(byClass.get("Product").relations).extracting(rel -> rel.name, rel -> rel.dtoField.name)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("country", "countryCode"));
        }

        void buildAndStart() throws Exception {
            project = Files.createTempDirectory("specforge-" + kind.name().toLowerCase(Locale.ROOT));
            GeneratorConfig cfg = Generation.config(project);
            cfg.basePackage = "com.example.shop";
            ApiModel model = new DatabaseMapper().map(snap, new GenerationPlan(), cfg, "Shop", cs.jdbcUrl());
            List<GeneratedFile> files = Generation.generate(model, cfg);
            Maven.write(files);
            app = GeneratedApp.buildAndStart(project, cs, "/customers");
        }

        void crud() throws Exception {
            assertThat(app.anonymous("GET", "/customers").statusCode()).isEqualTo(401);

            // assigned key
            HttpResponse<String> fr = app.call("POST", "/countries", Map.of("code", "FR", "name", "France"));
            assertThat(fr.statusCode()).as(fr.body()).isEqualTo(201);
            assertThat(app.call("POST", "/countries", Map.of("code", "FR", "name", "Again")).statusCode()).as("duplicate key").isEqualTo(409);

            // identity key, database default, unique constraint, large text
            HttpResponse<String> ada = app.call("POST", "/customers", Map.of("email", "ada@example.com", "fullName", "Ada Lovelace", "notes", "x".repeat(5000)));
            assertThat(ada.statusCode()).as(ada.body()).isEqualTo(201);
            long customer = json(ada).get("id").asLong();
            assertThat(json(ada).get("createdAt").isNull()).as("database default read back").isFalse();
            assertThat(app.call("POST", "/customers", Map.of("email", "ada@example.com", "fullName", "Copy")).statusCode()).isEqualTo(409);

            // sequence or identity key, many-to-one by a non-id column, many-to-many
            long tag = json(app.call("POST", "/tags", Map.of("label", "new"))).get("id").asLong();
            HttpResponse<String> product = app.call("POST", "/products", Map.of("sku", "SKU-1", "name", "Lamp", "price", 19.99,
                "countryCode", "FR", "tagIds", List.of(tag)));
            assertThat(product.statusCode()).as(product.body()).isEqualTo(201);
            long productId = json(product).get("id").asLong();
            JsonNode readProduct = json(app.call("GET", "/products/" + productId, null));
            assertThat(readProduct.get("countryCode").asText()).isEqualTo("FR");
            assertThat(readProduct.get("tagIds").get(0).asLong()).isEqualTo(tag);
            assertThat(readProduct.get("price").decimalValue()).isEqualByComparingTo("19.99");

            // database default on a plain column; Oracle DATE has a time of day, so it maps to LocalDateTime
            String day = kind == SourceKind.ORACLE ? "2026-10-17T00:00:00" : "2026-10-17";
            HttpResponse<String> order = app.call("POST", "/orders", Map.of("customerId", customer, "orderedOn", day));
            assertThat(order.statusCode()).as(order.body()).isEqualTo(201);
            long orderId = json(order).get("id").asLong();
            assertThat(json(order).get("status").asText()).isEqualTo("NEW");
            assertThat(app.call("POST", "/orders", Map.of("customerId", 999_999, "orderedOn", day)).statusCode())
                .as("unknown customer").isIn(404, 409);

            // composite key containing a foreign key
            HttpResponse<String> item = app.call("POST", "/order-items", Map.of("orderId", orderId, "lineNo", 1, "productId", productId, "quantity", 2));
            assertThat(item.statusCode()).as(item.body()).isEqualTo(201);
            assertThat(json(app.call("GET", "/order-items/" + orderId + "/1", null)).get("quantity").asInt()).isEqualTo(2);
            assertThat(app.call("POST", "/order-items", Map.of("orderId", orderId, "lineNo", 2, "productId", productId, "quantity", 0))
                .statusCode()).as("CHECK quantity > 0").isEqualTo(409);

            // list with a filter and sort, patch, delete
            JsonNode page = json(app.call("GET", "/products?countryCode=FR&sort=name,asc", null));
            assertThat(page.get("totalElements").asLong()).isEqualTo(1);
            HttpResponse<String> patched = app.call("PATCH", "/customers/" + customer, Map.of("fullName", "Ada King"));
            assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
            assertThat(json(patched).get("email").asText()).isEqualTo("ada@example.com");
            assertThat(app.call("DELETE", "/order-items/" + orderId + "/1", null).statusCode()).isEqualTo(204);
            assertThat(app.call("GET", "/order-items/" + orderId + "/1", null).statusCode()).isEqualTo(404);
        }

        void stop() {
            if (app != null) app.close();
        }

        private Snapshot.Table table(String name) {
            return snap.tables.stream().filter(t -> t.name.equalsIgnoreCase(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no table " + name));
        }

        private Snapshot.Column column(String table, String column) {
            return table(table).columns.stream().filter(c -> c.name.equalsIgnoreCase(column)).findFirst()
                .orElseThrow(() -> new AssertionError("no column " + table + "." + column));
        }

        private static List<String> lower(List<String> names) {
            return names.stream().map(n -> n.toLowerCase(Locale.ROOT)).toList();
        }
    }
}
