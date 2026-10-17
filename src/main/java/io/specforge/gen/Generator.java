package io.specforge.gen;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateScalarModel;
import io.specforge.config.GeneratorConfig;
import io.specforge.gen.TemplateManager.Entry;
import io.specforge.model.DbModel;
import io.specforge.model.Model.*;
import io.specforge.spring.BuildResolver;
import io.specforge.spring.SpringMetadataSource;
import io.specforge.util.JavaTidy;
import io.specforge.util.Names;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/** Renders every manifest entry for every matching model element. */
public class Generator {

    public static class GenException extends RuntimeException {
        /** where a template failed, when known (STU-5) */
        public String template;
        public Integer line;
        public Integer column;

        public GenException(String m) {
            super(m);
        }

        public GenException(String m, Throwable cause) {
            super(m, cause);
        }

        GenException at(String template, Integer line, Integer column) {
            this.template = template;
            this.line = line;
            this.column = column;
            return this;
        }
    }

    /**
     * Studio (STU-3, STU-4): render only one manifest entry, for one item. The items the entry renders for and the
     * template model of the chosen item are recorded.
     */
    public static class Probe {
        public String entryId = "";
        /** "" = the first item */
        public String itemKey = "";
        public List<Map<String, String>> items = new ArrayList<>();
        public Map<String, Object> root;
        public String chosenKey = "";
    }

    /** Largest output one template may produce (STU-10). */
    public static final int MAX_OUTPUT_CHARS = 4_000_000;

    private Probe probe;

    public Generator probe(Probe p) {
        this.probe = p;
        return this;
    }

    public static class Result {
        public List<GeneratedFile> files = new ArrayList<>();
        public List<Msg> messages = new ArrayList<>();
    }

    private final TemplateManager templates;

    public Generator(TemplateManager templates) {
        this.templates = templates;
    }

