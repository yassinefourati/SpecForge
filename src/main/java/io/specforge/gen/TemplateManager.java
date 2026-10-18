package io.specforge.gen;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import freemarker.cache.ClassTemplateLoader;
import freemarker.cache.FileTemplateLoader;
import freemarker.cache.MultiTemplateLoader;
import freemarker.cache.TemplateLoader;
import freemarker.core.TemplateClassResolver;
import freemarker.template.Configuration;
import freemarker.template.DefaultObjectWrapperBuilder;
import freemarker.template.TemplateExceptionHandler;
import io.specforge.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Resolves templates: an optional external folder wins over the templates bundled in the JAR, file by file.
 * The manifest (manifest.json) says which template renders what, for which layer, and where it is written.
 */
public class TemplateManager {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entry {
        public String id = "";
        public String layer = "";
        /** ENTITY | DTO | ENUM | RESOURCE | GLOBAL; database flow also USECASE (per use case) and USECASE_RECORD (per record) */
        public String scope = "GLOBAL";
        /** JAVA (default) | RESOURCE (under resourceRoot) | ROOT (project root) */
        public String kind = "JAVA";
        public String template = "";
        /** FreeMarker string, e.g. [=entity.className].java */
        public String fileName = "";
        /** Optional FreeMarker boolean expression, e.g. security.present */
        public String when = "";
        /**
         * SPEC | DATABASE | ANY: which source flow renders this template. Missing: per-item scopes default to SPEC
         * (so template folders exported before the database flow keep working), GLOBAL defaults to ANY.
         */
        public String flow = "";
        public String description = "";
        /** ARCH-1: the per-table package the file goes to, when it is not the layer's (e.g. "domain"); "" = the layer */
        @com.fasterxml.jackson.annotation.JsonProperty("package")
        public String packageKey = "";

        public boolean rendersFor(String sourceKind) {
            String f = flow == null || flow.isBlank() ? ("GLOBAL".equalsIgnoreCase(scope) ? "ANY" : "SPEC") : flow.trim().toUpperCase(java.util.Locale.ROOT);
            return f.equals("ANY") || f.equals(sourceKind);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Manifest {
        public List<Entry> templates = new ArrayList<>();
        /** an architecture's overlay: base entries it replaces (ARCH-6) */
        public List<String> remove = new ArrayList<>();
    }

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private final Path externalDir;
    /** classpath folder of the bundled templates: "/templates", or a contract's own set (CONTRACT-8) */
    private final String bundledRoot;
    /** an architecture's templates, laid over the others: its manifest removes and adds entries (ARCH-6); null = none */
    private String overlayRoot;
    /** unsaved Studio edits by template name; they win over the folder and the bundled templates (STU-3) */
    private final Map<String, String> drafts = new LinkedHashMap<>();

    public TemplateManager(Path externalDir) {
        this(externalDir, "/templates");
    }

    private TemplateManager(Path externalDir, String bundledRoot) {
        this.externalDir = externalDir != null && Files.isDirectory(externalDir) ? externalDir : null;
        this.bundledRoot = bundledRoot;
    }

    /** The bundled template set of a contract, e.g. "admin-template" (templates/contracts/admin-template/). */
    public static TemplateManager contract(String name) {
        if (!SAFE_NAME.matcher(name).matches()) throw new IllegalArgumentException("Bad contract name: " + name);
        return new TemplateManager(null, "/templates/contracts/" + name);
    }

    /** A copy with an architecture's bundled overlay, e.g. "hexagonal" (templates/architectures/hexagonal/). */
    public TemplateManager withArchitecture(String name) {
        if (!SAFE_NAME.matcher(name).matches()) throw new IllegalArgumentException("Bad architecture name: " + name);
        TemplateManager t = new TemplateManager(externalDir, bundledRoot);
        t.drafts.putAll(drafts);
        t.overlayRoot = "/templates/architectures/" + name;
        return t;
    }

    /** A copy that renders {@code name} from {@code content} instead of its file. */
    public TemplateManager withDraft(String name, String content) {
        TemplateManager t = new TemplateManager(externalDir, bundledRoot);
        t.overlayRoot = overlayRoot;
        t.drafts.putAll(drafts);
        if (name != null && content != null && SAFE_NAME.matcher(name).matches()) t.drafts.put(name, content);
        return t;
    }

    public Path externalDir() {
        return externalDir;
    }

    public static Path resolveDir(String configured, Path home) {
        if (configured != null && !configured.isBlank()) return Paths.get(configured.trim()).toAbsolutePath().normalize();
        Path d = home.resolve("templates");
        return Files.isDirectory(d) ? d : null;
    }

    public Manifest manifest() throws IOException {
        Manifest base = baseManifest();
        if (overlayRoot == null) return base;
        Manifest overlay;
        try (InputStream in = TemplateManager.class.getResourceAsStream(overlayRoot + "/manifest.json")) {
            if (in == null) throw new IOException("Bundled " + overlayRoot.substring(1) + "/manifest.json is missing from the JAR.");
            overlay = Json.MAPPER.readValue(in, Manifest.class);
        }
        Manifest out = new Manifest();
        for (Entry e : base.templates) if (!overlay.remove.contains(e.id)) out.templates.add(e);
        out.templates.addAll(overlay.templates);
        return out;
    }

    private Manifest baseManifest() throws IOException {
        if (drafts.containsKey("manifest.json")) return Json.MAPPER.readValue(drafts.get("manifest.json"), Manifest.class);
        if (externalDir != null && Files.isRegularFile(externalDir.resolve("manifest.json"))) {
            try {
                return Json.MAPPER.readValue(Files.readString(externalDir.resolve("manifest.json"), StandardCharsets.UTF_8), Manifest.class);
            } catch (IOException e) {
                throw new IOException("External manifest.json is invalid: " + e.getMessage(), e);
            }
        }
        return bundledManifest();
    }

    private Manifest bundledManifest() throws IOException {
        try (InputStream in = TemplateManager.class.getResourceAsStream(bundledRoot + "/manifest.json")) {
            if (in == null) throw new IOException("Bundled " + bundledRoot.substring(1) + "/manifest.json is missing from the JAR.");
            return Json.MAPPER.readValue(in, Manifest.class);
        }
    }

    public Configuration configuration() throws IOException {
        List<TemplateLoader> loaders = new ArrayList<>();
        if (!drafts.isEmpty()) {
            freemarker.cache.StringTemplateLoader draftLoader = new freemarker.cache.StringTemplateLoader();
            drafts.forEach(draftLoader::putTemplate);
            loaders.add(draftLoader);
        }
        if (externalDir != null) loaders.add(new FileTemplateLoader(externalDir.toFile()));
        if (overlayRoot != null) loaders.add(new ClassTemplateLoader(TemplateManager.class, overlayRoot));
        loaders.add(new ClassTemplateLoader(TemplateManager.class, bundledRoot));
        Configuration c = new Configuration(Configuration.VERSION_2_3_32);
        c.setTemplateLoader(new MultiTemplateLoader(loaders.toArray(new TemplateLoader[0])));
        c.setDefaultEncoding("UTF-8");
        // [=expr] for interpolation so that ${...} in Spring / Maven files is plain text
        c.setInterpolationSyntax(Configuration.SQUARE_BRACKET_INTERPOLATION_SYNTAX);
        c.setTagSyntax(Configuration.ANGLE_BRACKET_TAG_SYNTAX);
        c.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        c.setLogTemplateExceptions(false);
        c.setWrapUncheckedExceptions(true);
        c.setNumberFormat("computer");
        c.setBooleanFormat("c");
        c.setNewBuiltinClassResolver(TemplateClassResolver.ALLOWS_NOTHING_RESOLVER);
        DefaultObjectWrapperBuilder owb = new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_32);
        owb.setExposeFields(true);
        c.setObjectWrapper(owb.build());
        return c;
    }

