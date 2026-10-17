package io.specforge.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.specforge.gen.GeneratedFile;
import io.specforge.gen.Generator;
import io.specforge.gen.TemplateManager;
import io.specforge.jobs.Jobs;
import io.specforge.studio.JavaSyntax;
import io.specforge.studio.ModelTree;
import io.specforge.studio.Studio;
import io.specforge.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spring Boot Studio's endpoints (STU-1 … STU-7, STU-10, STU-13): template sets, customize / save / reset, live
 * preview of one template for one sample with the default's output beside it, syntax checks, the variable
 * explorer, ZIP export / import, and compiling the whole preview.
 */
final class StudioApi {

    /** Studio preview request: the usual generation request plus the template being edited. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PreviewReq {
        public String set;
        public String template;
        /** sample item key; "" = the first one */
        public String item = "";
        /** unsaved editor text; null = the saved template */
        public String draft;
        public Api.GenReq request = new Api.GenReq();
        /** verify: the user confirmed running Maven with a customized build-file template */
        public boolean trustBuildFile;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SetReq {
        public String name;
        public String copyOf;
        public String template;
        public String content;
    }

    /** Builds the model and generates with the given templates, as Preview does. */
    interface Runner {
        Generator.Result run(Api.GenReq r, TemplateManager tm, Generator.Probe probe) throws IOException;
    }

    private static final long RENDER_TIMEOUT_MS = 10_000;
    private static final Pattern MAVEN_ERROR = Pattern.compile("\\[ERROR] (.+\\.java):\\[(\\d+),(\\d+)] (.+)");