    public Result generate(ApiModel api, GeneratorConfig cfg) {
        List<String> problems = cfg.validate();
        if (!problems.isEmpty()) throw new GenException("Invalid settings:\n- " + String.join("\n- ", problems));
        Result result = new Result();
        result.messages.addAll(api.messages);
        BuildResolver.Build build = BuildResolver.resolve(SpringMetadataSource.current(), cfg.options.springBootVersion, buildIds(api, cfg));
        for (String w : build.warnings) result.messages.add(new Msg("WARN", w));
        assignNames(api, cfg);
        layerDependencyWarnings(cfg, result);
        String boot = cfg.options.springBootVersion == null ? "" : cfg.options.springBootVersion.trim();
        if (!cfg.options.isJakarta() && !boot.startsWith("2."))
            result.messages.add(new Msg("WARN", "javax.* imports require Spring Boot 2.7.x; set the Spring Boot version to 2.7.18 (currently " + boot + ")."));
        if (cfg.options.isJakarta() && boot.startsWith("2."))
            result.messages.add(new Msg("WARN", "jakarta.* imports require Spring Boot 3.x (currently " + boot + ")."));
        if (cfg.options.isProxy() && (boot.startsWith("2.") || boot.matches("3\\.[01](\\..*)?")))
            result.messages.add(new Msg("WARN", "Proxy mode uses Spring's RestClient, which needs Spring Boot 3.2 or newer (currently " + boot + ")."));
        if (cfg.options.isProxy() && "SERVICE_ACCOUNT".equalsIgnoreCase(cfg.security.upstreamAuth) && !api.security.present)
            result.messages.add(new Msg("WARN", "Service-account mode without inbound security lets every caller use the app's upstream account; "
                + "enable Spring Security or protect the app another way."));
        if (cfg.options.isProxy() && "SERVICE_ACCOUNT".equalsIgnoreCase(cfg.security.upstreamAuth) && api.security.outboundType.isEmpty())
            result.messages.add(new Msg("INFO", "The spec declares no usable credential for the upstream API; set app.upstream.credential.* if it needs one."));
        if (!cfg.options.isJakarta() && api.entities.stream().anyMatch(e -> e.fields.stream().anyMatch(f -> f.persist.equals("JSON"))))
            result.messages.add(new Msg("WARN", "In javax mode, nested objects / maps inside entities cannot use Hibernate 6 JSON columns; those fields are generated as @Transient with a TODO."));

        Configuration fm;
        TemplateManager.Manifest manifest;
        try {
            fm = templates.configuration();
            manifest = templates.manifest();
        } catch (IOException e) {
            throw new GenException("Cannot load templates: " + e.getMessage(), e);
        }

        Map<String, String> fqnByKey = new HashMap<>();
        for (ClassDef c : api.entities) fqnByKey.put(c.key, c.fqn);
        for (ClassDef c : api.dtos) fqnByKey.put(c.key, c.fqn);
        for (EnumDef e : api.enums) fqnByKey.put(e.key, e.fqn);

        Path target = targetPath(cfg);
        Map<String, String> seen = new HashMap<>();
        for (Entry entry : manifest.templates) {
            if (probe != null && !entry.id.equals(probe.entryId)) continue;
            GeneratorConfig.Layer layer = cfg.layer(entry.layer);
            if (!layer.enabled) continue;
            if (!GeneratorConfig.LAYER_KEYS.contains(entry.layer))
                throw new GenException("Template '" + entry.id + "' uses unknown layer '" + entry.layer + "'. Known layers: " + GeneratorConfig.LAYER_KEYS);
            if (entry.layer.equals("project") && !cfg.options.generateProjectFiles) continue;
            if (!entry.rendersFor(api.source.kind)) continue;
            Map<String, Object> base = baseModel(api, cfg, entry, build);
            // per-item scopes evaluate "when" once per item (with entity / item in scope), see render()
            boolean perItem = !entry.scope.equalsIgnoreCase("GLOBAL");
            if (!perItem && !evalWhen(fm, entry, base)) continue;
            switch (entry.scope.toUpperCase(Locale.ROOT)) {
                case "ENTITY" -> {
                    // per-entity templates of other layers (e.g. repositories) go to that layer's package
                    for (ClassDef c : api.entities) render(fm, entry, cfg, target, base, entry.layer.equals("entities") ? c.pkg : cfg.layerPackage(entry.layer),
                        "entity", c, c.allTypes(), fqnByKey, seen, result);
                }
                case "DTO" -> {
                    for (ClassDef c : api.dtos) render(fm, entry, cfg, target, base, c.pkg, "dto", c, c.allTypes(), fqnByKey, seen, result);
                }
                case "ENUM" -> {
                    for (EnumDef e : api.enums) render(fm, entry, cfg, target, base, e.pkg, "enumDef", e, List.of(), fqnByKey, seen, result);
                }
                case "RESOURCE" -> {
                    for (ResourceModel r : api.resources) render(fm, entry, cfg, target, base, cfg.layerPackage(entry.layer), "resource", r, r.allTypes(), fqnByKey, seen, result);
                }
                case "USECASE" -> {
                    for (DbModel.DbUseCase u : api.useCases) render(fm, entry, cfg, target, base, cfg.layerPackage(entry.layer), "useCase", u, List.of(), fqnByKey, seen, result);
                }
                case "USECASE_RECORD" -> {
                    // records are shared between use cases by name (the same parent and child give the same record)
                    Map<String, DbModel.DbRecord> records = new java.util.LinkedHashMap<>();
                    for (DbModel.DbUseCase u : api.useCases) for (DbModel.DbRecord rec : u.records) records.putIfAbsent(rec.name, rec);
                    for (DbModel.DbRecord rec : records.values()) render(fm, entry, cfg, target, base, cfg.layerPackage(entry.layer), "record", rec, List.of(), fqnByKey, seen, result);
                }
                case "GLOBAL" -> render(fm, entry, cfg, target, base, cfg.layerPackage(entry.layer), null, null, List.of(), fqnByKey, seen, result);
                default -> throw new GenException("Template '" + entry.id + "' has unknown scope '" + entry.scope + "'.");
            }
        }
        if (result.files.isEmpty())
            result.messages.add(new Msg("WARN", "Nothing was generated: all layers are disabled or the spec has nothing to generate."));
        return result;
    }

    // ------------------------------------------------------------------ naming

