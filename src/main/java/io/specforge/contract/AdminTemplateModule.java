package io.specforge.contract;

import io.specforge.config.GeneratorConfig;
import io.specforge.model.DbModel.DbEntity;
import io.specforge.model.DbModel.DbField;
import io.specforge.model.DbModel.DbManyToMany;
import io.specforge.model.DbModel.DbRelation;
import io.specforge.model.Model.ApiModel;
import io.specforge.model.Model.ClassDef;
import io.specforge.model.Model.Msg;
import io.specforge.util.Names;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The admin-template@1 contract (CONTRACT-2, CONTRACT-4, CONTRACT-8): what a database-flow model becomes as a
 * module of an admin-template backend ({@code com.admintemplate.<module>}). Everything the templates under
 * {@code templates/contracts/admin-template/} need is computed here, so they stay declarative: names as the
 * project's PostgreSQL sees them, typed list parameters, the request / response components, the Flyway migration
 * (tables, permission, menu) and the lines a developer adds to the project's hand-written registries.
 * <p>
 * The conventions are admin-template's at commit {@link #CONTRACT}: {@code common.BaseEntity},
 * {@code common.PageResponse}, {@code common.Paging}, {@code common.error.ApiException}, permission codes
 * {@code <area>.<action>} checked against {@code security/Permissions.java} at startup, menu rows in
 * {@code menu_item}, form constraints in {@code reference/FormConstraints}.
 */
public final class AdminTemplateModule {

    public static final String CONTRACT = "admin-template@1";
    public static final String ROOT_PACKAGE = "com.admintemplate";

    /** PostgreSQL's reserved key words (they need quotes as identifiers). */
    private static final Set<String> RESERVED = Set.of("all", "analyse", "analyze", "and", "any", "array", "as", "asc",
        "asymmetric", "authorization", "binary", "both", "case", "cast", "check", "collate", "collation", "column",
        "concurrently", "constraint", "create", "cross", "current_catalog", "current_date", "current_role",
        "current_schema", "current_time", "current_timestamp", "current_user", "default", "deferrable", "desc",
        "distinct", "do", "else", "end", "except", "false", "fetch", "for", "foreign", "freeze", "from", "full",
        "grant", "group", "having", "ilike", "in", "initially", "inner", "intersect", "into", "is", "isnull", "join",
        "lateral", "leading", "left", "like", "limit", "localtime", "localtimestamp", "natural", "not", "notnull",
        "null", "offset", "on", "only", "or", "order", "outer", "overlaps", "placing", "primary", "references",
        "returning", "right", "select", "session_user", "similar", "some", "symmetric", "system_user", "table",
        "tablesample", "then", "to", "trailing", "true", "union", "unique", "user", "using", "variadic", "verbose",
        "when", "where", "window", "with");
    private static final Pattern PLAIN = Pattern.compile("[a-z_][a-z0-9_]*");
    private static final Pattern MIGRATION = Pattern.compile("V(\\d+)__.*\\.sql");

    /** module name, e.g. "shop" */
    public String name = "";
    public String cap = "";
    public String basePackage = "";
    /** permission code guarding changes, "" when any signed-in user may change data */
    public String permission = "";
    /** a new code this module adds (and the registries need), else false */
    public boolean newPermission;
    /** Java constant for a new permission, e.g. SHOP_MANAGE */
    public String permissionConstant = "";
    public String permissionArea = "SALES";
    /** the MANAGER role is granted the new permission by the migration */
    public boolean grantManagers;
    public String menuSection = "";
    public int migrationVersion;
    public String migrationFile = "";
    public boolean createTables = true;
    public String tablePrefix = "";
    /** the project folder, when it was readable */
    public String projectDir = "";
    public List<Entity> entities = new ArrayList<>();
    /** in dependency order: an entity comes after the ones its required relations point to */
    public List<Entity> creationOrder = new ArrayList<>();
    public List<String> warnings = new ArrayList<>();
    /** the pages are generated too */
    public boolean frontend;
    /** some entity has a relation (the pages need the options hook) / a date-time with a zone (the input helper) */
    public boolean anyRelation;
    public boolean anyInstant;

    /** The module's UI texts, nested as the project's i18n/ui/<locale>.json holds them: {<module>: {<entity>: {...}}}. */
    public Map<String, Object> uiTexts() {
        Map<String, Object> mod = new LinkedHashMap<>();
        for (Entity x : entities) {
            Map<String, Object> ent = new LinkedHashMap<>();
            for (Map.Entry<String, String> t : x.messages.entrySet()) {
                String[] path = t.getKey().split("\\.");
                Map<String, Object> at = ent;
                for (int i = 0; i < path.length - 1; i++) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> next = (Map<String, Object>) at.computeIfAbsent(path[i], k -> new LinkedHashMap<String, Object>());
                    at = next;
                }
                at.put(path[path.length - 1], t.getValue());
            }
            mod.put(x.var, ent);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put(name, mod);
        return root;
    }