    private final Studio studio;
    private final Runner runner;
    private final Jobs jobs;
    private final ExecutorService renderPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "specforge-studio-render");
        t.setDaemon(true);
        return t;
    });

    StudioApi(Path home, Runner runner, Jobs jobs) {
        this.studio = new Studio(home);
        this.runner = runner;
        this.jobs = jobs;
    }

    Api.Resp handle(String method, String path, Map<String, String> query, byte[] body) throws IOException {
        try {
            if (path.equals("/api/studio/sets") && method.equals("GET")) {
                return Api.json(Map.of("root", studio.root().toString(), "sets", studio.sets()));
            }
            if (path.equals("/api/studio/sets") && method.equals("POST")) {
                SetReq r = Json.MAPPER.readValue(body, SetReq.class);
                Path dir = studio.create(r.name, r.copyOf);
                return Api.json(Map.of("name", r.name, "dir", dir.toString()));
            }
            if (path.equals("/api/studio/preview") && method.equals("POST")) return preview(Json.MAPPER.readValue(body, PreviewReq.class));
            if (path.equals("/api/studio/verify") && method.equals("POST")) return verify(Json.MAPPER.readValue(body, PreviewReq.class));
            if (path.startsWith("/api/studio/sets/")) {
                String[] parts = path.substring("/api/studio/sets/".length()).split("/", 2);
                String set = parts[0];
                String action = parts.length > 1 ? parts[1] : "";
                switch (method + " " + action) {
                    case "DELETE " -> {
                        studio.delete(set);
                        return Api.json(Map.of("deleted", set));
                    }
                    case "GET templates" -> {
                        return Api.json(Map.of("set", set, "dir", studio.set(set, false).toString(), "templates", studio.templates(studio.set(set, false))));
                    }
                    case "GET template" -> {
                        return Api.json(studio.template(studio.set(set, false), query.get("name")));
                    }
                    case "POST customize" -> {
                        SetReq r = Json.MAPPER.readValue(body, SetReq.class);
                        studio.customize(studio.set(set, false), r.template);
                        return Api.json(studio.template(studio.set(set, false), r.template));
                    }
                    case "PUT template" -> {
                        SetReq r = Json.MAPPER.readValue(body, SetReq.class);
                        studio.save(studio.set(set, false), r.template, r.content);
                        return Api.json(studio.template(studio.set(set, false), r.template));
                    }
                    case "POST reset" -> {
                        SetReq r = Json.MAPPER.readValue(body, SetReq.class);
                        studio.reset(studio.set(set, false), r.template);
                        return Api.json(studio.template(studio.set(set, false), r.template));
                    }
                    case "GET export" -> {
                        Api.Resp resp = new Api.Resp(200, "application/zip", studio.exportZip(studio.set(set, false)));
                        resp.headers.put("Content-Disposition", "attachment; filename=\"specforge-templates-" + set + ".zip\"");
                        return resp;
                    }
                    case "POST import" -> {
                        int n = studio.importZip(studio.set(set, true), body == null ? new byte[0] : body);
                        return Api.json(Map.of("set", set, "imported", n));
                    }
                    default -> throw new Api.ApiException(404, "Unknown Studio endpoint: " + method + " " + path);
                }
            }
            return null;
        } catch (IllegalArgumentException e) {
            throw new Api.ApiException(400, e.getMessage());
        } catch (NoSuchFileException e) {
            throw new Api.ApiException(404, e.getMessage());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new Api.ApiException(400, "Not valid JSON: " + e.getOriginalMessage());
        }
    }

    // ------------------------------------------------------------------ preview (STU-3, STU-4, STU-5)

    private Api.Resp preview(PreviewReq r) throws IOException {
        if (!TemplateManager.safeName(r.template) || r.template.equals("manifest.json"))
            throw new Api.ApiException(400, "Choose a template to preview (the manifest has no preview).");
        Path setDir = r.set == null || r.set.isBlank() ? null : studio.set(r.set, false);
        TemplateManager mine = new TemplateManager(setDir).withDraft(r.template, r.draft);
        String sourceKind = r.request.snapshotId != null && !r.request.snapshotId.isBlank() ? "DATABASE" : "SPEC";
        TemplateManager.Entry entry = null;
        for (TemplateManager.Entry e : mine.manifest().templates)
            if (e.template.equals(r.template) && e.rendersFor(sourceKind)) {
                entry = e;
                break;
            }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("template", r.template);
        if (entry == null) {
            out.put("unavailable", "This template does not render for the current source (" + (sourceKind.equals("SPEC") ? "Swagger / OpenAPI" : "database")
                + "). Build from the other source to preview it.");
            return Api.json(out);
        }
        out.put("entry", entry.id);
        long started = System.nanoTime();
        Generator.Probe probe = probe(entry.id, r.item);
        try {
            Generator.Result res = timed(() -> runner.run(r.request, mine, probe));
            GeneratedFile f = res.files.isEmpty() ? null : res.files.get(0);
            out.put("items", probe.items);
            out.put("item", probe.chosenKey);
            if (f == null) {
                out.put("output", "");
                out.put("note", probe.items.isEmpty() ? "Nothing to render: the current " + (sourceKind.equals("SPEC") ? "spec" : "selection")
                    + " has no item for this template, or its 'when' condition is false." : "Nothing rendered for this item.");
            } else {
                out.put("fileName", f.path);
                out.put("output", f.content);
                if (f.path.endsWith(".java")) {
                    List<Map<String, Object>> errors = JavaSyntax.check(f.path.substring(f.path.lastIndexOf('/') + 1), f.content);
                    if (errors == null) out.put("javaCheck", "unavailable: SpecForge runs on a JRE without the Java compiler");
                    else out.put("javaErrors", errors);
                }
            }
            if (probe.root != null) out.put("model", ModelTree.of(probe.root));
        } catch (Generator.GenException e) {
            out.put("error", templateError(e));
        }
        // the default template for the same item, for "compare with default" (STU-3)
        if (TemplateManager.bundledSource(r.template) != null) {
            try {
                Generator.Probe defProbe = probe(entry.id, (String) out.getOrDefault("item", r.item));
                Generator.Result def = timed(() -> runner.run(r.request, new TemplateManager(null), defProbe));
                out.put("defaultOutput", def.files.isEmpty() ? "" : def.files.get(0).content);
            } catch (Generator.GenException e) {
                out.put("defaultOutput", "");
            }
        }
        out.put("millis", (System.nanoTime() - started) / 1_000_000);
        return Api.json(out);
    }

    private static Generator.Probe probe(String entryId, String item) {
        Generator.Probe p = new Generator.Probe();
        p.entryId = entryId;
        p.itemKey = item == null ? "" : item;
        return p;
    }

    private static Map<String, Object> templateError(Generator.GenException e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", e.getMessage());
        m.put("template", e.template);
        m.put("line", e.line);
        m.put("column", e.column);
        return m;
    }

    @FunctionalInterface
    private interface Render {
        Generator.Result call() throws IOException;
    }

    /** STU-10: a template that does not finish in time is stopped and reported, never left to block the UI. */
    private Generator.Result timed(Render work) throws IOException {
        Future<Generator.Result> f = renderPool.submit(work::call);
        try {
            return f.get(RENDER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new Generator.GenException("The template did not finish within " + RENDER_TIMEOUT_MS / 1000 + " s; look for a loop that never ends.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Generator.GenException g) throw g;
            if (e.getCause() instanceof Api.ApiException a) throw a;
            if (e.getCause() instanceof IOException io) throw io;
            throw new Generator.GenException(String.valueOf(e.getCause().getMessage()), e.getCause());
        }
    }

    // ------------------------------------------------------------------ verify by compiling (STU-13)

    private Api.Resp verify(PreviewReq r) throws IOException {
        Path setDir = r.set == null || r.set.isBlank() ? null : studio.set(r.set, false);
        // Maven runs the generated build file's plugins: a customized pom template (e.g. from an imported set) is code
        boolean customBuild = "pom.xml.ftl".equals(r.template) && r.draft != null
            || setDir != null && Files.isRegularFile(setDir.resolve("pom.xml.ftl"));
        if (customBuild && !r.trustBuildFile)
            throw new Api.ApiException(409, "This set customizes pom.xml.ftl. Compiling runs Maven with that build file and its plugins; "
                + "confirm only for template sets you trust.");
        TemplateManager tm = new TemplateManager(setDir).withDraft(r.template, r.draft);
        Generator.Result res = runner.run(r.request, tm, null);
        if (res.files.stream().noneMatch(f -> f.path.endsWith("pom.xml")))
            throw new Api.ApiException(400, "Turn on \"Generate project files\" in Settings: compiling needs the generated pom.xml.");
        Jobs.Job job = jobs.start("verify", j -> compile(j, res));
        return Api.json(Map.of("jobId", job.id));
    }

    private Object compile(Jobs.Job job, Generator.Result res) throws Exception {
        job.phase("Writing the project", 0.05);
        Path dir = Files.createTempDirectory("specforge-verify");
        Map<String, GeneratedFile> byPath = new LinkedHashMap<>();
        Path base = null;
        for (GeneratedFile f : res.files) {
            // keep the layout relative to the pom
            if (f.path.endsWith("pom.xml") && !f.path.contains("/")) base = Path.of(f.absPath).getParent();
        }
        if (base == null) base = Path.of(res.files.get(0).absPath).getParent();
        for (GeneratedFile f : res.files) {
            Path abs = Path.of(f.absPath);
            Path rel = abs.startsWith(base) ? base.relativize(abs) : Path.of(f.path);
            Path target = dir.resolve(rel).normalize();
            if (!target.startsWith(dir)) continue;
            Files.createDirectories(target.getParent());
            Files.writeString(target, f.content, StandardCharsets.UTF_8);
            byPath.put(target.toAbsolutePath().normalize().toString().replace('\\', '/').toLowerCase(Locale.ROOT), f);
        }
        job.checkCancelled();
        job.phase("Compiling with Maven (the first run downloads dependencies)", 0.2);
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path log = dir.resolve("verify.log");
        Process p;
        try {
            p = new ProcessBuilder(windows ? "mvn.cmd" : "mvn", "-B", "-q", "compile").directory(dir.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        } catch (IOException e) {
            throw new IllegalStateException("Maven is not installed or not on the PATH; install it to compile the preview.");
        }
        while (!p.waitFor(500, TimeUnit.MILLISECONDS)) {
            try {
                job.checkCancelled();
            } catch (Jobs.Cancelled c) {
                p.destroyForcibly();
                throw c;
            }
        }
        List<Map<String, Object>> errors = new ArrayList<>();
        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
            Matcher m = MAVEN_ERROR.matcher(line.trim());
            if (!m.find()) continue;
            String file = m.group(1).replace('\\', '/').replaceFirst("^/(?=[A-Za-z]:)", "");
            GeneratedFile gf = byPath.get(Path.of(file).toAbsolutePath().normalize().toString().replace('\\', '/').toLowerCase(Locale.ROOT));
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("file", gf == null ? file : gf.path);
            e.put("template", gf == null ? "" : gf.id);
            e.put("line", Integer.parseInt(m.group(2)));
            e.put("column", Integer.parseInt(m.group(3)));
            e.put("message", m.group(4));
            if (errors.stream().noneMatch(x -> x.get("file").equals(e.get("file")) && x.get("line").equals(e.get("line")) && x.get("message").equals(e.get("message"))))
                errors.add(e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", p.exitValue() == 0);
        out.put("errors", errors);
        out.put("dir", dir.toString());
        if (p.exitValue() != 0 && errors.isEmpty()) {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            out.put("log", String.join("\n", lines.subList(Math.max(0, lines.size() - 30), lines.size())));
        }
        return out;
    }
}
