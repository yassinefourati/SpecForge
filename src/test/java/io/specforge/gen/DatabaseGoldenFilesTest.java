package io.specforge.gen;

import io.specforge.config.GeneratorConfig;
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
import io.specforge.support.Generation;
import io.specforge.support.Golden;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Database-flow output must not change unintentionally (P2, S5, STYLE-8). Uses the in-process H2 "shop" fixture, so
 * it runs in the fast stage without Docker: identity, sequence and assigned keys, a composite key, a join table, and
 * one use-case controller with every operation kind.
 */
class DatabaseGoldenFilesTest {

    static Snapshot snap;
    static String jdbcUrl;

    @TempDir
    Path target;

    @BeforeAll
    static void introspect() throws Exception {
        ConnectionSettings cs = Engines.database(SourceKind.H2, Path.of("src/test/resources/fixtures/engines/shop-h2.sql"));
        snap = new JdbcConnector(cs).introspect(cs.schemas, null);
        // the file location differs per run; generated output must not depend on it
        jdbcUrl = "jdbc:h2:file:./shop";
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "shop-h2-default, '',     ''",
        "shop-h2-modern,  MODERN, ''",
        // CONTRACT-3, CONTRACT-7: q, ranges, lenient sort; Keycloak JWT with a write role
        "shop-h2-lists-jwt, MODERN, lists+jwt",
        // CONTRACT-2, CONTRACT-4: a module for admin-template, tables created by a Flyway migration
        "shop-h2-admin-template, MODERN, lists+admin",
        // MIG-1, MIG-2, TEST-1, TEST-2: Flyway with the schema as V1, controller tests, the schema test on H2 in memory
        "shop-h2-checks, MODERN, checks",
        // ARCH-1: one package per table, with a use case spanning two of them
        "shop-h2-feature, MODERN, feature",
        // ARCH-3 … ARCH-5: two Spring Modulith modules; order items → products crosses them and becomes an id
        "shop-h2-modular, MODERN, modular",
        // ARCH-6: ports and adapters (the composite-key table and the use case are left out, with warnings)
        "shop-h2-hexagonal, MODERN, hexagonal",
    })
    void databaseFlowOutputIsStable(String scenario, String style, String extras) throws Exception {
        GeneratorConfig cfg = Generation.config(target);
        cfg.basePackage = "com.example.shop";
        if (!style.isEmpty()) Generation.style(cfg, style);
        if (extras.contains("lists")) {
            cfg.lists.textSearch = "Q";
            cfg.lists.rangeFilters = true;
            cfg.lists.unknownSort = "IGNORE";
        }
        if (extras.contains("admin")) {
            cfg.contract.kind = "ADMIN_TEMPLATE";
            cfg.contract.module = "shop";
            cfg.contract.migrationVersion = 12;
        }
        if (extras.contains("checks")) {
            cfg.migrations.flyway = "SCHEMA";
            cfg.tests.controllers = true;
            cfg.tests.application = true;
        }
        if (extras.contains("feature")) cfg.architecture = "FEATURE";
        if (extras.contains("modular")) cfg.architecture = "MODULAR";
        if (extras.contains("hexagonal")) cfg.architecture = "HEXAGONAL";
        if (extras.contains("jwt")) {
            cfg.security.databaseAuth = "JWT";
            cfg.security.jwtRoles = "KEYCLOAK";
            cfg.security.writeRole = "ROLE_editor";
        }
        GenerationPlan plan = plan();
        if (extras.contains("modular")) {
            for (String t : List.of("PRODUCTS", "TAGS", "COUNTRIES")) plan.modules.put(key(t), "catalog");
            for (String t : List.of("CUSTOMERS", "ORDERS", "ORDER_ITEMS")) plan.modules.put(key(t), "sales");
        }
        ApiModel model = new DatabaseMapper().map(snap, plan, cfg, "Shop", jdbcUrl);
        Map<String, String> files = new TreeMap<>(Generation.byPath(Generation.generate(model, cfg)));
        Golden.assertMatches(scenario, files);
    }

    private static GenerationPlan plan() {
        GenerationPlan p = new GenerationPlan();
        ControllerDecision checkout = new ControllerDecision();
        checkout.name = "Checkout";
        checkout.path = "checkout";
        checkout.operations.add(op("AGGREGATE_CREATE", "ORDERS", null, "ORDER_ITEMS"));
        checkout.operations.add(op("AGGREGATE_UPDATE", "ORDERS", null, "ORDER_ITEMS"));
        checkout.operations.add(op("WITH_CHILDREN", "ORDERS", null, "ORDER_ITEMS"));
        checkout.operations.add(op("CHILDREN", "ORDERS", null, "ORDER_ITEMS"));
        checkout.operations.add(op("ENTITY", "CUSTOMERS", "create", null));
        p.controllers.add(checkout);
        return p;
    }

    private static UseCaseOperation op(String kind, String table, String operation, String child) {
        UseCaseOperation o = new UseCaseOperation();
        o.kind = kind;
        o.entity = key(table);
        o.operation = operation;
        o.child = child == null ? null : key(child);
        return o;
    }

    private static String key(String table) {
        return snap.tables.stream().filter(t -> t.name.equalsIgnoreCase(table)).findFirst()
            .orElseThrow(() -> new AssertionError("no table " + table)).qualifiedName();
    }
}