    /** {@link #uiTexts()} as pretty JSON, for the instructions. */
    public String uiTextsJson() {
        try {
            return io.specforge.util.Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(uiTexts());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public Entity of(DbEntity e) {
        for (Entity x : entities) if (x.db == e) return x;
        return null;
    }

    public boolean has(DbEntity e) {
        return of(e) != null;
    }

    /** One table of the module. */
    public static class Entity {
        public DbEntity db;
        public String className = "";
        /** JPA entity name, unique in the project's persistence unit (admin-template has a Customer of its own) */
        public String entityName = "";
        public String var = "";
        /** URL below /api, e.g. "shop/customers" */
        public String path = "";
        /** frontend route, e.g. "/shop/customers" */
        public String route = "";
        /** table name for @Table and for the DDL (quoted when needed) */
        public String table = "";
        public String ddlTable = "";
        public boolean baseEntity;
        /** IDENTITY | UUID | ASSIGNED */
        public String idStrategy = "ASSIGNED";
        public String idName = "id";
        public String idCap = "Id";
        public String idJavaType = "Long";
        public String idColumn = "";
        public String ddlIdColumn = "";
        public String ddlIdType = "";
        /** the key's type without the identity clause, for the columns that reference it */
        public String ddlKeyType = "";
        /** maximum length of a text key, null when none */
        public Integer idLength;
        public List<Column> columns = new ArrayList<>();
        public List<Relation> relations = new ArrayList<>();
        public List<ManyToMany> manyToMany = new ArrayList<>();
        public List<List<String>> uniques = new ArrayList<>();
        public List<Component> request = new ArrayList<>();
        public List<Component> response = new ArrayList<>();
        public List<Param> params = new ArrayList<>();
        /** entity attribute paths searched by q */
        public List<String> searchPaths = new ArrayList<>();
        /** API property → entity attribute path */
        public Map<String, String> sorts = new LinkedHashMap<>();
        public String defaultSort = "id";
        public Set<String> imports = new java.util.TreeSet<>();
        public boolean list, read, create, update, delete;
        /** FormConstraints key, e.g. "shopCustomer" */
        public String formKey = "";
        /** menu item_key and UI text key nav.<navKey> */
        public String navKey = "";
        /** human label, e.g. "Order items" */
        public String label = "";
        /** Java expression of the JSON body the generated test sends on create (see {@link #samples}) */
        public String createBody = "";
        /** a second, different body for the update call */
        public String updateBody = "";
        /** the test cannot create a row (a required relation without a creatable target) */
        public boolean testable = true;

        // ---- frontend (CONTRACT-6)
        /** folder and file stem, e.g. "order-item" */
        public String kebab = "";
        /** "Order item", "order item", "order items" */
        public String singular = "";
        public String singularLower = "";
        public String pluralLower = "";
        /** the response property that names a row in pickers (a text column, else the key) */
        public String labelProp = "";
        /** the TypeScript type of the key: number | string */
        public String idTs = "number";
        public List<TsProp> tsResponse = new ArrayList<>();
        public List<TsProp> tsRequest = new ArrayList<>();
        public List<UiField> form = new ArrayList<>();
        public List<UiColumn> listColumns = new ArrayList<>();
        /** UI texts under <module>.<var>: key → English */
        public Map<String, String> messages = new LinkedHashMap<>();

        public boolean hasParam(String kind) {
            return params.stream().anyMatch(p -> p.kind.equals(kind));
        }
    }

    public static class Column {
        public String name = "";
        public String cap = "";
        public String javaType = "";
        /** for @Column(name = ...) */
        public String column = "";
        public String ddlName = "";
        public String ddlType = "";
        public String ddlDefault = "";
        public boolean nullable = true;
        public Integer length;
        public Integer precision;
        public Integer scale;
        public boolean writable = true;
        /** the database fills it when the request leaves it out */
        public boolean dbDefault;
        public String jdbcTypeCode = "";
    }

    public static class Relation {
        public String name = "";
        public String cap = "";
        public String targetClass = "";
        public Entity target;
        public String column = "";
        public String ddlColumn = "";
        public boolean nullable = true;
        /** API property carrying the target's id, e.g. customerId */
        public String idName = "";
        public String idType = "Long";
    }

    public static class ManyToMany {
        public String name = "";
        public String cap = "";
        public String targetClass = "";
        public Entity target;
        public String joinTable = "";
        public String ddlJoinTable = "";
        public String joinColumn = "";
        public String inverseJoinColumn = "";
        public String ddlJoinColumn = "";
        public String ddlInverseJoinColumn = "";
        public String idName = "";
        public String idType = "Long";
    }

    /** A record component of the request or response, with how it is read from an entity {@code e}. */
    public static class Component {
        public String name = "";
        public String javaType = "";
        public List<String> validations = new ArrayList<>();
        /** Java expression over the entity variable "e" (responses) */
        public String read = "";
    }

    /** A property of a TypeScript interface. */
    public static class TsProp {
        public String name = "";
        public String type = "";
    }

    /** One input of the form, with the TypeScript expressions that move its value in and out. */
    public static class UiField {
        public String name = "";
        /** text | textarea | number | date | datetime-local | time | switch | select */
        public String kind = "text";
        public boolean required;
        /** the form state holds a string, or a boolean for switches */
        public String stateType = "string";
        /** initial state from the row being edited ({@code row}, null when new) */
        public String init = "";
        /** the request value from the form state ({@code form}) */
        public String submit = "";
        /** only set when creating (an assigned key) */
        public boolean createOnly;
        /** select: the target's list endpoint and the properties naming and identifying its rows */
        public String optionsPath = "";
        public String optionsLabel = "";
        public String optionsId = "";
    }

    /** One column of the list. */
    public static class UiColumn {
        public String name = "";
        public String sortKey = "";
        /** TypeScript expression over the row {@code r} */
        public String render = "";
        public boolean right;
    }

    /** A typed list parameter. */
    public static class Param {
        public String name = "";
        public String javaType = "";
        /** EQ | FROM | TO */
        public String kind = "EQ";
        /** entity attribute path, e.g. "customer.id" */
        public String path = "";
        /** DATE | DATE_TIME | TIME | "" — for @DateTimeFormat */
        public String iso = "";
    }

    // ------------------------------------------------------------------ building

    public static AdminTemplateModule build(ApiModel api, GeneratorConfig cfg, List<Msg> messages) {
        GeneratorConfig.Contract ct = cfg.contract();
        AdminTemplateModule m = new AdminTemplateModule();
        String chosen = ct.module == null ? "" : ct.module.trim();
        m.name = chosen.isEmpty() ? moduleName(api.title) : chosen;
        m.cap = Names.cap(m.name);
        m.basePackage = ROOT_PACKAGE + "." + m.name;
        m.createTables = ct.createTables();
        m.tablePrefix = m.createTables ? (ct.tablePrefix == null ? m.name + "_" : ct.tablePrefix) : "";
        m.permissionArea = ct.permissionArea.toUpperCase(Locale.ROOT);
        m.menuSection = ct.menuSection == null ? "" : ct.menuSection.trim();
        m.grantManagers = ct.grantManagers;
        m.frontend = ct.frontend;
        switch (ct.permission.toUpperCase(Locale.ROOT)) {
            case "NEW" -> {
                m.permission = m.name + ".manage";
                m.newPermission = true;
                m.permissionConstant = m.name.toUpperCase(Locale.ROOT) + "_MANAGE";
            }
            case "EXISTING" -> m.permission = ct.existingPermission.trim();
            default -> m.permission = "";
        }
        m.migrationVersion = ct.migrationVersion > 0 ? ct.migrationVersion : nextMigration(ct.projectDir, m, messages);
        m.migrationFile = "V" + m.migrationVersion + "__" + m.name + ".sql";

        // what the contract cannot express yet is left out, with the reason (never silently)
        Map<String, DbEntity> byClass = new LinkedHashMap<>();
        for (ClassDef c : api.entities) if (c.db != null) byClass.put(c.db.className, c.db);
        Set<String> out = new LinkedHashSet<>();
        for (DbEntity e : byClass.values()) {
            // (a foreign key to a column other than the key is already a plain column, see SuggestionEngine)
            if (e.id.composite()) reason(m, out, e, "it has a composite key, which admin-template modules do not support yet");
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (DbEntity e : byClass.values()) {
                if (out.contains(e.className)) continue;
                for (DbRelation r : e.relations) if (out.contains(r.targetClass)) {
                    reason(m, out, e, "it refers to " + r.targetClass + ", which is left out");
                    changed = true;
                    break;
                }
                if (out.contains(e.className)) continue;
                for (DbManyToMany mm : e.manyToMany) if (out.contains(mm.targetClass)) {
                    reason(m, out, e, "it refers to " + mm.targetClass + ", which is left out");
                    changed = true;
                    break;
                }
            }
        }
        if (!api.useCases.isEmpty())
            m.warnings.add("Use-case controllers are not generated for admin-template modules yet; their tables get their own endpoints.");

        for (DbEntity e : byClass.values()) if (!out.contains(e.className)) m.entities.add(entity(m, e, cfg));
        for (Entity x : m.entities) {
            for (Relation r : x.relations) r.target = byName(m, r.targetClass);
            for (ManyToMany mm : x.manyToMany) mm.target = byName(m, mm.targetClass);
        }
        // the API shape needs every target's key name (BaseEntity or not), so it comes second
        for (Entity x : m.entities) api(m, x);
        for (Entity x : m.entities) ui(x);
        for (Entity x : m.entities) {
            if (!x.relations.isEmpty()) m.anyRelation = true;
            if (x.form.stream().anyMatch(f -> f.init.contains("toLocalInput"))) m.anyInstant = true;
        }
        order(m);
        for (Entity x : m.entities) samples(x);
        for (String w : m.warnings) messages.add(new Msg("WARN", w));
        return m;
    }

    private static void reason(AdminTemplateModule m, Set<String> out, DbEntity e, String why) {
        out.add(e.className);
        m.warnings.add("Not generated in the admin-template module: " + e.className + " (table " + e.qualifiedName + "), because " + why + ".");
    }

    private static Entity byName(AdminTemplateModule m, String className) {
        for (Entity x : m.entities) if (x.className.equals(className)) return x;
        return null;
    }

    /** "Shop DB" → "shop": a valid package segment. */
    static String moduleName(String title) {
        String s = (title == null ? "" : title).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (s.isEmpty() || !Character.isLetter(s.charAt(0))) s = "data" + s;
        return s.length() > 30 ? s.substring(0, 30) : s;
    }

    /** The next free Flyway version in the project's migration folder; 1 with a warning when it cannot be read. */
    static int nextMigration(String projectDir, AdminTemplateModule m, List<Msg> messages) {
        Path dir = projectDir == null || projectDir.isBlank() ? null
            : Path.of(projectDir.trim()).resolve("backend/src/main/resources/db/migration");
        if (dir == null || !Files.isDirectory(dir)) {
            messages.add(new Msg("WARN", "The admin-template project folder is not set or has no backend/src/main/resources/db/migration, "
                + "so the module's migration is numbered 1. Set the project folder (or the migration version) before writing."));
            return 1;
        }
        m.projectDir = Path.of(projectDir.trim()).toAbsolutePath().normalize().toString();
        int max = 0;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Matcher x = MIGRATION.matcher(p.getFileName().toString());
                // the module's own earlier migration is replaced, not followed
                if (x.matches() && !p.getFileName().toString().endsWith("__" + m.name + ".sql")) max = Math.max(max, Integer.parseInt(x.group(1)));
            }
        } catch (IOException e) {
            messages.add(new Msg("WARN", "Cannot read " + dir + ": " + e.getMessage()));
        }
        return max + 1;
    }

