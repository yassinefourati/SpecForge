package io.specforge.db;

import com.fasterxml.jackson.databind.JsonNode;
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
 * Milestone 1 walking skeleton against real PostgreSQL: prior-art fixture → snapshot → default plan → generated
 * project → it compiles, starts with ddl-auto=validate, and passes CRUD calls (§18.3, GEN-6, GEN-7, OPS-1).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatabaseFlowIT {

    static ConnectionSettings cs;
    static Path project;
    static Map<String, String> sources;
    static GeneratedApp app;

    @BeforeAll
    static void generate() throws Exception {
        cs = Postgres.database(List.of(Path.of("src/test/resources/fixtures/prior-art/schema.sql")));
        Snapshot snap = new JdbcConnector(cs).introspect(List.of("administration"), null);
        project = Files.createTempDirectory("specforge-dbflow");
        GeneratorConfig cfg = Generation.config(project);
        cfg.basePackage = "com.example.admin";
        ApiModel model = new DatabaseMapper().map(snap, new GenerationPlan(), cfg, "Administration", cs.jdbcUrl());
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

    // ------------------------------------------------------------------ prior-art regression (§18.3)

    @Test
    @Order(1)
    void auditTimestampIsNotOptimisticLocking() {
        assertThat(source("/AuditLog.java")).doesNotContain("@Version");
        assertThat(source("/Notification.java")).doesNotContain("@Version");
    }

    @Test
    @Order(1)
    void jsonbIsAJsonColumn() {
        assertThat(source("/ErrorLog.java")).contains("@JdbcTypeCode(SqlTypes.JSON)").doesNotContain("UUID");
    }

    @Test
    @Order(1)
    void namesAreInflectedOnce() {
        assertThat(sources.values()).noneMatch(s -> s.contains("roleses") || s.contains("permissionses") || s.contains("notificationses"));
        assertThat(source("/Role.java")).contains("Set<Permission> permissions");
    }

    @Test
    @Order(1)
    void joinTableAndCompositeKey() {
        assertThat(sources.keySet()).noneMatch(p -> p.endsWith("/RolePermission.java"));
        assertThat(source("/UserRole.java")).contains("@EmbeddedId").contains("@MapsId(\"userId\")").contains("@MapsId(\"roleId\")");
    }

    @Test
    @Order(1)
    void keysComeFromFacts() {
        String config = source("/ConfigurationEntity.java");
        assertThat(config).contains("@Id").doesNotContain("@GeneratedValue");
        assertThat(source("/ErrorLog.java")).contains("sequenceName = \"error_logs_seq\"").contains("allocationSize = 1");
        assertThat(source("/AuditLog.java")).contains("sequenceName = \"audit_logs_audit_id_seq\"").contains("allocationSize = 1");
        assertThat(source("/User.java")).contains("GenerationType.IDENTITY");
    }

    @Test
    @Order(1)
    void uniqueConstraintsAreEmitted() {
        assertThat(source("/User.java")).contains("@UniqueConstraint(columnNames = {\"email\"})").contains("@UniqueConstraint(columnNames = {\"username\"})");
    }

    @Test
    @Order(1)
    void noForbiddenPatterns() {
        for (Map.Entry<String, String> e : sources.entrySet()) {
            if (!e.getKey().endsWith(".java")) continue;
            assertThat(e.getValue()).as(e.getKey())
                // GEN-7: an exception turned into null / false
                .doesNotContainPattern("catch \\([^)]*\\)\\s*\\{\\s*(//[^\\n]*\\s*)*return (null|false)")
                .doesNotContain("@CrossOrigin").doesNotContain("@Data")
                .doesNotContain("Cloneable").doesNotContain("java.util.Date").doesNotContain("@Autowired");
        }
        assertThat(source("application.properties")).contains("ddl-auto=validate").contains("${DB_PASSWORD}")
            .doesNotContain(Postgres.PASSWORD);
    }

    // ------------------------------------------------------------------ build, start, CRUD

    @Test
    @Order(2)
    void generatedProjectBuildsAndStarts() throws Exception {
        app = GeneratedApp.buildAndStart(project, cs, "/users");
    }

    @Test
    @Order(3)
    void requiresAuthentication() throws Exception {
        assertThat(app.anonymous("GET", "/users").statusCode()).isEqualTo(401);
    }

    @Test
    @Order(3)
    void fullCrudOnAnIdentityTable() throws Exception {
        HttpResponse<String> created = call("POST", "/users", Map.of("firstName", "Ada", "lastName", "Lovelace",
            "email", "ada@example.com", "username", "ada", "password", "secret", "status", "ACTIVE"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        long id = json(created).get("userId").asLong();
        assertThat(created.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l).endsWith("/users/" + id));

        assertThat(json(call("GET", "/users/" + id, null)).get("email").asText()).isEqualTo("ada@example.com");
        JsonNode page = json(call("GET", "/users?username=ada&sort=email,asc", null));
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);

        HttpResponse<String> patched = call("PATCH", "/users/" + id, Map.of("status", "LOCKED"));
        assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
        assertThat(json(patched).get("status").asText()).isEqualTo("LOCKED");
        assertThat(json(patched).get("email").asText()).isEqualTo("ada@example.com");

        assertThat(call("GET", "/users?nope=1", null).statusCode()).isEqualTo(400);
        assertThat(call("GET", "/users?sort=password,asc", null).statusCode()).isEqualTo(200);
        assertThat(call("POST", "/users", Map.of("firstName", "x")).statusCode()).isEqualTo(400);
        assertThat(call("POST", "/users", Map.of("firstName", "Ada", "lastName", "L", "email", "ada@example.com",
            "username", "ada2", "password", "p", "status", "ACTIVE")).statusCode()).as("duplicate email").isEqualTo(409);

        assertThat(call("DELETE", "/users/" + id, null).statusCode()).isEqualTo(204);
        assertThat(call("GET", "/users/" + id, null).statusCode()).isEqualTo(404);
    }

    @Test
    @Order(3)
    void compositeKeyManyToManyJsonSequenceAndAssignedKey() throws Exception {
        long user = json(call("POST", "/users", Map.of("firstName", "Alan", "lastName", "Turing", "email", "alan@example.com",
            "username", "alan", "password", "secret", "status", "ACTIVE"))).get("userId").asLong();
        long perm = json(call("POST", "/permissions", Map.of("permissionName", "read"))).get("permissionId").asLong();
        HttpResponse<String> role = call("POST", "/roles", Map.of("roleName", "admin", "permissionIds", List.of(perm)));
        assertThat(role.statusCode()).as(role.body()).isEqualTo(201);
        long roleId = json(role).get("roleId").asLong();
        assertThat(json(call("GET", "/roles/" + roleId, null)).get("permissionIds").get(0).asLong()).isEqualTo(perm);

        HttpResponse<String> link = call("POST", "/user-roles", Map.of("userId", user, "roleId", roleId));
        assertThat(link.statusCode()).as(link.body()).isEqualTo(201);
        assertThat(call("GET", "/user-roles/" + user + "/" + roleId, null).statusCode()).isEqualTo(200);
        assertThat(call("POST", "/user-roles", Map.of("userId", user, "roleId", roleId)).statusCode()).isEqualTo(409);

        long e1 = json(call("POST", "/error-logs", Map.of("errorMessage", "boom", "severity", "HIGH", "details", "{\"code\": 42}"))).get("errorId").asLong();
        long e2 = json(call("POST", "/error-logs", Map.of("errorMessage", "boom again", "severity", "LOW"))).get("errorId").asLong();
        assertThat(e2).isEqualTo(e1 + 1);
        assertThat(json(call("GET", "/error-logs/" + e1, null)).get("details").asText()).contains("42");

        assertThat(call("POST", "/configurations", Map.of("configKey", "site.name", "configValue", "SpecForge")).statusCode()).isEqualTo(201);
        assertThat(call("POST", "/configurations", Map.of("configKey", "site.name", "configValue", "again")).statusCode()).isEqualTo(409);
        assertThat(json(call("GET", "/configurations/site.name", null)).get("configValue").asText()).isEqualTo("SpecForge");
    }

    private static HttpResponse<String> call(String method, String path, Object body) throws Exception {
        return app.call(method, path, body);
    }
}