    private void assignNames(ApiModel api, GeneratorConfig cfg) {
        for (ClassDef c : api.entities) {
            c.pkg = cfg.layerPackage("entities");
            c.fqn = c.pkg + "." + c.className;
            String base = c.db != null ? c.className : Names.className(c.key);
            c.repositoryName = cfg.naming.repository.replace("{Name}", base);
            c.repositoryFqn = cfg.layerPackage("repositories") + "." + c.repositoryName;
            if (c.db != null) {
                c.db.repositoryName = c.repositoryName;
                c.db.dtoName = c.className + "Dto";
                c.db.mapperName = c.className + "Mapper";
                c.db.serviceName = cfg.naming.service.replace("{Name}", c.className);
                c.db.controllerName = c.db.controllerNameOverride.isEmpty() ? cfg.naming.controller.replace("{Name}", c.className) : c.db.controllerNameOverride;
            }
        }
        // use-case controllers and services never take an entity's class names (OPS-3)
        Set<String> taken = new HashSet<>();
        for (ClassDef c : api.entities) {
            taken.add(c.className);
            if (c.db != null) {
                taken.add(c.db.serviceName);
                taken.add(c.db.controllerName);
                taken.add(c.db.dtoName);
                taken.add(c.db.mapperName);
                taken.add(c.repositoryName);
            }
        }
        for (DbModel.DbUseCase u : api.useCases) {
            u.controllerName = unique(cfg.naming.controller.replace("{Name}", u.name), taken);
            u.serviceName = unique(cfg.naming.service.replace("{Name}", u.name), taken);
            for (DbModel.DbRecord rec : u.records) taken.add(rec.name);
        }
        for (ClassDef c : api.dtos) {
            c.pkg = cfg.layerPackage("models");
            c.fqn = c.pkg + "." + c.className;
        }
        for (EnumDef e : api.enums) {
            e.pkg = cfg.layerPackage("enums");
            e.fqn = e.pkg + "." + e.className;
        }
        Set<String> used = new HashSet<>();
        for (ResourceModel r : api.resources) {
            r.controllerName = unique(cfg.naming.controller.replace("{Name}", r.name), used);
            r.controllerFqn = cfg.layerPackage("controllers") + "." + r.controllerName;
            r.serviceName = unique(cfg.naming.service.replace("{Name}", r.name), used);
            r.serviceFqn = cfg.layerPackage("services") + "." + r.serviceName;
            r.serviceVar = Names.decap(r.serviceName);
            if (r.entity != null) {
                r.repositoryName = r.entity.repositoryName;
                r.repositoryFqn = r.entity.repositoryFqn;
                r.entityFqn = r.entity.fqn;
            }
        }
    }

    private static String itemKey(Object item) {
        if (item instanceof ClassDef c) return c.key;
        if (item instanceof EnumDef e) return e.key;
        if (item instanceof ResourceModel r) return r.name;
        if (item instanceof DbModel.DbUseCase u) return u.name;
        if (item instanceof DbModel.DbRecord r) return r.name;
        return "";
    }

    private static String itemLabel(Object item) {
        if (item instanceof ClassDef c) return c.className + (c.db != null ? " (table " + c.db.qualifiedName + ")" : "");
        if (item instanceof EnumDef e) return e.className;
        if (item instanceof ResourceModel r) return r.controllerName;
        if (item instanceof DbModel.DbUseCase u) return u.controllerName;
        if (item instanceof DbModel.DbRecord r) return r.name;
        return "the project";
    }

    /** A writer that fails once a template has written too much (STU-10). */
    static final class LimitedWriter extends java.io.Writer {
        static final class TooLong extends IOException {
            TooLong() {
                super("output too long");
            }
        }

        private final StringBuilder sb = new StringBuilder();
        private final int max;

        LimitedWriter(int max) {
            this.max = max;
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("rendering was stopped");
            if (sb.length() + len > max) throw new TooLong();
            sb.append(cbuf, off, len);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        @Override
        public String toString() {
            return sb.toString();
        }
    }

    private static String unique(String n, Set<String> used) {
        String c = n;
        int i = 2;
        while (!used.add(c)) c = n + i++;
        return c;
    }

    private void layerDependencyWarnings(GeneratorConfig cfg, Result r) {
        String[][] deps = {
            {"repositories", "entities"}, {"services", "repositories"}, {"services", "entities"},
            {"controllers", "services"}, {"entities", "models"}, {"entities", "enums"}, {"models", "enums"},
            {"controllers", "exceptions"}, {"services", "exceptions"}};
        for (String[] d : deps) {
            if (cfg.layer(d[0]).enabled && !cfg.layer(d[1]).enabled)
                r.messages.add(new Msg("WARN", "Layer '" + d[0] + "' is enabled but '" + d[1]
                    + "' is disabled; the generated code will not compile unless those classes exist elsewhere."));
        }
    }

    // ------------------------------------------------------------------ rendering