    private static Entity entity(AdminTemplateModule m, DbEntity e, GeneratorConfig cfg) {
        Entity x = new Entity();
        x.db = e;
        x.className = e.className;
        x.entityName = m.cap + e.className;
        x.var = Names.decap(e.className);
        x.path = m.name + "/" + e.path;
        x.route = "/" + x.path;
        x.formKey = m.name + e.className;
        x.navKey = m.name + e.className;
        x.label = label(e.className);
        x.list = e.op("list");
        x.read = e.op("read");
        x.create = e.op("create");
        x.update = e.op("update");
        x.delete = e.op("delete");
        x.ddlTable = m.createTables ? ddlName(m.tablePrefix + lower(e.table)) : ddlName(plain(e.table));
        x.table = annotationName(m.createTables ? m.tablePrefix + lower(e.table) : plain(e.table));
        x.imports.addAll(e.entityImports);
        x.imports.addAll(e.dtoImports);

        DbField key = e.id.field;
        x.idColumn = annotationName(colName(m, key.column));
        x.ddlIdColumn = ddlName(colName(m, key.column));
        x.idJavaType = key.javaType;
        x.idStrategy = switch (e.id.strategy) {
            case "IDENTITY", "SEQUENCE" -> "Long".equals(key.javaType) || "Integer".equals(key.javaType) ? "IDENTITY" : "ASSIGNED";
            case "UUID" -> "UUID";
            default -> "ASSIGNED";
        };
        x.ddlKeyType = ddlType(key);
        x.idLength = key.length;
        x.ddlIdType = ddlType(key) + (x.idStrategy.equals("IDENTITY") ? " generated by default as identity" : "");
        // admin-template's BaseEntity: a generated Long key named id, version, created_at and updated_at
        Set<String> absorbed = Set.of("version", "created_at", "updated_at");
        boolean hasAudit = e.fields.stream().map(f -> lower(plain(f.column))).filter(absorbed::contains).count() == 3;
        x.baseEntity = cfg.contract().baseEntity && "Long".equals(key.javaType) && x.idStrategy.equals("IDENTITY")
            && (m.createTables || hasAudit && lower(plain(key.column)).equals("id"));
        x.idName = x.baseEntity ? "id" : key.name;
        x.idCap = Names.cap(x.idName);

        for (DbField f : e.fields) {
            if (x.baseEntity && absorbed.contains(lower(plain(f.column)))) continue;
            Column c = new Column();
            c.name = f.name;
            c.cap = f.cap;
            c.javaType = f.javaType;
            c.column = annotationName(colName(m, f.column));
            c.ddlName = ddlName(colName(m, f.column));
            c.ddlType = ddlType(f);
            c.nullable = f.nullable;
            c.length = f.length;
            c.precision = f.precision;
            c.scale = f.scale;
            c.writable = f.writable;
            // created columns are text / bytea / jsonb: a source engine's LOB type codes do not apply (only JSON does)
            c.jdbcTypeCode = m.createTables && !"SqlTypes.JSON".equals(f.jdbcTypeCode) ? "" : f.jdbcTypeCode;
            c.ddlDefault = m.createTables ? pgDefault(f.defaultValue, f.javaType) : "";
            c.dbDefault = m.createTables ? !c.ddlDefault.isEmpty() : f.dbDefault;
            if (m.createTables && f.defaultValue != null && c.ddlDefault.isEmpty() && !f.nullable && f.writable)
                m.warnings.add(x.className + "." + f.name + ": the default " + f.defaultValue.trim() + " has no PostgreSQL equivalent here; "
                    + "the created column has no default, so the request must send a value.");
            if (m.createTables && !f.writable)
                m.warnings.add(x.className + "." + f.name + " is computed in the source database; the created column is a plain column.");
            x.columns.add(c);
        }
        if (m.createTables) for (Column c : x.columns) if (!c.writable) c.writable = true;
        for (DbRelation r : e.relations) {
            Relation rel = new Relation();
            rel.name = r.name;
            rel.cap = r.cap;
            rel.targetClass = r.targetClass;
            rel.column = annotationName(colName(m, r.column));
            rel.ddlColumn = ddlName(colName(m, r.column));
            rel.nullable = r.nullable;
            rel.idName = r.dtoField != null ? r.dtoField.name : Names.decap(r.name) + "Id";
            rel.idType = r.targetIdType;
            x.relations.add(rel);
        }
        for (DbManyToMany mm : e.manyToMany) {
            ManyToMany j = new ManyToMany();
            j.name = mm.name;
            j.cap = mm.cap;
            j.targetClass = mm.targetClass;
            String jt = m.createTables ? m.tablePrefix + lower(mm.joinTable) : plain(mm.joinTable);
            j.joinTable = annotationName(jt);
            j.ddlJoinTable = ddlName(jt);
            j.joinColumn = annotationName(colName(m, mm.joinColumn));
            j.inverseJoinColumn = annotationName(colName(m, mm.inverseJoinColumn));
            j.ddlJoinColumn = ddlName(colName(m, mm.joinColumn));
            j.ddlInverseJoinColumn = ddlName(colName(m, mm.inverseJoinColumn));
            j.idName = mm.dtoName;
            j.idType = mm.targetIdType;
            x.manyToMany.add(j);
            x.imports.add("java.util.Set");
            x.imports.add("java.util.LinkedHashSet");
        }
        for (List<String> u : e.uniqueColumns) {
            List<String> cols = new ArrayList<>();
            for (String c : u) cols.add(colName(m, c));
            x.uniques.add(cols);
        }
        return x;
    }

