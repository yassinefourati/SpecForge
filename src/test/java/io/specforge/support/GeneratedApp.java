package io.specforge.support;

import com.fasterxml.jackson.databind.JsonNode;
import io.specforge.source.ConnectionSettings;
import io.specforge.util.Json;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** A generated database-first project, built with Maven and running against the test database (OPS-1). */
public final class GeneratedApp implements AutoCloseable {

    private static final String AUTH = "Basic " + Base64.getEncoder().encodeToString("user:changeme".getBytes(StandardCharsets.UTF_8));
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final Process process;
    private final Path log;
    public final String base;

    private GeneratedApp(Process process, Path log, String base) {
        this.process = process;
        this.log = log;
        this.base = base;
    }

    /** Packages the project, starts it with DB_URL / DB_USERNAME / DB_PASSWORD, and waits until {@code probePath} answers 200. */
    public static GeneratedApp buildAndStart(Path project, ConnectionSettings cs, String probePath) throws Exception {
        Maven.Result build = Maven.run(project, "-DskipTests", "package");
        assertThat(build.ok()).as("generated project does not build:%n%s", build.errors()).isTrue();
        Path jar;
        try (Stream<Path> s = Files.list(project.resolve("target"))) {
            jar = s.filter(p -> p.toString().endsWith(".jar") && !p.toString().endsWith("-plain.jar")).findFirst().orElseThrow();
        }
        int port;
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        Path log = project.resolve("app.log");
        ProcessBuilder pb = new ProcessBuilder(ProcessHandle.current().info().command().orElse("java"), "-jar", jar.toString(), "--server.port=" + port)
            .redirectErrorStream(true).redirectOutput(log.toFile());
        pb.environment().put("DB_URL", cs.jdbcUrl());
        pb.environment().put("DB_USERNAME", cs.user);
        pb.environment().put("DB_PASSWORD", cs.password);
        GeneratedApp app = new GeneratedApp(pb.start(), log, "http://localhost:" + port);
        long deadline = System.currentTimeMillis() + 120_000;
        while (true) {
            assertThat(app.process.isAlive()).as("generated app exited:%n%s", app.tail()).isTrue();
            try {
                if (app.call("GET", probePath, null).statusCode() == 200) return app;
            } catch (IOException notYet) {
                // still starting
            }
            assertThat(System.currentTimeMillis()).as("generated app did not start in time:%n%s", app.tail()).isLessThan(deadline);
            Thread.sleep(500);
        }
    }

    /** An authenticated call; PATCH bodies are sent as merge-patch. */
    public HttpResponse<String> call(String method, String path, Object body) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30)).header("Authorization", AUTH);
        if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody());
        else b.header("Content-Type", method.equals("PATCH") ? "application/merge-patch+json" : "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A call without credentials. */
    public HttpResponse<String> anonymous(String method, String path) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).method(method, HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    public static JsonNode json(HttpResponse<String> r) throws IOException {
        return Json.MAPPER.readTree(r.body());
    }

    public String tail() {
        try {
            List<String> lines = Files.readAllLines(log);
            return String.join("\n", lines.subList(Math.max(0, lines.size() - 60), lines.size()));
        } catch (IOException e) {
            return "(no log)";
        }
    }

    @Override
    public void close() {
        process.destroyForcibly();
    }
}
