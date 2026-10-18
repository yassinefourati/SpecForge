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
import io.specforge.support.TestIssuer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.specforge.support.GeneratedApp.json;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * List endpoints with text search, ranges and lenient sorting (CONTRACT-3), behind a JWT resource server with
 * Keycloak realm roles and a write role (CONTRACT-7), on PostgreSQL.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ListsAndJwtIT {

    static final String AUDIENCE = "shop-api";
    static ConnectionSettings cs;
    static Path project;
    static TestIssuer issuer;
    static GeneratedApp app;
    static String reader;
    static String editor;

    @BeforeAll
    static void generate() throws Exception {
        cs = Engines.database(SourceKind.POSTGRESQL, Path.of("src/test/resources/fixtures/engines/shop-postgresql.sql"));
        Snapshot snap = new JdbcConnector(cs).introspect(List.of("public"), null);
        project = Files.createTempDirectory("specforge-lists");
        GeneratorConfig cfg = Generation.config(project);
        cfg.basePackage = "com.example.shop";
        Generation.style(cfg, "MODERN");
        cfg.lists.textSearch = "Q";
        cfg.lists.rangeFilters = true;
        cfg.lists.unknownSort = "IGNORE";
        cfg.security.databaseAuth = "JWT";
        cfg.security.jwtRoles = "KEYCLOAK";
        cfg.security.writeRole = "editor";
        ApiModel model = new DatabaseMapper().map(snap, new GenerationPlan(), cfg, "Shop", cs.jdbcUrl());
        List<GeneratedFile> files = Generation.generate(model, cfg);
        Maven.write(files);
        issuer = new TestIssuer();
        reader = issuer.bearer(AUDIENCE, List.of("offline_access"));
        editor = issuer.bearer(AUDIENCE, List.of("editor"));
    }

    @AfterAll
    static void stop() {
        if (app != null) app.close();
        if (issuer != null) issuer.close();
    }

    @Test
    @Order(1)
    void generatedProjectBuildsAndStarts() throws Exception {
        app = GeneratedApp.buildAndStart(project, cs, "/customers", Map.of("JWT_ISSUER_URI", issuer.issuer,
            "JWT_JWK_SET_URI", issuer.jwkSetUri, "JWT_AUDIENCE", AUDIENCE), editor);
    }

    @Test
    @Order(2)
    void tokensAndRoles() throws Exception {
        assertThat(app.anonymous("GET", "/customers").statusCode()).isEqualTo(401);
        assertThat(app.call("GET", "/customers", null, reader).statusCode()).as("any signed-in caller reads").isEqualTo(200);
        assertThat(app.call("POST", "/customers", Map.of("email", "r@example.com", "fullName", "R"), reader).statusCode())
            .as("writing needs the editor realm role").isEqualTo(403);
        assertThat(app.call("POST", "/customers", Map.of("email", "e@example.com", "fullName", "E"), editor).statusCode()).isEqualTo(201);

        assertThat(app.call("GET", "/customers", null, issuer.bearer("other-api", List.of("editor"))).statusCode()).as("audience").isEqualTo(401);
        assertThat(app.call("GET", "/customers", null, issuer.bearer("http://evil/realms/test", AUDIENCE, List.of(), Instant.now().plusSeconds(300)))
            .statusCode()).as("issuer").isEqualTo(401);
        assertThat(app.call("GET", "/customers", null, issuer.bearer(issuer.issuer, AUDIENCE, List.of(), Instant.now().minusSeconds(600)))
            .statusCode()).as("expired").isEqualTo(401);
        assertThat(app.call("GET", "/customers", null, editor.substring(0, editor.length() - 4) + "AAAA").statusCode()).as("signature").isEqualTo(401);
    }

    @Test
    @Order(3)
    void textSearchRangesAndSort() throws Exception {
        app.call("POST", "/customers", Map.of("email", "ada@example.com", "fullName", "Ada Lovelace"));
        app.call("POST", "/customers", Map.of("email", "alan@example.com", "fullName", "Alan Turing"));
        app.call("POST", "/customers", Map.of("email", "pct@example.com", "fullName", "50% off"));

        assertThat(total("/customers?q=LOVE")).as("case-insensitive contains").isEqualTo(1);
        assertThat(total("/customers?q=example.com")).as("searches email too").isGreaterThanOrEqualTo(3);
        assertThat(total("/customers?q=" + enc("50%"))).as("% is a literal").isEqualTo(1);
        assertThat(total("/customers?q=" + enc("%"))).as("% alone matches only a literal %").isEqualTo(1);
        assertThat(total("/customers?q=")).as("blank q is no filter").isEqualTo(total("/customers"));

        app.call("POST", "/products", Map.of("sku", "A", "name", "Cheap", "price", 5));
        app.call("POST", "/products", Map.of("sku", "B", "name", "Mid", "price", 15));
        app.call("POST", "/products", Map.of("sku", "C", "name", "Dear", "price", 25));
        assertThat(total("/products?priceFrom=10&priceTo=20")).as("inclusive range").isEqualTo(1);
        assertThat(total("/products?priceFrom=15")).isEqualTo(2);
        assertThat(total("/customers?createdAtFrom=2999-01-01T00:00:00")).isZero();
        assertThat(app.call("GET", "/products?priceFrom=cheap", null).statusCode()).as("unparsable bound").isEqualTo(400);

        HttpResponse<String> sorted = app.call("GET", "/products?sort=nope,asc", null);
        assertThat(sorted.statusCode()).as("unknown sort ignored").isEqualTo(200);
        assertThat(json(sorted).get("content").get(0).get("sku").asText()).as("key order by default").isEqualTo("A");
        assertThat(json(app.call("GET", "/products?sort=price,desc", null)).get("content").get(0).get("sku").asText()).isEqualTo("C");
        assertThat(app.call("GET", "/products?nope=1", null).statusCode()).as("unknown filter still rejected").isEqualTo(400);
    }

    private static long total(String path) throws Exception {
        HttpResponse<String> r = app.call("GET", path, null);
        assertThat(r.statusCode()).as(path + ": " + r.body()).isEqualTo(200);
        return json(r).get("totalElements").asLong();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