    /** Request, response, list parameters and sorts, once every relation's target is known. */
    private static void api(AdminTemplateModule m, Entity x) {
        DbEntity e = x.db;
        DbField key = e.id.field;
        // request: an assigned key, writable columns, relation ids, many-to-many ids (STYLE-9 rules)
        if (x.idStrategy.equals("ASSIGNED")) x.request.add(component(x.idName, x.idJavaType, key.validations, ""));
        for (Column c : x.columns) {
            if (!c.writable) continue;
            DbField f = field(e, c.name);
            List<String> v = new ArrayList<>(f == null ? List.of() : f.validations);
            if (m.createTables && !c.nullable && !c.dbDefault && !v.contains("@NotNull")) v.add(0, "@NotNull");
            x.request.add(component(c.name, c.javaType, v, ""));
        }
        for (Relation r : x.relations) x.request.add(component(r.idName, r.idType, r.nullable ? List.of() : List.of("@NotNull"), ""));
        for (ManyToMany j : x.manyToMany) x.request.add(component(j.idName, "Set<" + j.idType + ">", List.of(), ""));

        // response: key, columns, relation ids, many-to-many ids, BaseEntity's timestamps, deletable
        x.response.add(component(x.idName, x.idJavaType, List.of(), "e.get" + x.idCap + "()"));
        for (Column c : x.columns) x.response.add(component(c.name, c.javaType, List.of(), "e.get" + c.cap + "()"));
        for (Relation r : x.relations)
            x.response.add(component(r.idName, r.idType, List.of(), "e.get" + r.cap + "() == null ? null : e.get" + r.cap + "().get"
                + r.target.idCap + "()"));
        for (ManyToMany j : x.manyToMany) x.response.add(component(j.idName, "Set<" + j.idType + ">", List.of(), ""));
        if (x.baseEntity) {
            x.response.add(component("createdAt", "Instant", List.of(), "e.getCreatedAt()"));
            x.response.add(component("updatedAt", "Instant", List.of(), "e.getUpdatedAt()"));
            x.imports.add("java.time.Instant");
        }
        x.response.add(component("deletable", "boolean", List.of(), "canManage"));

        // list parameters: equality filters, q, ranges (the user's Map choices and list options, CONTRACT-3)
        Map<String, String> paths = new LinkedHashMap<>();
        Map<String, String> types = new LinkedHashMap<>();
        paths.put(key.name, x.idName);
        types.put(key.name, x.idJavaType);
        for (DbField f : e.fields) {
            paths.put(f.name, f.name);
            types.put(f.name, f.javaType);
        }
        for (Relation r : x.relations) {
            paths.put(r.idName, r.name + "." + r.target.idName);
            types.put(r.idName, r.idType);
        }
        for (String p : e.filterable) if (paths.containsKey(p)) x.params.add(param(apiName(x, key, p), types.get(p), "EQ", path(x, key, paths, p)));
        for (String p : e.rangeable) if (paths.containsKey(p)) {
            x.params.add(param(apiName(x, key, p) + "From", types.get(p), "FROM", path(x, key, paths, p)));
            x.params.add(param(apiName(x, key, p) + "To", types.get(p), "TO", path(x, key, paths, p)));
        }
        for (String p : e.searchable) if (paths.containsKey(p)) x.searchPaths.add(path(x, key, paths, p));
        for (String p : e.sortable) if (paths.containsKey(p)) x.sorts.put(apiName(x, key, p), path(x, key, paths, p));
        if (x.baseEntity) x.sorts.putIfAbsent("createdAt", "createdAt");
        x.defaultSort = x.idName;
    }

