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
    /** where generated tests go (TEST-1, TEST-2) */
    public String testRoot = "src/test/java";
    /** External template folder; empty = ~/.specforge/templates when it exists, else bundled templates only. */
    public String templatesDir = "";

    public Map<String, Layer> layers = defaultLayers();
    /** ARCH-1: LAYERED | FEATURE (a package per table) | MODULAR (Spring Modulith) | HEXAGONAL (ports and adapters) */
    public String architecture = "LAYERED";
    public Naming naming = new Naming();
    public Options options = new Options();
    public SecurityOptions security = new SecurityOptions();
    /** Code style (STYLE-7). The defaults reproduce the output of profiles saved before it existed (STYLE-8). */
    public Style style = new Style();
    /** Database flow: what list endpoints accept (CONTRACT-3). Defaults keep earlier output. */
    public ListOptions lists = new ListOptions();
    /** Database flow: where the code will live (CONTRACT-1). Default: a standalone Spring Boot project. */
    public Contract contract = new Contract();
    /** Database flow: Flyway (MIG-1 … MIG-3). Default: none, as before. */
    public Migrations migrations = new Migrations();
    /** Generated tests (TEST-1, TEST-2). Default: none, as before. */
    public Tests tests = new Tests();
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
        /** Database flow (CONTRACT-7): BASIC = development user | JWT = OAuth2 resource server (e.g. Keycloak) */
        public String databaseAuth = "BASIC";
        /** JWT: where roles come from. SCOPES = scope / scp claims | KEYCLOAK = realm_access.roles, plus the scopes */
        public String jwtRoles = "SCOPES";
        /** Database flow with JWT: role needed to read (GET); "" = any signed-in caller */
        public String readRole = "";
        /** Database flow with JWT: role needed to create, change or delete; "" = any signed-in caller */
        public String writeRole = "";

        @JsonIgnore
        public boolean databaseJwt() {
            return "JWT".equalsIgnoreCase(databaseAuth);
        }

        @JsonIgnore
        public boolean keycloakRoles() {
            return "KEYCLOAK".equalsIgnoreCase(jwtRoles);
        }
    }

    /**
     * CONTRACT-1, CONTRACT-8: generate a module for an existing project with its own conventions instead of a
     * standalone project. The only contract so far is admin-template@1 (Next.js + Spring Boot admin dashboard).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Contract {
        /** STANDALONE | ADMIN_TEMPLATE */
        public String kind = "STANDALONE";
        /** root of the admin-template checkout (holds backend/ and frontend/); files are written there */
        public String projectDir = "";
        /** Java package segment and name prefix of the module, e.g. "shop" → com.admintemplate.shop; "" = from the title */
        public String module = "";
        /** CREATE = a Flyway migration creates the tables | EXISTING = the tables are already in the project's database */
        public String tables = "CREATE";
        /** CREATE: prefix of the created tables, so they never collide with the project's own; null = "<module>_" */
        public String tablePrefix;
        /** entities with a generated Long key extend the project's BaseEntity (id, version, created_at, updated_at) */
        public boolean baseEntity = true;
        /** NEW = a permission "<module>.manage" guards changes | EXISTING = the code in existingPermission | NONE */
        public String permission = "NEW";
        public String existingPermission = "";
        /** SALES | WORKSPACE | ADMINISTRATION: where a new permission is listed in the permissions screen */
        public String permissionArea = "SALES";
        /** NEW permission: also grant it to the MANAGER role (else only ADMIN, until granted in the Permissions screen) */
        public boolean grantManagers = false;
        /** item_key of the menu section the entities are listed under (e.g. "sales"); "" = no menu entries */
        public String menuSection = "";
        /** also the pages: list, create / edit dialog and route per table, under frontend/apps/web (CONTRACT-6) */
        public boolean frontend = true;
        /** Flyway version of the module's migration; 0 = the next free one in the project */
        public int migrationVersion = 0;

        @JsonIgnore
        public boolean adminTemplate() {
            return "ADMIN_TEMPLATE".equalsIgnoreCase(kind);
        }

        @JsonIgnore
        public boolean createTables() {
            return !"EXISTING".equalsIgnoreCase(tables);
        }
    }

    /** Flyway in the generated project (database flow). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Migrations {
        /**
         * NONE | BASELINE = Flyway takes the existing database as version 1, changes start at V2 |
         * SCHEMA = BASELINE plus V1__baseline.sql, the selected tables' DDL, so empty databases get the schema too
         */
        public String flyway = "NONE";

        @JsonIgnore
        public boolean enabled() {
            return !"NONE".equalsIgnoreCase(flyway);
        }

        @JsonIgnore
        public boolean schema() {
            return "SCHEMA".equalsIgnoreCase(flyway);
        }
    }

    /** Tests in the generated project. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Tests {
        /** a web-layer test per controller (MockMvc, the service mocked): status codes, paging, validation, 404 */
        public boolean controllers = false;
        /**
         * ApplicationIT: the application against a database created from V1__baseline.sql (a container of the
         * source engine, H2 in memory): the mapping matches the schema and every table is readable. Needs SCHEMA.
         */
        public boolean application = false;
    }

    /** List endpoints of the database flow (CONTRACT-3). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ListOptions {
        /** NONE | Q = a q parameter searches the columns marked "search" (case-insensitive, contains) */
        public String textSearch = "NONE";
        /** true = &lt;property&gt;From and &lt;property&gt;To for the columns marked "range" (inclusive) */
        public boolean rangeFilters = false;
        /** REJECT = 400 for an unknown sort property | IGNORE = skip it; the key orders by default and breaks ties */
        public String unknownSort = "REJECT";

        @JsonIgnore
        public boolean q() {
            return "Q".equalsIgnoreCase(textSearch);
        }

        @JsonIgnore
        public boolean lenientSort() {
            return "IGNORE".equalsIgnoreCase(unknownSort);
        }
    }

    /**
     * How the generated code is written (STYLE-7). Every value is the user's choice; the UI offers presets
     * ({@link #PRESETS}) and marks "Modern" as the suggestion. Field defaults equal {@code CURRENT}, so a profile
     * saved without a style keeps producing the same files (STYLE-8).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Style {
        /** LEGACY_MAP = {status, error, message, details[]} | PROBLEM_DETAIL = RFC 9457 with errors[{field, message}] */
        public String errorFormat = "LEGACY_MAP";
        /** Database flow. SINGLE = one XxxDto in and out | REQUEST_RESPONSE = XxxRequest in, XxxResponse out (STYLE-9) */
        public String dtoSplit = "SINGLE";

        public Style() {}

        public Style(String errorFormat, String dtoSplit) {
            this.errorFormat = errorFormat;
            this.dtoSplit = dtoSplit;
        }

        @JsonIgnore
        public boolean problemDetail() {
            return "PROBLEM_DETAIL".equalsIgnoreCase(errorFormat);
        }

        @JsonIgnore
        public boolean requestResponse() {
            return "REQUEST_RESPONSE".equalsIgnoreCase(dtoSplit);
        }
    }

    /** Named style presets (STYLE-6); the UI applies one by copying its values, which the user can then change. */
    public static final Map<String, Style> PRESETS = Map.of(
        "CURRENT", new Style("LEGACY_MAP", "SINGLE"),
        "MODERN", new Style("PROBLEM_DETAIL", "REQUEST_RESPONSE"));

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

    /** The contract, also for profiles whose JSON has {@code "contract": null}. */
    @JsonIgnore
    public Contract contract() {
        return contract != null ? contract : new Contract();
    }

    /** The list options, also for profiles whose JSON has {@code "lists": null}. */
    @JsonIgnore
    public ListOptions lists() {
        return lists != null ? lists : new ListOptions();
    }

    /** The style, also for profiles whose JSON has {@code "style": null}. */
    @JsonIgnore
    public Style style() {
        return style != null ? style : new Style();
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
        if (!oneOf(style().errorFormat, "LEGACY_MAP", "PROBLEM_DETAIL"))
            e.add("Error format must be LEGACY_MAP or PROBLEM_DETAIL.");
        if (!oneOf(style().dtoSplit, "SINGLE", "REQUEST_RESPONSE"))
            e.add("DTO style must be SINGLE or REQUEST_RESPONSE.");
        if (!oneOf(lists().textSearch, "NONE", "Q"))
            e.add("Text search must be NONE or Q.");
        if (!oneOf(lists().unknownSort, "REJECT", "IGNORE"))
            e.add("Unknown sort handling must be REJECT or IGNORE.");
        if (!oneOf(security.databaseAuth, "BASIC", "JWT"))
            e.add("Database-flow authentication must be BASIC or JWT.");
        if (!oneOf(security.jwtRoles, "SCOPES", "KEYCLOAK"))
            e.add("JWT roles must come from SCOPES or KEYCLOAK.");
        for (String role : new String[] {security.readRole, security.writeRole})
            if (role != null && !role.isBlank() && !role.trim().matches("[A-Za-z0-9_.:-]+"))
                e.add("Role '" + role + "' may only contain letters, digits and _ . : -");
        if (!oneOf(architecture == null ? "LAYERED" : architecture, "LAYERED", "FEATURE", "MODULAR", "HEXAGONAL"))
            e.add("Architecture must be LAYERED, FEATURE, MODULAR or HEXAGONAL.");
        if (!oneOf(migrations == null ? "NONE" : migrations.flyway, "NONE", "BASELINE", "SCHEMA"))
            e.add("Flyway must be NONE, BASELINE or SCHEMA.");
        if (testRoot == null || testRoot.isBlank()) e.add("Test root must not be empty.");
        Contract ct = contract();
        if (!oneOf(ct.kind, "STANDALONE", "ADMIN_TEMPLATE"))
            e.add("Target must be STANDALONE or ADMIN_TEMPLATE.");
        if (!oneOf(ct.tables, "CREATE", "EXISTING"))
            e.add("Tables must be CREATE or EXISTING.");
        if (!oneOf(ct.permission, "NEW", "EXISTING", "NONE"))
            e.add("Permission must be NEW, EXISTING or NONE.");
        if (!oneOf(ct.permissionArea, "SALES", "WORKSPACE", "ADMINISTRATION"))
            e.add("Permission area must be SALES, WORKSPACE or ADMINISTRATION.");
        if (ct.module != null && !ct.module.isBlank() && !ct.module.trim().matches("[a-z][a-z0-9]{0,29}"))
            e.add("Module name '" + ct.module + "' must be lower-case letters and digits, starting with a letter (it becomes a Java package).");
        if (ct.tablePrefix != null && !ct.tablePrefix.isEmpty() && !ct.tablePrefix.matches("[a-z][a-z0-9_]{0,19}"))
            e.add("Table prefix '" + ct.tablePrefix + "' must be lower-case letters, digits and _.");
        if ("EXISTING".equalsIgnoreCase(ct.permission) && (ct.existingPermission == null || !ct.existingPermission.trim().matches("[A-Za-z0-9_.]+")))
            e.add("Name the existing permission code to use, e.g. catalog.manage.");
        if (ct.menuSection != null && !ct.menuSection.isBlank() && !ct.menuSection.trim().matches("[A-Za-z0-9_-]{1,50}"))
            e.add("Menu section must be an item_key such as sales.");
        if (ct.migrationVersion < 0) e.add("Migration version must be 0 (next free) or positive.");
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