    /** Dependency ids of the generated build, in build-file order (VER-3). "#…" entries become comments. */
    static List<String> buildIds(ApiModel api, GeneratorConfig cfg) {
        List<String> ids = new ArrayList<>();
        ids.add("web");
        if (!cfg.options.isProxy() || "DATABASE".equals(api.source.kind)) ids.add("data-jpa");
        if (cfg.options.validation) ids.add("validation");
        if (api.security.present) {
            ids.add("security");
            if (api.security.usesJwt) ids.add("oauth2-resource-server");
        }
        if (cfg.options.isProxy()) ids.add("#proxy mode: no database, data comes from the upstream API");
        else if ("DATABASE".equals(api.source.kind)) {
            if ("SQLITE".equals(api.source.engine)) {
                // compile scope: the exception handler reads SQLite's result codes; the dialect lives in the community dialects (GEN-1)
                ids.add("=org.xerial:sqlite-jdbc");
                ids.add("=org.hibernate.orm:hibernate-community-dialects");
            } else {
                ids.add(api.source.initializrId);
            }
        }
        else ids.add("POSTGRES".equals(cfg.options.database) ? "postgresql" : "h2");
        if (cfg.options.lombok) ids.add("lombok");
        return ids;
    }

    private Map<String, Object> baseModel(ApiModel api, GeneratorConfig cfg, Entry entry, BuildResolver.Build build) {
        Map<String, Object> m = new HashMap<>();
        m.put("build", build);
        m.put("source", api.source);
        m.put("bootMajor", build.bootMajor);
        m.put("api", api);
        m.put("config", cfg);
        m.put("options", cfg.options);
        m.put("secCfg", cfg.security);
        m.put("security", api.security);
        m.put("naming", cfg.naming);
        boolean jakarta = cfg.options.isJakarta();
        m.put("jakarta", jakarta);
        m.put("ns", jakarta ? "jakarta" : "javax");
        m.put("basePackage", cfg.basePackage);
        m.put("layerPackage", cfg.layerPackage(entry.layer));
        m.put("entityPackage", cfg.layerPackage("entities"));
        m.put("repositoryPackage", cfg.layerPackage("repositories"));
        m.put("servicePackage", cfg.layerPackage("services"));
        m.put("controllerPackage", cfg.layerPackage("controllers"));
        m.put("securityPackage", cfg.layerPackage("security"));
        m.put("exceptionPackage", cfg.layerPackage("exceptions"));
        m.put("modelPackage", cfg.layerPackage("models"));
        m.put("enumPackage", cfg.layerPackage("enums"));
        m.put("doc", (TemplateMethodModelEx) args -> Names.doc(args.isEmpty() || args.get(0) == null ? "" : args.get(0) instanceof TemplateScalarModel sm ? sm.getAsString() : args.get(0).toString()));
        m.put("generatedNote", "Generated by SpecForge from \"" + api.title + "\" (" + api.version + "). Safe to edit.");
        return m;
    }

    private boolean evalWhen(Configuration fm, Entry entry, Map<String, Object> root) {
        if (entry.when == null || entry.when.isBlank()) return true;
        try {
            Template t = new Template("when:" + entry.id, new StringReader("<#if " + entry.when + ">true</#if>"), fm);
            StringWriter w = new StringWriter();
            t.process(root, w);
            return w.toString().trim().equals("true");
        } catch (Exception e) {
            throw new GenException("Cannot evaluate the 'when' condition of template '" + entry.id + "' (" + entry.when + "): " + e.getMessage(), e);
        }
    }