    /** API name of a property: the key is "id" when the entity uses BaseEntity. */
    private static String apiName(Entity x, DbField key, String p) {
        return p.equals(key.name) ? x.idName : p;
    }

    private static String path(Entity x, DbField key, Map<String, String> paths, String p) {
        return p.equals(key.name) ? x.idName : paths.get(p);
    }

    private static DbField field(DbEntity e, String name) {
        for (DbField f : e.fields) if (f.name.equals(name)) return f;
        return null;
    }

    private static Component component(String name, String type, List<String> validations, String read) {
        Component c = new Component();
        c.name = name;
        c.javaType = type;
        c.validations = new ArrayList<>(validations);
        c.read = read;
        return c;
    }

    private static Param param(String name, String type, String kind, String path) {
        Param p = new Param();
        p.name = name;
        p.javaType = type;
        p.kind = kind;
        p.path = path;
        p.iso = switch (type) {
            case "LocalDate" -> "DATE";
            case "LocalDateTime", "OffsetDateTime", "Instant" -> "DATE_TIME";
            case "LocalTime", "OffsetTime" -> "TIME";
            default -> "";
        };
        return p;
    }

    // ------------------------------------------------------------------ frontend

    private static final Set<String> NUMBERS = Set.of("Integer", "Long", "Short", "BigDecimal", "Double", "Float", "BigInteger", "int", "long");
    private static final Set<String> INSTANTS = Set.of("OffsetDateTime", "Instant", "ZonedDateTime");

