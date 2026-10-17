package io.specforge.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.specforge.gen.TemplateManager;
import io.specforge.util.Json;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot Studio through the web API (STU-1 … STU-7, STU-10): sets, customize → preview → generate → reset
 * round-trip byte for byte, template errors at their line, Java syntax errors, variables, ZIP export and import.
 */
class StudioApiTest {

    @TempDir
    static Path home;
    static Api api;
    static String specId;

    @BeforeAll
    static void setUp() throws Exception {
        api = new Api(home, null);
        JsonNode analyzed = call("POST", "/api/spec/analyze", Map.of("mode", "text", "content", Files.readString(Path.of("samples/petstore.yaml")), "name", "petstore.yaml"), 200);
        specId = analyzed.get("specId").asText();
    }

    private static JsonNode call(String method, String path, Object body, int expected) throws Exception {
        return call(method, path, Map.of(), body == null ? new byte[0] : Json.write(body).getBytes(StandardCharsets.UTF_8), expected);
    }

    private static JsonNode call(String method, String path, Map<String, String> query, byte[] body, int expected) throws Exception {
        Api.Resp r = api.handle(method, path, query, body);
        String text = r.contentType.startsWith("application/json") ? new String(r.body, StandardCharsets.UTF_8) : "";
        assertThat(r.status).as("%s %s: %s", method, path, text).isEqualTo(expected);
        return text.isEmpty() ? null : Json.MAPPER.readTree(text);
    }

