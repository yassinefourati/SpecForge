package io.specforge.db;

import com.fasterxml.jackson.databind.JsonNode;
import io.specforge.config.GeneratorConfig;
import io.specforge.gen.GeneratedFile;
import io.specforge.mapper.DatabaseMapper;
import io.specforge.model.Model.ApiModel;
import io.specforge.plan.GenerationPlan;
import io.specforge.plan.GenerationPlan.ColumnDecision;
import io.specforge.plan.GenerationPlan.ControllerDecision;
import io.specforge.plan.GenerationPlan.EntityDecision;
import io.specforge.plan.GenerationPlan.RelationDecision;
import io.specforge.plan.GenerationPlan.UseCaseOperation;
import io.specforge.snapshot.Snapshot;
import io.specforge.source.ConnectionSettings;
import io.specforge.source.SourceKind;
import io.specforge.source.jdbc.JdbcConnector;
import io.specforge.support.Engines;
import io.specforge.support.GeneratedApp;
import io.specforge.support.Generation;
import io.specforge.support.Maven;
import io.specforge.util.Json;
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
 * R2 milestones 4 and 5 on PostgreSQL: mapping-editor decisions (MAP-11) and a use-case controller with every
 * operation kind (OPS-3 … OPS-5) go through the plan, generate a project that compiles, starts with
 * ddl-auto=validate, and behaves: aggregate create and update in one transaction, parent with children, children of
 * a parent, moved and removed operations.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UseCaseIT {

    static ConnectionSettings cs;
    static ApiModel model;
    static Map<String, String> sources;
    static Path project;
    static GeneratedApp app;

    @BeforeAll
    static void generate() throws Exception {
        cs = Engines.database(SourceKind.POSTGRESQL, Path.of("src/test/resources/fixtures/engines/shop-postgresql.sql"));
        Snapshot snap = new JdbcConnector(cs).introspect(List.of("public"), null);
        // the plan goes through JSON, as it does between the UI and the server
        GenerationPlan plan = Json.MAPPER.readValue(Json.write(plan()), GenerationPlan.class);
        project = Files.createTempDirectory("specforge-usecase");
        GeneratorConfig cfg = Generation.config(project);
        cfg.basePackage = "com.example.shop";
        model = new DatabaseMapper().map(snap, plan, cfg, "Shop", cs.jdbcUrl());
        List<GeneratedFile> files = Generation.generate(model, cfg);
        Maven.write(files);
        sources = Generation.byPath(files);
    }

    private static GenerationPlan plan() {
        GenerationPlan p = new GenerationPlan();

        EntityDecision customers = new EntityDecision();
        ColumnDecision fullName = new ColumnDecision();
        fullName.name = "displayName";
        customers.columns.put("full_name", fullName);
        ColumnDecision notes = new ColumnDecision();
        notes.include = false;
        customers.columns.put("notes", notes);
        ColumnDecision email = new ColumnDecision();
        email.filter = false;
        customers.columns.put("email", email);
        p.entities.put("public.customers", customers);

        EntityDecision orders = new EntityDecision();
        RelationDecision customer = new RelationDecision();
        customer.name = "buyer";
        customer.dtoName = "buyerId";
        customer.inverse = true;
        orders.relations.put("customer_id", customer);
        p.entities.put("public.orders", orders);

        EntityDecision items = new EntityDecision();
        items.path = "order-lines";
        p.entities.put("public.order_items", items);

        EntityDecision tags = new EntityDecision();
        tags.controller = false;
        p.entities.put("public.tags", tags);

        EntityDecision products = new EntityDecision();
        products.controllerName = "CatalogController";
        p.entities.put("public.products", products);

        ControllerDecision checkout = new ControllerDecision();
        checkout.name = "Checkout";
        checkout.path = "checkout";
        checkout.operations.add(op("AGGREGATE_CREATE", "public.orders", null, "public.order_items"));
        checkout.operations.add(op("AGGREGATE_UPDATE", "public.orders", null, "public.order_items"));
        checkout.operations.add(op("WITH_CHILDREN", "public.orders", null, "public.order_items"));
        checkout.operations.add(op("CHILDREN", "public.orders", null, "public.order_items"));
        checkout.operations.add(op("ENTITY", "public.customers", "create", null));
        checkout.operations.add(op("ENTITY", "public.tags", "list", null));
        UseCaseOperation broken = op("CHILDREN", "public.tags", null, "public.customers");
        checkout.operations.add(broken);
        p.controllers.add(checkout);
        return p;
    }

    private static UseCaseOperation op(String kind, String entity, String operation, String child) {
        UseCaseOperation o = new UseCaseOperation();
        o.kind = kind;
        o.entity = entity;
        o.operation = operation;
        o.child = child;
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
    void mappingDecisionsAreApplied() {
        assertThat(source("/CustomerDto.java")).contains("String displayName").doesNotContain("notes").doesNotContain("fullName");
        assertThat(source("/Customer.java")).contains("@Column(name = \"full_name\"").contains("private List<Order> orders");
        assertThat(source("/Order.java")).contains("private Customer buyer");
        assertThat(source("/OrderDto.java")).contains("Long buyerId");
        assertThat(source("/OrderItemController.java")).contains("@RequestMapping(\"/order-lines\")");
        assertThat(sources.keySet()).anyMatch(k -> k.endsWith("/CatalogController.java")).noneMatch(k -> k.endsWith("/ProductController.java"));
    }

    @Test
    @Order(1)
    void operationsMoveAndControllersGo() {
        assertThat(sources.keySet()).as("tag controller removed (OPS-5)").noneMatch(k -> k.endsWith("/TagController.java"));
        assertThat(sources.keySet()).anyMatch(k -> k.endsWith("/TagService.java")).anyMatch(k -> k.endsWith("/TagRepository.java"));
        String customers = source("/CustomerController.java");
        assertThat(customers).as("create moved to the use case (OPS-4)").doesNotContain("@PostMapping").contains("@GetMapping");
        assertThat(source("/CustomerService.java")).contains("public CustomerDto create(");
        assertThat(source("/CheckoutController.java")).contains("@RequestMapping(\"/checkout\")").contains("createOrderWithOrderItems")
            .contains("listTags").contains("createCustomer");
        assertThat(source("/OrderItemRepository.java")).contains("findAllByOrder_Id(Long id)");
        assertThat(source("/OrderItemInput.java")).doesNotContain("Long orderId").contains("Integer lineNo");
        assertThat(model.messages).extracting(m -> m.text).anyMatch(t -> t.contains("Tag") && t.contains("left out"));
    }

    @Test
    @Order(2)
    void generatedProjectBuildsAndStarts() throws Exception {
        app = GeneratedApp.buildAndStart(project, cs, "/checkout/tags");
    }

    @Test
    @Order(3)
    void useCasesWork() throws Exception {
        HttpResponse<String> ada = app.call("POST", "/checkout/customers", Map.of("email", "ada@example.com", "displayName", "Ada"));
        assertThat(ada.statusCode()).as(ada.body()).isEqualTo(201);
        long customer = json(ada).get("id").asLong();
        assertThat(app.call("POST", "/customers", Map.of("email", "x@example.com", "displayName", "X")).statusCode())
            .as("create moved out of /customers").isIn(404, 405);

        long product = json(app.call("POST", "/products", Map.of("sku", "SKU-1", "name", "Lamp", "price", 10))).get("id").asLong();

        // aggregate create: parent and children in one transaction
        Map<String, Object> order = Map.of("buyerId", customer, "orderedOn", "2026-10-17");
        HttpResponse<String> created = app.call("POST", "/checkout/orders", Map.of("order", order, "orderItems", List.of(
            Map.of("lineNo", 1, "productId", product, "quantity", 2),
            Map.of("lineNo", 2, "productId", product, "quantity", 5))));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode body = json(created);
        long orderId = body.get("order").get("id").asLong();
        assertThat(body.get("order").get("status").asText()).isEqualTo("NEW");
        assertThat(body.get("orderItems")).hasSize(2);
        assertThat(body.get("orderItems").get(0).get("orderId").asLong()).isEqualTo(orderId);
        assertThat(created.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l).endsWith("/checkout/orders/" + orderId));

        // all or nothing: a bad child rolls the parent back
        long before = json(app.call("GET", "/orders", null)).get("totalElements").asLong();
        HttpResponse<String> bad = app.call("POST", "/checkout/orders", Map.of("order", order, "orderItems", List.of(
            Map.of("lineNo", 1, "productId", product, "quantity", 1),
            Map.of("lineNo", 2, "productId", product, "quantity", 0))));
        assertThat(bad.statusCode()).as(bad.body()).isEqualTo(409);
        assertThat(json(app.call("GET", "/orders", null)).get("totalElements").asLong()).as("rolled back").isEqualTo(before);

        // parent with children, children of parent
        JsonNode read = json(app.call("GET", "/checkout/orders/" + orderId, null));
        assertThat(read.get("orderItems")).hasSize(2);
        JsonNode page = json(app.call("GET", "/checkout/orders/" + orderId + "/order-lines?sort=quantity,desc", null));
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        assertThat(page.get("content").get(0).get("quantity").asInt()).isEqualTo(5);
        assertThat(app.call("GET", "/checkout/orders/999999/order-lines", null).statusCode()).isEqualTo(404);

        // aggregate update replaces the children
        HttpResponse<String> updated = app.call("PUT", "/checkout/orders/" + orderId, Map.of("order", Map.of("buyerId", customer,
            "orderedOn", "2026-10-18", "status", "PAID"), "orderItems", List.of(Map.of("lineNo", 7, "productId", product, "quantity", 3))));
        assertThat(updated.statusCode()).as(updated.body()).isEqualTo(200);
        assertThat(json(updated).get("order").get("status").asText()).isEqualTo("PAID");
        assertThat(json(app.call("GET", "/checkout/orders/" + orderId, null)).get("orderItems")).hasSize(1);

        // an entity operation of a removed controller, served by the use case
        app.call("POST", "/checkout/customers", Map.of("email", "b@example.com", "displayName", "B"));
        assertThat(app.call("GET", "/tags", null).statusCode()).as("tag controller removed").isEqualTo(404);
        assertThat(app.call("GET", "/checkout/tags", null).statusCode()).isEqualTo(200);
    }
}