    /** Types, form inputs, list columns and texts of the entity's pages (CONTRACT-6). */
    private static void ui(Entity x) {
        x.kebab = x.className.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
        x.singular = words(x.className);
        x.singularLower = x.singular.toLowerCase(Locale.ROOT);
        x.pluralLower = x.label.toLowerCase(Locale.ROOT);
        x.idTs = ts(x.idJavaType);
        Column labelCol = x.columns.stream().filter(c -> "String".equals(c.javaType) && !"SqlTypes.JSON".equals(c.jdbcTypeCode))
            .findFirst().orElse(null);
        x.labelProp = labelCol != null ? labelCol.name : x.idName;
        for (Component c : x.response) x.tsResponse.add(tsProp(c.name, c.javaType));
        for (Component c : x.request) x.tsRequest.add(tsProp(c.name, c.javaType));

        x.messages.put("title", x.label);
        x.messages.put("new", "New " + x.singularLower);
        x.messages.put("edit", "Edit " + x.singularLower);
        x.messages.put("empty", "No " + x.pluralLower + " yet");
        x.messages.put("search", "Search " + x.pluralLower);
        x.messages.put("deleteTitle", "Delete this " + x.singularLower + "?");

        for (Component c : x.request) {
            // many-to-many ids and binary values are kept as they are and edited through the API
            if (c.javaType.startsWith("Set<") || "byte[]".equals(c.javaType)) continue;
            UiField f = new UiField();
            f.name = c.name;
            f.required = c.validations.contains("@NotNull") || c.validations.contains("@NotBlank");
            x.messages.put("fields." + c.name, words(c.name));
            String v = "form." + c.name;
            String row = "row?." + c.name;
            Relation rel = x.relations.stream().filter(r -> r.idName.equals(c.name)).findFirst().orElse(null);
            Column col = x.columns.stream().filter(k -> k.name.equals(c.name)).findFirst().orElse(null);
            if (rel != null) {
                f.kind = "select";
                f.optionsPath = "/api/" + rel.target.path;
                f.optionsLabel = rel.target.labelProp;
                f.optionsId = rel.target.idName;
                f.init = row + " != null ? String(" + row + ") : ''";
                f.submit = v + " === '' ? null : " + ("number".equals(ts(rel.idType)) ? "Number(" + v + ")" : v);
            } else if ("Boolean".equals(c.javaType)) {
                f.kind = "switch";
                f.stateType = "boolean";
                f.init = row + " ?? false";
                f.submit = v;
            } else if (NUMBERS.contains(c.javaType)) {
                f.kind = "number";
                f.init = row + " != null ? String(" + row + ") : ''";
                f.submit = v + " === '' ? null : Number(" + v + ")";
            } else if ("LocalDate".equals(c.javaType)) {
                f.kind = "date";
                f.init = row + " ?? ''";
                f.submit = v + " || null";
            } else if ("LocalDateTime".equals(c.javaType)) {
                f.kind = "datetime-local";
                f.init = row + " ? " + row + ".slice(0, 16) : ''";
                f.submit = v + " || null";
            } else if (INSTANTS.contains(c.javaType)) {
                f.kind = "datetime-local";
                f.init = row + " ? toLocalInput(" + row + ") : ''";
                f.submit = v + " ? new Date(" + v + ").toISOString() : null";
            } else if ("LocalTime".equals(c.javaType) || "OffsetTime".equals(c.javaType)) {
                f.kind = "time";
                f.init = row + " ? " + row + ".slice(0, 5) : ''";
                f.submit = v + " || null";
            } else {
                boolean json = col != null && "SqlTypes.JSON".equals(col.jdbcTypeCode);
                f.createOnly = c.name.equals(x.idName);
                boolean longText = !f.createOnly && (col == null || col.length == null || col.length > 255);
                f.kind = json || longText ? "textarea" : "text";
                f.init = row + " ?? ''";
                f.submit = v + " || null";
            }
            x.form.add(f);
        }

        // the list: the key, then up to five values and the relations (long text, JSON and binary stay in the form)
        x.listColumns.add(column(x, x.idName, x.idJavaType));
        for (Column c : x.columns) {
            if (x.listColumns.size() >= 6) break;
            boolean longText = "String".equals(c.javaType) && (c.length == null || c.length > 255);
            if (longText || "SqlTypes.JSON".equals(c.jdbcTypeCode) || "byte[]".equals(c.javaType)) continue;
            x.listColumns.add(column(x, c.name, c.javaType));
        }
        for (Relation r : x.relations) {
            if (x.listColumns.size() >= 7) break;
            x.listColumns.add(column(x, r.idName, r.idType));
        }
        for (UiColumn u : x.listColumns) x.messages.putIfAbsent("fields." + u.name, words(u.name));
    }

