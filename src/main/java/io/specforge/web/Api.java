package io.specforge.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.specforge.App;
import io.specforge.config.GeneratorConfig;
import io.specforge.config.ProfileStore;
import io.specforge.gen.GeneratedFile;
import io.specforge.gen.Generator;
import io.specforge.gen.ProjectWriter;
import io.specforge.gen.TemplateManager;
import io.specforge.mapper.OpenApiMapper;
import io.specforge.model.Model.*;
import io.specforge.spec.SpecLoader;
import io.specforge.spec.SpecStore;
import io.specforge.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** The REST API behind the browser UI. Pure request -> response; no HTTP server types leak in here. */
public class Api {

    public static class ApiException extends RuntimeException {
        public final int status;

        public ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public static class Resp {
        public final int status;
        public final String contentType;
        public final byte[] body;
        public final Map<String, String> headers = new LinkedHashMap<>();

        public Resp(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }
    }

    public static class AnalyzeReq {
        public String mode = "text";
        public String content;
        public String url;
        public String name = "pasted";
    }

    public static class GenReq {
        public String specId;
        /** database flow: the snapshot and the user's decisions, instead of a spec */
        public String snapshotId;
        public io.specforge.plan.GenerationPlan plan;
        public GeneratorConfig config = new GeneratorConfig();
        public Map<String, String> overrides = new HashMap<>();
        public String overwrite = "SKIP";
    }

    private final Path home;
    private final Path templatesOverride;
    private final SpecStore store = new SpecStore();
    private final SpecLoader loader = new SpecLoader();
    private final ProfileStore profiles;
    private final DbApi db;
    private final StudioApi studio;

    public Api(Path home, Path templatesOverride) {
        this.home = home;
        this.templatesOverride = templatesOverride;
        this.profiles = new ProfileStore(home.resolve("profiles"));
        this.db = new DbApi(home);
        this.studio = new StudioApi(home, this::run, db.jobs());
    }

    // ------------------------------------------------------------------ routing

