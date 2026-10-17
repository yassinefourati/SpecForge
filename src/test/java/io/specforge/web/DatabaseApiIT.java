package io.specforge.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.specforge.source.ConnectionSettings;
import io.specforge.support.Postgres;
import io.specforge.util.Json;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The database flow through the web API, as the UI drives it; no response may carry a secret (S3). */
class DatabaseApiIT {

    @TempDir
    static Path home;
    static ConnectionSettings cs;
    static Api api;

    @BeforeAll
    static void setUp() throws Exception {
        cs = Postgres.database(List.of(Path.of("src/test/resources/fixtures/prior-art/schema.sql")));
        api = new Api(home, null);
    }

    private static JsonNode call(String method, String path, Object body) throws Exception {
        return call(method, path, Map.of(), body, 200);
    }

    private static JsonNode call(String method, String path, Map<String, String> query, Object body, int expected) throws Exception {
        Api.Resp r = api.handle(method, path, query, body == null ? new byte[0] : Json.write(body).getBytes(StandardCharsets.UTF_8));
        String text = new String(r.body, StandardCharsets.UTF_8);
        assertThat(text).as("response of %s %s leaks a secret", method, path).doesNotContain(Postgres.PASSWORD).doesNotContain("Wr0ngPassw0rdXyz");
        assertThat(r.status).as("%s %s: %s", method, path, text).isEqualTo(expected);
        return r.contentType.startsWith("application/json") ? Json.MAPPER.readTree(text) : null;
    }

    @Test
    void fromSavedConnectionToGeneratedPreview() throws Exception {
        JsonNode kinds = call("GET", "/api/db/kinds", null);
        assertThat(kinds.findValues("id").stream().map(JsonNode::asText)).contains("POSTGRESQL", "ORACLE");
        assertThat(kinds.get(0).get("available").asBoolean()).as("PostgreSQL driver is bundled").isTrue();

        Map<String, Object> conn = Map.of("name", "Prior art", "kind", "POSTGRESQL", "host", cs.host, "port", cs.port,
            "database", cs.database, "user", cs.user, "password", cs.password, "schemas", List.of("administration"));
        JsonNode saved = call("POST", "/api/db/connections", conn);
        String id = saved.get("id").asText();
        assertThat(saved.get("secrets").get("password").get("set").asBoolean()).isTrue();
        assertThat(saved.get("secrets").get("password").get("masked").asText()).startsWith("••••");
        assertThat(Files.readString(home.resolve("connections.json"))).as("stored encrypted").doesNotContain(cs.password).contains("v1:");

        JsonNode info = call("POST", "/api/db/test", Map.of("connectionId", id));
        assertThat(info.get("serverVersion").asText()).startsWith("PostgreSQL");

        JsonNode started = call("POST", "/api/db/introspect", Map.of("connectionId", id, "schemas", List.of("administration")));
        JsonNode job = null;
        for (int i = 0; i < 300; i++) {
            job = call("GET", "/api/jobs/" + started.get("jobId").asText(), null);
            if (!job.get("state").asText().equals("RUNNING")) break;
            Thread.sleep(100);
        }
        assertThat(job.get("state").asText()).as(job.toString()).isEqualTo("DONE");
        String snapshotId = job.get("result").get("snapshotId").asText();

        JsonNode cached = call("POST", "/api/db/introspect", Map.of("connectionId", id, "schemas", List.of("administration")));
        assertThat(cached.get("snapshotId").asText()).as("same settings reuse the snapshot (INT-9)").isEqualTo(snapshotId);

        JsonNode snap = call("GET", "/api/db/snapshot/" + snapshotId, Map.of("q", "user"), null, 200);
        assertThat(snap.get("total").asInt()).isEqualTo(2); // users, user_roles

        JsonNode plan = call("POST", "/api/db/plan/default", Map.of("snapshotId", snapshotId));
        assertThat(plan.get("entities").size()).isEqualTo(9);
        assertThat(plan.get("joinTables").get(0).asText()).isEqualTo("administration.role_permissions");
        assertThat(plan.get("plan").get("rulesetVersion").asText()).isNotEmpty();

        Map<String, Object> decisions = Map.of("select", Map.of("exclude", List.of("administration.audit_logs")),
            "entities", Map.of("administration.users", Map.of("className", "Member", "operations", Map.of("delete", false))));
        JsonNode preview = call("POST", "/api/generate/preview", Map.of("snapshotId", snapshotId, "plan", decisions,
            "config", Map.of("targetDir", home.resolve("out").toString())));
        List<String> paths = preview.findValues("path").stream().map(JsonNode::asText).toList();
        assertThat(paths).anyMatch(p -> p.endsWith("/Member.java")).noneMatch(p -> p.endsWith("/AuditLog.java"));
        String controller = preview.findValues("content").stream().map(JsonNode::asText).filter(c -> c.contains("class MemberController")).findFirst().orElseThrow();
        assertThat(controller).doesNotContain("@DeleteMapping");
    }

    @Test
    void wrongPasswordIsAClearErrorWithoutTheSecret() throws Exception {
        Map<String, Object> conn = Map.of("kind", "POSTGRESQL", "host", cs.host, "port", cs.port, "database", cs.database,
            "user", cs.user, "password", "Wr0ngPassw0rdXyz");
        JsonNode err = call("POST", "/api/db/test", Map.of(), Map.of("connection", conn), 422);
        assertThat(err.get("code").asText()).isEqualTo("AUTH_FAILED");
    }

    @Test
    void noSecretReachesTheSnapshotFolder() throws Exception {
        Path dir = home.resolve("snapshots");
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : (Iterable<Path>) s::iterator) assertThat(Files.readString(f)).doesNotContain(cs.password);
        }
    }
}