    private static UiColumn column(Entity x, String name, String javaType) {
        UiColumn u = new UiColumn();
        u.name = name;
        u.sortKey = x.sorts.containsKey(name) ? name : "";
        String v = "r." + name;
        if ("Boolean".equals(javaType)) u.render = v + " ? '✓' : '–'";
        else if ("LocalDate".equals(javaType)) u.render = v + " ? format.date(" + v + ") : '–'";
        else if ("LocalDateTime".equals(javaType) || INSTANTS.contains(javaType)) u.render = v + " ? format.dateTime(" + v + ") : '–'";
        else if (NUMBERS.contains(javaType)) u.render = v + " != null ? format.number(" + v + ") : '–'";
        else u.render = v + " ?? '–'";
        u.right = NUMBERS.contains(javaType);
        return u;
    }

    private static TsProp tsProp(String name, String javaType) {
        TsProp p = new TsProp();
        p.name = name;
        boolean set = javaType.startsWith("Set<");
        String t = set ? ts(javaType.substring(4, javaType.length() - 1)) + "[]" : ts(javaType);
        p.type = set || javaType.equals("boolean") ? t : t + " | null";
        return p;
    }

    /** The TypeScript type of a JSON value of this Java type. */
    static String ts(String javaType) {
        if (NUMBERS.contains(javaType)) return "number";
        return "Boolean".equals(javaType) || "boolean".equals(javaType) ? "boolean" : "string";
    }

    /** "fullName" / "OrderItem" → "Full name" / "Order item". */
    static String words(String name) {
        String w = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ').toLowerCase(Locale.ROOT).trim();
        return w.isEmpty() ? name : Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }

    /** Entities ordered so that each comes after the targets of its required relations (for the test, and the reader). */
    private static void order(AdminTemplateModule m) {
        List<Entity> left = new ArrayList<>(m.entities);
        while (!left.isEmpty()) {
            Entity next = null;
            for (Entity x : left) {
                boolean ready = x.relations.stream().allMatch(r -> r.nullable || r.target == x || m.creationOrder.contains(r.target));
                if (ready) {
                    next = x;
                    break;
                }
            }
            if (next == null) {
                // a cycle of required relations: the rest cannot be created one by one
                for (Entity x : left) {
                    x.testable = false;
                    m.creationOrder.add(x);
                }
                break;
            }
            left.remove(next);
            m.creationOrder.add(next);
        }
        for (Entity x : m.creationOrder) {
            if (!x.create) x.testable = false;
            for (Relation r : x.relations) if (!r.nullable && (r.target == x || r.target == null || !r.target.testable)) x.testable = false;
        }
    }

    /**
     * Two valid request bodies per entity, as Java expressions for the generated test. Relation ids are the keys of
     * rows the test created before ({@code keys.get("Customer")}); text is {@code text(n, max)}, which adds the run's
     * random part so unique columns never clash; other values come from the column types.
     */
    private static void samples(Entity x) {
        x.createBody = body(x, 0);
        x.updateBody = body(x, 50);
    }

    private static String body(Entity x, int offset) {
        StringBuilder sb = new StringBuilder("\"{\"");
        int n = offset;
        boolean first = true;
        for (Component c : x.request) {
            n++;
            sb.append(" + ").append(javaString((first ? "" : ", ") + "\"" + c.name + "\": "));
            first = false;
            Relation rel = x.relations.stream().filter(r -> r.idName.equals(c.name)).findFirst().orElse(null);
            if (rel != null) {
                boolean creatable = rel.target != null && rel.target != x && rel.target.testable;
                sb.append(" + ").append(creatable ? "keys.get(" + javaString(rel.targetClass) + ")" : javaString("null"));
                continue;
            }
            if (c.javaType.startsWith("Set<")) {
                sb.append(" + ").append(javaString("[]"));
                continue;
            }
            Column col = x.columns.stream().filter(k -> k.name.equals(c.name)).findFirst().orElse(null);
            if ("String".equals(c.javaType) && (col == null || !"SqlTypes.JSON".equals(col.jdbcTypeCode))) {
                Integer len = col != null ? col.length : c.name.equals(x.idName) ? x.idLength : null;
                int max = len != null ? len : 0;
                sb.append(" + \"\\\"\" + text(\"").append(n).append("\", ").append(max).append(") + \"\\\"\"");
            } else {
                sb.append(" + ").append(javaString(literal(c.javaType, n, col)));
            }
        }
        return sb.append(" + \"}\"").toString();
    }