    public Resp handle(String method, String path, Map<String, String> query, byte[] body) {
        try {
            if (path.equals("/api/info") && method.equals("GET")) return info();
            if (path.equals("/api/spring/metadata") && method.equals("GET")) return springMetadata();
            if (path.equals("/api/spec/analyze") && method.equals("POST")) return analyze(parse(body, AnalyzeReq.class));
            if (path.equals("/api/generate/preview") && method.equals("POST")) return preview(parse(body, GenReq.class));
            if (path.equals("/api/generate/write") && method.equals("POST")) return write(parse(body, GenReq.class));
            if (path.equals("/api/generate/zip") && method.equals("POST")) return zip(parse(body, GenReq.class));
            if (path.equals("/api/profiles") && method.equals("GET")) return json(Map.of("profiles", profiles.list(), "dir", profiles.dir().toString()));
            if (path.startsWith("/api/profiles/")) return profile(method, path.substring("/api/profiles/".length()), body);
            if (path.equals("/api/templates") && method.equals("GET")) return templates(query.getOrDefault("dir", ""));
            if (path.equals("/api/templates/source") && method.equals("GET")) return templateSource(query.getOrDefault("dir", ""), query.get("name"));
            if (path.equals("/api/templates/export") && method.equals("POST")) return exportTemplates(body);
            if (path.equals("/api/fs/list") && method.equals("GET")) return fsList(query.getOrDefault("path", ""));
            if (path.startsWith("/api/studio/")) {
                Resp r = studio.handle(method, path, query, body);
                if (r != null) return r;
            }
            if (path.startsWith("/api/db/") || path.startsWith("/api/jobs/")) {
                Resp r = db.handle(method, path, query, body);
                if (r != null) return r;
            }
            throw new ApiException(404, "Unknown endpoint: " + method + " " + path);
        } catch (ApiException e) {
            return error(e.status, e.getMessage());
        } catch (Generator.GenException e) {
            return error(422, e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(400, e.getMessage());
        } catch (NoSuchFileException e) {
            return error(404, e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return error(500, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ endpoints

    private Resp info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "SpecForge");
        m.put("version", App.VERSION);
        m.put("home", home.toString());
        m.put("profilesDir", profiles.dir().toString());
        m.put("defaultTemplatesDir", home.resolve("templates").toString());
        m.put("activeTemplatesDir", templatesOverride == null ? "" : templatesOverride.toString());
        m.put("cwd", Paths.get("").toAbsolutePath().toString());
        m.put("userHome", System.getProperty("user.home"));
        m.put("java", System.getProperty("java.version"));
        m.put("defaults", GeneratorConfig.defaults());
        m.put("layerKeys", GeneratorConfig.LAYER_KEYS);
        return json(m);
    }

    /** Boot and Java versions for the Settings pickers (VER-1, VER-2). R1 serves the bundled snapshot. */
    private Resp springMetadata() {
        io.specforge.spring.SpringMetadata sm = io.specforge.spring.SpringMetadataSource.current();
        List<Map<String, Object>> lines = new ArrayList<>();
        for (io.specforge.spring.SpringMetadata.BootLine l : sm.lines) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("version", l.version);
            e.put("origin", l.origin);
            e.put("javaVersions", l.javaVersions);
            e.put("suggested", l.version.equals(sm.bootDefault));
            lines.add(e);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", "bundled");
        m.put("fetchedAt", sm.fetchedAt);
        m.put("bootDefault", sm.bootDefault);
        m.put("javaDefault", sm.javaDefault);
        m.put("testedRange", io.specforge.spring.MetadataSnapshotTool.TESTED_RANGE);
        m.put("lines", lines);
        return json(m);
    }

    private Resp analyze(AnalyzeReq r) {
        SpecLoader.Loaded l;
        try {
            if ("url".equalsIgnoreCase(r.mode)) {
                if (r.url == null || r.url.isBlank()) throw new ApiException(400, "Enter a URL.");
                l = loader.fromUrl(r.url);
            } else {
                l = loader.fromText(r.content, r.name == null ? "pasted" : r.name);
            }
        } catch (SpecLoader.SpecException e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", e.getMessage());
            m.put("messages", e.messages);
            return new Resp(422, "application/json", Json.write(m).getBytes(StandardCharsets.UTF_8));
        }
        store.put(l);
        ApiModel model;
        try {
            model = new OpenApiMapper(l.api, GeneratorConfig.defaults(), Map.of()).map();
        } catch (RuntimeException e) {
            e.printStackTrace();
            throw new ApiException(422, "The spec was parsed but could not be analysed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("specId", l.id);
        m.put("source", l.source);
        m.put("upstreamUrl", new OpenApiMapper(l.api, GeneratorConfig.defaults(), Map.of()).sourceUrl(l.source).suggestedUpstreamUrl());
        m.put("format", l.format);
        m.put("declared", l.declared);
        m.put("title", model.title);
        m.put("version", model.version);
        m.put("description", model.description);
        m.put("basePath", model.basePath);
        List<Msg> msgs = new ArrayList<>(l.messages);
        msgs.addAll(model.messages);
        m.put("messages", msgs);
        int ops = 0;
        List<Map<String, Object>> resources = new ArrayList<>();
        for (ResourceModel x : model.resources) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("name", x.name);
            rm.put("entity", x.entity == null ? "" : x.entity.key);
            List<Map<String, Object>> eps = new ArrayList<>();
            for (OperationModel o : x.operations) {
                ops++;
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("method", o.httpMethod);
                em.put("path", o.fullPath);
                em.put("operation", o.method);
                em.put("kind", o.kind);
                em.put("access", o.access);
                eps.add(em);
            }
            rm.put("endpoints", eps);
            resources.add(rm);
        }
        m.put("resources", resources);
        List<Map<String, Object>> schemas = new ArrayList<>();
        for (ClassDef c : model.entities) schemas.add(schemaRow(c));
        for (ClassDef c : model.dtos) schemas.add(schemaRow(c));
        for (EnumDef e : model.enums) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", e.key);
            s.put("className", e.className);
            s.put("kind", "ENUM");
            s.put("reason", e.synthetic ? "inline enum" : "enum");
            s.put("fields", e.values.size());
            s.put("synthetic", e.synthetic);
            schemas.add(s);
        }
        m.put("schemas", schemas);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("operations", ops);
        stats.put("resources", model.resources.size());
        stats.put("entities", model.entities.size());
        stats.put("dtos", model.dtos.size());
        stats.put("enums", model.enums.size());
        m.put("stats", stats);
        List<Map<String, Object>> sec = new ArrayList<>();
        for (SchemeDef d : model.security.schemes) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", d.name);
            s.put("type", d.type);
            String detail = switch (d.type) {
                case "APIKEY" -> d.paramName + " (" + d.in + ")";
                case "OAUTH2" -> String.join(", ", d.flows) + (d.scopes.isEmpty() ? "" : "; " + d.scopes.size() + " scopes");
                case "OPENID_CONNECT" -> d.openIdConnectUrl;
                case "HTTP_BEARER" -> d.bearerFormat.isEmpty() ? "bearer" : d.bearerFormat;
                default -> "";
            };
            s.put("detail", detail);
            sec.add(s);
        }
        m.put("security", sec);
        return json(m);
    }

    private static Map<String, Object> schemaRow(ClassDef c) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", c.key);
        s.put("className", c.className);
        s.put("kind", c.kind);
        s.put("reason", c.reason);
        s.put("fields", c.fields.size());
        s.put("synthetic", c.synthetic);
        return s;
    }

    private Generator.Result run(GenReq r) throws IOException {
        return run(r, null, null);
    }

    /**
     * Builds the model from the spec or the snapshot and generates. {@code tm} replaces the configured templates
     * and {@code probe} restricts rendering to one template and item (Studio previews, STU-3).
     */
    Generator.Result run(GenReq r, TemplateManager tm, Generator.Probe probe) throws IOException {
        if (r.snapshotId != null && !r.snapshotId.isBlank()) {
            if (r.config == null) r.config = GeneratorConfig.defaults();
            GeneratorConfig cfg = db.databaseConfig(r.config);
            List<String> problems = cfg.validate();
            if (!problems.isEmpty()) throw new ApiException(400, "Invalid settings:\n- " + String.join("\n- ", problems));
            return new Generator(tm != null ? tm : templateManager(cfg.templatesDir)).probe(probe).generate(db.model(r.snapshotId, r.plan, cfg), cfg);
        }
        SpecLoader.Loaded l = store.get(r.specId);
        if (l == null) throw new ApiException(409, "This spec is no longer in memory (the app was restarted?). Go back to step 1 and analyse it again.");
        if (r.config == null) r.config = GeneratorConfig.defaults();
        List<String> problems = r.config.validate();
        if (!problems.isEmpty()) throw new ApiException(400, "Invalid settings:\n- " + String.join("\n- ", problems));
        ApiModel model = new OpenApiMapper(l.api, r.config, r.overrides).sourceUrl(l.source).map();
        return new Generator(tm != null ? tm : templateManager(r.config.templatesDir)).probe(probe).generate(model, r.config);
    }

    private TemplateManager templateManager(String configured) {
        String c = configured == null ? "" : configured.trim();
        if (c.isEmpty() && templatesOverride != null) c = templatesOverride.toString();
        return new TemplateManager(TemplateManager.resolveDir(c, home));
    }

    private Resp preview(GenReq r) throws IOException {
        Generator.Result res = run(r);
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> files = new ArrayList<>();
        int n = 0, ex = 0, same = 0;
        for (GeneratedFile f : res.files) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("path", f.path);
            fm.put("layer", f.layer);
            fm.put("id", f.id);
            fm.put("language", f.language);
            fm.put("status", f.status);
            fm.put("size", f.content.length());
            fm.put("content", f.content);
            files.add(fm);
            switch (f.status) {
                case "EXISTS" -> ex++;
                case "IDENTICAL" -> same++;
                default -> n++;
            }
        }
        m.put("files", files);
        m.put("messages", res.messages);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("total", files.size());
        counts.put("new", n);
        counts.put("exists", ex);
        counts.put("identical", same);
        m.put("counts", counts);
        return json(m);
    }