    private static Map<String, Object> request(String set) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("templatesDir", set == null ? "" : home.resolve("templates").resolve(set).toString());
        config.put("targetDir", home.resolve("out").toString());
        return Map.of("specId", specId, "config", config);
    }

    private static Map<String, Object> preview(String set, String template, String draft) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("set", set);
        m.put("template", template);
        m.put("draft", draft);
        m.put("request", request(set));
        return m;
    }

    private static String generated(String set, String suffix) throws Exception {
        JsonNode files = call("POST", "/api/generate/preview", request(set), 200).get("files");
        for (JsonNode f : files) if (f.get("path").asText().endsWith(suffix)) return f.get("content").asText();
        throw new AssertionError("no " + suffix);
    }

    @Test
    void customizePreviewGenerateResetRoundTrips() throws Exception {
        call("POST", "/api/studio/sets", Map.of("name", "team"), 200);
        String before = generated("team", "/Pet.java");
        JsonNode list = call("GET", "/api/studio/sets/team/templates", null, 200);
        assertThat(list.get("templates").findValuesAsText("status")).containsOnly("DEFAULT");

        JsonNode t = call("POST", "/api/studio/sets/team/customize", Map.of("template", "entity.java.ftl"), 200);
        assertThat(t.get("status").asText()).isEqualTo("CUSTOM");
        assertThat(generated("team", "/Pet.java")).as("an untouched customized copy changes nothing").isEqualTo(before);

        String edited = t.get("content").asText().replace("public class [=", "/** Team style. */\npublic class [=");
        JsonNode p = call("POST", "/api/studio/preview", preview("team", "entity.java.ftl", edited), 200);
        assertThat(p.get("output").asText()).contains("/** Team style. */");
        assertThat(p.get("defaultOutput").asText()).doesNotContain("Team style");
        assertThat(p.get("javaErrors")).isEmpty();
        assertThat(p.get("items").size()).isGreaterThan(1);
        assertThat(p.get("model").findValuesAsText("name")).contains("entity", "options", "api");

        call("PUT", "/api/studio/sets/team/template", Map.of("template", "entity.java.ftl", "content", edited), 200);
        assertThat(generated("team", "/Pet.java")).contains("/** Team style. */");

        call("POST", "/api/studio/sets/team/reset", Map.of("template", "entity.java.ftl"), 200);
        assertThat(generated("team", "/Pet.java")).as("reset round-trips byte for byte").isEqualTo(before);
    }

    @Test
    void templateErrorsPointAtTheirLine() throws Exception {
        call("POST", "/api/studio/sets", Map.of("name", "broken"), 200);
        String src = TemplateManager.bundledSource("entity.java.ftl");
        String bad = src.replaceFirst("\n", "\n[=entity.noSuchThing.deeper]\n");
        JsonNode p = call("POST", "/api/studio/preview", preview("broken", "entity.java.ftl", bad), 200);
        assertThat(p.get("error").get("line").asInt()).isEqualTo(2);
        assertThat(p.get("error").get("message").asText()).contains("noSuchThing");

        String syntax = src.replaceFirst("\n", "\n<#if>\n");
        assertThat(call("POST", "/api/studio/preview", preview("broken", "entity.java.ftl", syntax), 200).get("error").get("line").asInt()).isEqualTo(2);

        String java = src.replace("public class [=", "public clas [=");
        JsonNode jp = call("POST", "/api/studio/preview", preview("broken", "entity.java.ftl", java), 200);
        assertThat(jp.get("javaErrors").size()).as("rendered Java is syntax-checked (STU-5)").isPositive();
    }

    @Test
    void runawayTemplatesAreStopped() throws Exception {
        call("POST", "/api/studio/sets", Map.of("name", "loop"), 200);
        String huge = "<#list 1..100000000 as i>[=i] some padding text to grow the output quickly</#list>";
        JsonNode p = call("POST", "/api/studio/preview", preview("loop", "entity.java.ftl", huge), 200);
        assertThat(p.get("error").get("message").asText()).containsAnyOf("characters", "did not finish");
        String sneaky = "[=\"freemarker.template.utility.Execute\"?new()(\"whoami\")]";
        JsonNode s = call("POST", "/api/studio/preview", preview("loop", "entity.java.ftl", sneaky), 200);
        assertThat(s.get("error").get("message").asText()).as("sandbox (STU-10)").isNotBlank();
    }

    @Test
    void compilingWithACustomizedBuildFileNeedsConsent() throws Exception {
        call("POST", "/api/studio/sets", Map.of("name", "imported"), 200);
        call("POST", "/api/studio/sets/imported/customize", Map.of("template", "pom.xml.ftl"), 200);
        Map<String, Object> verify = new LinkedHashMap<>();
        verify.put("set", "imported");
        verify.put("request", request("imported"));
        JsonNode refused = call("POST", "/api/studio/verify", verify, 409);
        assertThat(refused.get("error").asText()).contains("pom.xml.ftl").contains("trust");
    }

    @Test
    void setsExportAndImportAsZip() throws Exception {
        call("POST", "/api/studio/sets", Map.of("name", "shared"), 200);
        call("POST", "/api/studio/sets/shared/customize", Map.of("template", "controller.java.ftl"), 200);
        Api.Resp zip = api.handle("GET", "/api/studio/sets/shared/export", Map.of(), new byte[0]);
        assertThat(zip.contentType).isEqualTo("application/zip");

        JsonNode imported = call("POST", "/api/studio/sets/copy/import", Map.of(), zip.body, 200);
        assertThat(imported.get("imported").asInt()).isEqualTo(2); // the template and .studio.json
        JsonNode list = call("GET", "/api/studio/sets/copy/templates", null, 200);
        boolean custom = false;
        for (JsonNode t : list.get("templates")) if (t.get("template").asText().equals("controller.java.ftl")) custom = t.get("status").asText().equals("CUSTOM");
        assertThat(custom).isTrue();

        ByteArrayOutputStream evil = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(evil)) {
            z.putNextEntry(new ZipEntry("../outside.ftl"));
            z.write("x".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        call("POST", "/api/studio/sets/evil/import", Map.of(), evil.toByteArray(), 400);
        assertThat(Files.exists(home.resolve("templates").resolve("outside.ftl"))).isFalse();
        call("POST", "/api/studio/sets", Map.of("name", "../escape"), 400);
    }
}