    /** A JSON literal for a non-text value. */
    private static String literal(String type, int n, Column col) {
        if (col != null && "SqlTypes.JSON".equals(col.jdbcTypeCode)) return "\"{}\"";
        String day = String.format("%02d", 1 + n % 28);
        return switch (type) {
            case "Integer", "Long", "Short" -> String.valueOf(n);
            case "BigDecimal", "Double", "Float" -> n + ".5";
            case "Boolean" -> n % 2 == 0 ? "true" : "false";
            case "LocalDate" -> "\"2026-01-" + day + "\"";
            case "LocalDateTime" -> "\"2026-01-" + day + "T10:00:00\"";
            case "OffsetDateTime", "Instant", "ZonedDateTime" -> "\"2026-01-" + day + "T10:00:00Z\"";
            case "LocalTime" -> "\"10:00:00\"";
            case "OffsetTime" -> "\"10:00:00Z\"";
            case "UUID" -> "\"00000000-0000-4000-8000-" + String.format("%012d", n) + "\"";
            case "byte[]" -> "\"AQID\"";
            default -> "null";
        };
    }

    /** A Java string literal. */
    static String javaString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ------------------------------------------------------------------ names and PostgreSQL types

    private static String colName(AdminTemplateModule m, String column) {
        return m.createTables ? lower(plain(column)) : plain(column);
    }

    /** A name without the quoting characters of the source engine. */
    static String plain(String name) {
        return name == null ? "" : name.replace("`", "").replace("\"", "").replace("[", "").replace("]", "");
    }

    static String lower(String name) {
        return plain(name).toLowerCase(Locale.ROOT);
    }

    /** For the DDL: quoted when PostgreSQL would fold or reject it. */
    static String ddlName(String name) {
        return PLAIN.matcher(name).matches() && !RESERVED.contains(name) ? name : "\"" + name.replace("\"", "\"\"") + "\"";
    }

    /** For JPA annotations: Hibernate quotes a name in backticks. */
    static String annotationName(String name) {
        return PLAIN.matcher(name).matches() && !RESERVED.contains(name) ? name : "`" + name + "`";
    }

    /** PostgreSQL column type for a mapped field, from its Java type and limits (not the source engine's type). */
    static String ddlType(DbField f) {
        if ("SqlTypes.JSON".equals(f.jdbcTypeCode)) return "jsonb";
        return switch (f.javaType) {
            case "String" -> f.length != null ? "varchar(" + f.length + ")" : "text";
            case "Integer" -> "integer";
            case "Long" -> "bigint";
            case "Short" -> "smallint";
            case "BigDecimal" -> f.precision != null ? "numeric(" + f.precision + ", " + f.scale + ")" : "numeric";
            case "BigInteger" -> "numeric(38)";
            case "Double" -> "double precision";
            case "Float" -> "real";
            case "Boolean" -> "boolean";
            case "LocalDate" -> "date";
            case "LocalTime" -> "time";
            case "OffsetTime" -> "time with time zone";
            case "LocalDateTime" -> "timestamp";
            case "OffsetDateTime", "Instant", "ZonedDateTime" -> "timestamptz";
            case "UUID" -> "uuid";
            case "byte[]" -> "bytea";
            default -> "text";
        };
    }

    private static final Pattern QUOTED = Pattern.compile("^\\(?'((?:[^']|'')*)'\\)?(?:::[\\w\\s]+)?$");
    private static final Pattern NUMBER = Pattern.compile("^\\(?\\(?(-?\\d+(?:\\.\\d+)?)\\)?\\)?$");

    /**
     * The source default as a PostgreSQL default, for the portable cases: literals, booleans and "now". Sequences,
     * functions and expressions return "" (the caller warns).
     */
    static String pgDefault(String source, String javaType) {
        if (source == null) return "";
        String s = source.trim();
        if (s.isEmpty() || s.equalsIgnoreCase("null")) return "";
        String l = s.toLowerCase(Locale.ROOT).replace("(", "").replace(")", "").trim();
        if (l.matches("current_timestamp\\d*|now|getdate|sysdate|systimestamp|localtimestamp|sysdatetime|current timestamp|datetime'now'")
            || l.startsWith("current_timestamp")) {
            return switch (javaType) {
                case "LocalDate" -> "current_date";
                case "LocalTime", "OffsetTime" -> "current_time";
                case "LocalDateTime" -> "localtimestamp";
                default -> "now()";
            };
        }
        if (l.equals("current_date") || l.equals("current date")) return "current_date";
        if (l.equals("true") || l.equals("false")) return "Boolean".equals(javaType) ? l : "";
        Matcher q = QUOTED.matcher(s);
        if (q.matches()) {
            if ("Boolean".equals(javaType)) {
                String v = q.group(1).toLowerCase(Locale.ROOT);
                return v.equals("1") || v.equals("true") || v.equals("t") ? "true" : v.equals("0") || v.equals("false") || v.equals("f") ? "false" : "";
            }
            return "'" + q.group(1) + "'";
        }
        Matcher n = NUMBER.matcher(s);
        if (n.matches()) {
            if ("Boolean".equals(javaType)) return n.group(1).equals("0") ? "false" : n.group(1).equals("1") ? "true" : "";
            return n.group(1);
        }
        return "";
    }

    /** "OrderItem" → "Order items". */
    static String label(String className) {
        String words = className.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        int last = words.lastIndexOf(' ') + 1;
        String plural = words.substring(0, last) + io.specforge.suggest.Inflection.plural(words.substring(last));
        return Character.toUpperCase(plural.charAt(0)) + plural.substring(1);
    }
}