    /** Effective templates with their origin, for the UI. */
    public List<Map<String, Object>> list() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        boolean extManifest = externalDir != null && Files.isRegularFile(externalDir.resolve("manifest.json"));
        for (Entry e : manifest().templates) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id);
            m.put("layer", e.layer);
            m.put("scope", e.scope);
            m.put("template", e.template);
            m.put("fileName", e.fileName);
            m.put("when", e.when);
            m.put("description", e.description);
            m.put("flow", e.flow == null || e.flow.isBlank() ? ("GLOBAL".equalsIgnoreCase(e.scope) ? "ANY" : "SPEC") : e.flow.trim().toUpperCase(java.util.Locale.ROOT));
            m.put("source", externalDir != null && Files.isRegularFile(externalDir.resolve(e.template)) ? "EXTERNAL" : "BUNDLED");
            out.add(m);
        }
        Map<String, Object> mf = new LinkedHashMap<>();
        mf.put("id", "manifest");
        mf.put("layer", "-");
        mf.put("scope", "-");
        mf.put("template", "manifest.json");
        mf.put("fileName", "");
        mf.put("when", "");
        mf.put("description", "Which template renders what, and where it is written");
        mf.put("source", extManifest ? "EXTERNAL" : "BUNDLED");
        out.add(0, mf);
        return out;
    }

    public String source(String name) throws IOException {
        if (name == null || !SAFE_NAME.matcher(name).matches()) throw new IOException("Invalid template name.");
        if (drafts.containsKey(name)) return drafts.get(name);
        if (externalDir != null) {
            Path p = externalDir.resolve(name).normalize();
            if (p.startsWith(externalDir) && Files.isRegularFile(p)) return Files.readString(p, StandardCharsets.UTF_8);
        }
        try (InputStream in = TemplateManager.class.getResourceAsStream("/templates/" + name)) {
            if (in == null) throw new NoSuchFileException("No such template: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Copies the bundled templates (and manifest + README) into dir, never overwriting existing files. */
    /** The bundled (default) text of a template, or null when there is none. */
    public static String bundledSource(String name) throws IOException {
        if (name == null || !SAFE_NAME.matcher(name).matches()) return null;
        try (InputStream in = TemplateManager.class.getResourceAsStream("/templates/" + name)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static boolean safeName(String name) {
        return name != null && SAFE_NAME.matcher(name).matches();
    }

    public static Manifest bundled() throws IOException {
        return new TemplateManager(null).bundledManifest();
    }

    public static int exportDefaults(Path dir) throws IOException {
        Files.createDirectories(dir);
        List<String> names = new ArrayList<>(List.of("manifest.json", "README.txt"));
        try (InputStream in = TemplateManager.class.getResourceAsStream("/templates/manifest.json")) {
            Manifest m = Json.MAPPER.readValue(in, Manifest.class);
            for (Entry e : m.templates) if (!names.contains(e.template)) names.add(e.template);
        }
        int copied = 0;
        for (String n : names) {
            Path target = dir.resolve(n);
            if (Files.exists(target)) continue;
            try (InputStream in = TemplateManager.class.getResourceAsStream("/templates/" + n)) {
                if (in == null) continue;
                Files.write(target, in.readAllBytes());
                copied++;
            }
        }
        return copied;
    }
}