    private Resp write(GenReq r) throws IOException {
        if (r.config == null || r.config.targetDir == null || r.config.targetDir.isBlank())
            throw new ApiException(400, "Choose a target directory first.");
        Path t = Paths.get(r.config.targetDir.trim());
        if (Files.exists(t) && !Files.isDirectory(t)) throw new ApiException(400, "The target path exists but is not a directory: " + t);
        String pol = r.overwrite == null ? "SKIP" : r.overwrite.toUpperCase(Locale.ROOT);
        if (!Set.of("SKIP", "BACKUP", "OVERWRITE").contains(pol)) throw new ApiException(400, "Unknown overwrite policy: " + r.overwrite);
        Generator.Result res = run(r);
        ProjectWriter.Report rep = ProjectWriter.write(res.files, pol);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("targetDir", t.toAbsolutePath().normalize().toString());
        m.put("policy", pol);
        m.put("created", rep.created);
        m.put("skipped", rep.skipped);
        m.put("unchanged", rep.unchanged);
        m.put("overwritten", rep.overwritten);
        m.put("backedUp", rep.backedUp);
        m.put("errors", rep.errors);
        m.put("messages", res.messages);
        return json(m);
    }

    private Resp zip(GenReq r) throws IOException {
        Generator.Result res = run(r);
        SpecLoader.Loaded l = store.get(r.specId);
        String name = io.specforge.util.Names.slug(l.api.getInfo() != null && l.api.getInfo().getTitle() != null ? l.api.getInfo().getTitle() : "api");
        byte[] z = ProjectWriter.zip(res.files, name);
        Resp resp = new Resp(200, "application/zip", z);
        resp.headers.put("Content-Disposition", "attachment; filename=\"" + name + ".zip\"");
        return resp;
    }

