package io.specforge.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.specforge.util.Names;

import java.util.*;

/** Everything the user can configure in the UI. Serialised as-is into profile files. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GeneratorConfig {

    public static final List<String> LAYER_KEYS = List.of(
        "entities", 
        "models", 
        "enums", 
        "repositories", 
        "services", 
        "controllers", 
        "security", 
        "exceptions", 
        "project")
    ;

    public String targetDir = Path0.defaultTarget();
    public String basePackage = "com.example.api";
    public String sourceRoot = "src/main/java";
    public String resourceRoot = "src/main/resources";
    /** External template folder; empty = ~/.specforge/templates when it exists, else bundled templates only. */
    public String templatesDir = "";

    public Map<String, Layer> layers = defaultLayers();
    public Naming naming = new Naming();
    public Options options = new Options();
    public SecurityOptions security = new SecurityOptions();
    /** Database flow: the user's decisions (PLAN-1), saved with the profile (PLAN-2); null in the spec flow. */
    public io.specforge.plan.GenerationPlan databasePlan;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Layer {
        public boolean enabled = true;
        /** Package relative to basePackage ("" = base package itself). */
        public String subPackage = "";
        /** Optional module root override (absolute, or relative to targetDir). Empty = targetDir. */
        public String rootDir = "";

        public Layer() {}

        public Layer(boolean enabled, String subPackage) {
            this.enabled = enabled;
            this.subPackage = subPackage;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Naming {
        /** Patterns; {Name} is replaced by the PascalCase base name. */
        public String entity = "{Name}";
        public String model = "{Name}";
        public String enumType = "{Name}";
        public String repository = "{Name}Repository";
        public String service = "{Name}Service";
        public String controller = "{Name}Controller";
        /** PLURAL_SNAKE | SNAKE | AS_IS */
        public String tableStyle = "PLURAL_SNAKE";
        public String tablePrefix = "";
        /** TAG | PATH */
        public String resourceGrouping = "TAG";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Options {
        public boolean lombok = false;
        public boolean validation = true;
        /** JAKARTA | JAVAX */
        public String namespace = "JAKARTA";
        /** Defaults follow start.spring.io (bundled metadata); profiles that pin a version keep it (P2). */
        public String javaVersion = io.specforge.spring.SpringMetadataSource.current().javaDefault;
        public String springBootVersion = io.specforge.spring.SpringMetadataSource.current().bootDefault;
        /** H2 | POSTGRES */
        public String database = "H2";
        /** OffsetDateTime | LocalDateTime | Instant | ZonedDateTime */
        public String dateTimeType = "OffsetDateTime";
        /** Blank = derive from servers[0].url / basePath in the spec; "/" = none. */
        public String basePath = "";
        public boolean generateProjectFiles = true;
        public String groupId = "";
        public String artifactId = "";
        /** AUTO = detect; ALL_ENTITIES = every object schema becomes an entity */
        public String entityDetection = "AUTO";
        /** DATABASE = JPA entities and repositories; PROXY = services call the upstream API the spec describes */
        public String serviceMode = "DATABASE";
        /** PROXY mode: upstream base URL; blank = the spec's server URL (resolved against the spec's URL when relative). */
        public String upstreamUrl = "";

        @JsonIgnore
        public boolean isJakarta() {
            return !"JAVAX".equalsIgnoreCase(namespace);
        }

        @JsonIgnore
        public boolean isProxy() {
            return "PROXY".equalsIgnoreCase(serviceMode);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SecurityOptions {
        public boolean enabled = true;
        /** MATCHERS (URL rules in SecurityConfig) | METHOD (@PreAuthorize) | BOTH */
        public String accessRules = "MATCHERS";
        public String scopeAuthorityPrefix = "SCOPE_";
        /**
         * PROXY mode only. FORWARD = pass the caller's credentials through, the upstream API checks them;
         * SERVICE_ACCOUNT = callers authenticate here, the app calls upstream with its own configured credential.
         */
        public String upstreamAuth = "FORWARD";
    }

    public static Map<String, Layer> defaultLayers() {
        Map<String, Layer> m = new LinkedHashMap<>();
        m.put("entities", new Layer(true, "entity"));
        m.put("models", new Layer(true, "model"));
        m.put("enums", new Layer(true, "model"));
        m.put("repositories", new Layer(true, "repository"));
        m.put("services", new Layer(true, "service"));
        m.put("controllers", new Layer(true, "controller"));
        m.put("security", new Layer(true, "security"));
        m.put("exceptions", new Layer(true, "exception"));
        m.put("project", new Layer(true, ""));
        return m;
    }

    public static GeneratorConfig defaults() {
        return new GeneratorConfig();
    }

    @JsonIgnore
    public Layer layer(String key) {
        Layer l = layers == null ? null : layers.get(key);
        return l != null ? l : defaultLayers().getOrDefault(key, new Layer());
    }

    @JsonIgnore
    public String layerPackage(String key) {
        String sub = layer(key).subPackage == null ? "" : layer(key).subPackage.trim();
        return sub.isEmpty() ? basePackage : basePackage + "." + sub;
    }

    /** Validation errors (empty = ok). */
    public List<String> validate() {
        List<String> e = new ArrayList<>();
        if (!Names.isValidPackage(basePackage) || basePackage == null || basePackage.isBlank())
            e.add("Base package '" + basePackage + "' is not a valid Java package name.");
        for (String k : LAYER_KEYS) {
            String sub = layer(k).subPackage;
            if (!Names.isValidPackage(sub == null ? "" : sub.trim()))
                e.add("Sub-package for layer '" + k + "' ('" + sub + "') is not a valid package name.");
        }
        checkPattern(e, "entity", naming.entity);
        checkPattern(e, "model", naming.model);
        checkPattern(e, "enum", naming.enumType);
        checkPattern(e, "repository", naming.repository);
        checkPattern(e, "service", naming.service);
        checkPattern(e, "controller", naming.controller);
        if (!oneOf(naming.tableStyle, "PLURAL_SNAKE", "SNAKE", "AS_IS")) 
            e.add("Unknown table style: " + naming.tableStyle);
        if (!oneOf(naming.resourceGrouping, "TAG", "PATH")) 
            e.add("Unknown resource grouping: " + naming.resourceGrouping);
        if (!oneOf(options.namespace, "JAKARTA", "JAVAX")) 
            e.add("Namespace must be JAKARTA or JAVAX.");
        if (!oneOf(options.database, "H2", "POSTGRES")) 
            e.add("Database must be H2 or POSTGRES.");
        if (!oneOf(options.dateTimeType, "OffsetDateTime", "LocalDateTime", "Instant", "ZonedDateTime"))
            e.add("Unsupported date-time type: " + options.dateTimeType);
        if (!oneOf(options.entityDetection, "AUTO", "ALL_ENTITIES")) 
            e.add("Unknown entity detection mode.");
        if (!oneOf(security.accessRules, "MATCHERS", "METHOD", "BOTH"))
            e.add("Unknown access rule style.");
        if (!oneOf(options.serviceMode, "DATABASE", "PROXY"))
            e.add("Service mode must be DATABASE or PROXY.");
        if (!oneOf(security.upstreamAuth, "FORWARD", "SERVICE_ACCOUNT"))
            e.add("Upstream authentication must be FORWARD or SERVICE_ACCOUNT.");
        String up = options.upstreamUrl == null ? "" : options.upstreamUrl.trim();
        if (!up.isEmpty() && !up.matches("(?i)https?://[^\\s/?#]+[^\\s?#]*"))
            e.add("Upstream URL must be an absolute http(s) URL without query or fragment.");
        if (sourceRoot == null || sourceRoot.isBlank()) 
            e.add("Source root must not be empty.");
        if (resourceRoot == null || resourceRoot.isBlank()) 
            e.add("Resource root must not be empty.");
        return e;
    }

    private static void checkPattern(List<String> e, String what, String p) {
        if (p == null || !p.contains("{Name}")) 
            e.add("Naming pattern for " + what + " must contain {Name}.");
        else if (!Names.isJavaIdentifier(p.replace("{Name}", "X")))
            e.add("Naming pattern for " + what + " ('" + p + "') does not produce a valid class name.");
    }

    private static boolean oneOf(String v, String... allowed) {
        for (String a : allowed) if (a.equalsIgnoreCase(v)) 
            return true;
        return false;
    }

    /** Tiny holder so the field initialiser above stays readable. */
    static final class Path0 {
        static String defaultTarget() {
            return java.nio.file.Paths.get(System.getProperty("user.dir", "."), "generated").toAbsolutePath().normalize().toString();
        }
    }
}