    private void render(Configuration fm, Entry entry, GeneratorConfig cfg, Path target, Map<String, Object> base,
                        String pkg, String varName, Object item, List<TypeRef> types, Map<String, String> fqnByKey,
                        Map<String, String> seen, Result result) {
        Map<String, Object> root = new HashMap<>(base);
        root.put("pkg", pkg);
        root.put("imports", imports(types, fqnByKey));
        if (varName != null) {
            root.put(varName, item);
            root.put("item", item);
            if (!evalWhen(fm, entry, root)) return;
        }
        if (probe != null) {
            String key = itemKey(item);
            probe.items.add(Map.of("key", key, "label", itemLabel(item)));
            if (probe.root != null || !probe.itemKey.isEmpty() && !probe.itemKey.equals(key)) return;
            probe.root = root;
            probe.chosenKey = key;
        }
        String fileName;
        String content;
        try {
            Template nameT = new Template("fileName:" + entry.id, new StringReader(entry.fileName), fm);
            StringWriter nw = new StringWriter();
            nameT.process(root, nw);
            fileName = nw.toString().trim();
            Template t = fm.getTemplate(entry.template);
            LimitedWriter w = new LimitedWriter(MAX_OUTPUT_CHARS);
            t.process(root, w);
            content = w.toString();
        } catch (freemarker.template.TemplateException e) {
            throw new GenException("Template '" + entry.template + "' failed: " + e.getMessageWithoutStackTop()
                + (e.getLineNumber() != null && e.getLineNumber() > 0 ? " (line " + e.getLineNumber() + ")" : ""), e)
                .at(entry.template, e.getLineNumber(), e.getColumnNumber());
        } catch (freemarker.core.ParseException e) {
            throw new GenException("Template '" + entry.template + "' has a syntax error: " + e.getEditorMessage()
                + " (line " + e.getLineNumber() + ", column " + e.getColumnNumber() + ")", e)
                .at(entry.template, e.getLineNumber(), e.getColumnNumber());
        } catch (LimitedWriter.TooLong e) {
            throw new GenException("Template '" + entry.template + "' produced more than " + MAX_OUTPUT_CHARS + " characters; is a loop running away?", e)
                .at(entry.template, null, null);
        } catch (IOException e) {
            throw new GenException("Cannot read template '" + entry.template + "': " + e.getMessage(), e);
        }
        if (fileName.isEmpty()) throw new GenException("Template '" + entry.id + "' produced an empty file name.");

        Path root0 = layerRoot(cfg, target, entry.layer);
        Path abs;
        switch (entry.kind.toUpperCase(Locale.ROOT)) {
            case "RESOURCE" -> abs = root0.resolve(cfg.resourceRoot).resolve(fileName);
            case "ROOT" -> abs = root0.resolve(fileName);
            default -> abs = root0.resolve(cfg.sourceRoot).resolve(pkg.replace('.', '/')).resolve(fileName);
        }
        abs = abs.toAbsolutePath().normalize();
        if (!abs.startsWith(root0.toAbsolutePath().normalize()))
            throw new GenException("Template '" + entry.id + "' would write outside its root: " + fileName);

        boolean java = fileName.endsWith(".java");
        if (java) content = JavaTidy.tidy(content, pkg);
        else content = content.replace("\r\n", "\n");

        GeneratedFile gf = new GeneratedFile();
        gf.id = entry.id;
        gf.layer = entry.layer;
        gf.language = languageOf(fileName);
        gf.absPath = abs.toString();
        gf.path = displayPath(target, abs, entry.layer, root0);
        gf.content = content;
        String prev = seen.put(gf.absPath, entry.id);
        if (prev != null)
            throw new GenException("Two templates ('" + prev + "' and '" + entry.id + "') would write the same file: " + gf.path
                + ". Check your naming patterns and layer packages.");
        if (!cfg.targetDir.isBlank() && Files.isRegularFile(abs)) {
            try {
                gf.status = Files.readString(abs, StandardCharsets.UTF_8).replace("\r\n", "\n").equals(content) ? "IDENTICAL" : "EXISTS";
            } catch (IOException e) {
                gf.status = "EXISTS";
            }
        }
        result.files.add(gf);
    }

    private static List<String> imports(List<TypeRef> types, Map<String, String> fqnByKey) {
        TreeSet<String> out = new TreeSet<>();
        for (TypeRef t : types) {
            out.addAll(t.allImports());
            for (String ref : t.allRefs()) {
                String fqn = fqnByKey.get(ref);
                if (fqn != null) out.add(fqn);
            }
        }
        return new ArrayList<>(out);
    }

    static Path targetPath(GeneratorConfig cfg) {
        String t = cfg.targetDir == null ? "" : cfg.targetDir.trim();
        return (t.isEmpty() ? Paths.get(".") : Paths.get(t)).toAbsolutePath().normalize();
    }

    static Path layerRoot(GeneratorConfig cfg, Path target, String layer) {
        String ro = cfg.layer(layer).rootDir;
        if (ro == null || ro.isBlank()) return target;
        Path p = Paths.get(ro.trim());
        return (p.isAbsolute() ? p : target.resolve(p)).toAbsolutePath().normalize();
    }

    private static String displayPath(Path target, Path abs, String layer, Path layerRoot) {
        if (abs.startsWith(target)) return target.relativize(abs).toString().replace('\\', '/');
        // layer lives outside the target directory (module override): keep it addressable in previews / ZIPs
        return "_external/" + layer + "/" + layerRoot.relativize(abs).toString().replace('\\', '/');
    }

    private static String languageOf(String name) {
        if (name.endsWith(".java")) return "java";
        if (name.endsWith(".xml")) return "xml";
        if (name.endsWith(".properties")) return "properties";
        if (name.endsWith(".yml") || name.endsWith(".yaml")) return "yaml";
        if (name.endsWith(".json")) return "json";
        if (name.endsWith(".md")) return "markdown";
        if (name.endsWith(".gradle")) return "groovy";
        return "plaintext";
    }
}