    private Resp profile(String method, String name, byte[] body) throws IOException {
        name = java.net.URLDecoder.decode(name, StandardCharsets.UTF_8);
        switch (method) {
            case "GET":
                return json(profiles.load(name));
            case "PUT": {
                GeneratorConfig c = parse(body, GeneratorConfig.class);
                profiles.save(name, c);
                return json(Map.of("saved", name));
            }
            case "DELETE":
                return json(Map.of("deleted", profiles.delete(name)));
            default:
                throw new ApiException(405, "Method not allowed");
        }
    }

    private Resp templates(String dir) throws IOException {
        TemplateManager tm = templateManager(dir);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("externalDir", tm.externalDir() == null ? "" : tm.externalDir().toString());
        m.put("defaultDir", home.resolve("templates").toString());
        m.put("templates", tm.list());
        return json(m);
    }

    private Resp templateSource(String dir, String name) throws IOException {
        if (name == null) throw new ApiException(400, "Missing template name.");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("content", templateManager(dir).source(name));
        return json(m);
    }

    private Resp exportTemplates(byte[] body) throws IOException {
        JsonNode n = body == null || body.length == 0 ? null : Json.MAPPER.readTree(body);
        String dir = n != null && n.hasNonNull("dir") ? n.get("dir").asText().trim() : "";
        Path target = dir.isEmpty() ? home.resolve("templates") : Paths.get(dir);
        int copied = TemplateManager.exportDefaults(target.toAbsolutePath().normalize());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dir", target.toAbsolutePath().normalize().toString());
        m.put("copied", copied);
        return json(m);
    }

    private Resp fsList(String path) throws IOException {
        Path p = path.isBlank() ? Paths.get(System.getProperty("user.home", ".")) : Paths.get(path.trim());
        p = p.toAbsolutePath().normalize();
        boolean exists = Files.isDirectory(p);
        Path shown = p;
        while (shown != null && !Files.isDirectory(shown)) shown = shown.getParent();
        if (shown == null) shown = Paths.get(System.getProperty("user.home", "."));
        List<String> dirs = new ArrayList<>();
        try (Stream<Path> s = Files.list(shown)) {
            s.filter(Files::isDirectory).map(x -> x.getFileName() == null ? x.toString() : x.getFileName().toString())
                .filter(n -> !n.startsWith(".")).sorted(String.CASE_INSENSITIVE_ORDER).limit(500).forEach(dirs::add);
        } catch (IOException | RuntimeException e) {
            // unreadable directory: show it empty
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", shown.toString());
        m.put("requested", p.toString());
        m.put("exists", exists);
        m.put("parent", shown.getParent() == null ? "" : shown.getParent().toString());
        m.put("writable", Files.isWritable(shown));
        m.put("dirs", dirs);
        m.put("home", System.getProperty("user.home", ""));
        return json(m);
    }

    // ------------------------------------------------------------------ helpers

    private static <T> T parse(byte[] body, Class<T> type) {
        try {
            if (body == null || body.length == 0) return type.getDeclaredConstructor().newInstance();
            return Json.MAPPER.readValue(body, type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ApiException(400, "Malformed request: " + e.getOriginalMessage());
        } catch (Exception e) {
            throw new ApiException(400, "Malformed request: " + e.getMessage());
        }
    }

    static Resp json(Object o) {
        return new Resp(200, "application/json; charset=utf-8", Json.write(o).getBytes(StandardCharsets.UTF_8));
    }

    static Resp error(int status, String message) {
        return new Resp(status, "application/json; charset=utf-8",
            Json.write(Map.of("error", message == null ? "Unexpected error" : message)).getBytes(StandardCharsets.UTF_8));
    }
}
