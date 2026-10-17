package io.specforge.mapper;

import io.specforge.config.GeneratorConfig;
import io.specforge.model.Model.*;
import io.specforge.util.Names;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.servers.ServerVariable;
import io.swagger.v3.oas.models.tags.Tag;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a parsed OpenAPI document into the template model: entities, DTOs, enums, resources and security.
 * One instance per mapping run.
 */
public class OpenApiMapper {

    private static final class Unit {
        String key;
        Schema<?> schema;
        String kind; // ENTITY | DTO | ENUM
        String className;
        boolean synthetic;
        String reason = "";
    }

    private static final class RawOp {
        String path;
        String httpMethod;
        Operation op;
        List<Parameter> pathLevel;
        String group;
    }

    private final OpenAPI api;
    private final GeneratorConfig cfg;
    private final Map<String, String> overrides;
    private final ApiModel out = new ApiModel();
    private final SchemaUtil.Resolver resolver = this::component;

    private final Map<String, Schema<?>> components = new LinkedHashMap<>();
    private final Set<String> objectComponents = new LinkedHashSet<>();
    private final Map<String, Unit> units = new LinkedHashMap<>();
    private final IdentityHashMap<Schema<?>, Unit> inlineUnits = new IdentityHashMap<>();
    private final Deque<Unit> queue = new ArrayDeque<>();
    private final Set<String> usedClassNames = new HashSet<>();
    private final Set<String> aliasGuard = new HashSet<>();
    private final Set<String> warned = new HashSet<>();
    private final Map<String, ClassDef> classDefs = new LinkedHashMap<>();
    private final Map<String, EnumDef> enumDefs = new LinkedHashMap<>();
    private final Map<String, String> reasons = new HashMap<>();
    private SecurityMapper sec;
    private String basePath = "";
    private int variantOps;
    private String variantExample = "";
    private String sourceUrl = "";

    public OpenApiMapper(OpenAPI api, GeneratorConfig cfg, Map<String, String> overrides) {
        this.api = api;
        this.cfg = cfg;
        this.overrides = overrides == null ? Map.of() : overrides;
    }

    /** Where the spec was loaded from; relative server URLs are resolved against it in PROXY mode. */
    public OpenApiMapper sourceUrl(String source) {
        this.sourceUrl = source == null ? "" : source.trim();
        return this;
    }

    // ------------------------------------------------------------------ entry point

    public ApiModel map() {
        out.title = api.getInfo() != null && api.getInfo().getTitle() != null ? api.getInfo().getTitle() : "API";
        out.version = api.getInfo() != null && api.getInfo().getVersion() != null ? api.getInfo().getVersion() : "1.0.0";
        out.projectVersion = out.version.matches("[A-Za-z0-9][A-Za-z0-9._-]*") ? out.version : "0.0.1-SNAPSHOT";
        out.description = api.getInfo() != null && api.getInfo().getDescription() != null ? api.getInfo().getDescription() : "";
        out.openapiVersion = api.getOpenapi() == null ? "" : api.getOpenapi();
        out.artifactId = !cfg.options.artifactId.isBlank() ? cfg.options.artifactId.trim() : Names.slug(out.title);
        out.applicationClass = Names.className(out.title) + "Application";
        if (Names.RESERVED_CLASS_NAMES.contains(out.applicationClass) || out.applicationClass.equals("Application"))
            out.applicationClass = "Application";
        basePath = computeBasePath();
        out.basePath = basePath;
        if (cfg.options.isProxy()) out.upstreamUrl = computeUpstreamUrl();
        sec = new SecurityMapper(api, cfg, out.messages);

        collectComponents();
        List<RawOp> raw = collectOperations();
        Map<String, List<RawOp>> groups = group(raw);
        Map<String, String> primaries = primaries(groups);
        Set<String> entities = classify(primaries);
        createComponentUnits(entities);
        drain();
        buildResources(groups, primaries);
        drain();

        for (ClassDef c : classDefs.values()) (c.kind.equals("ENTITY") ? out.entities : out.dtos).add(c);
        out.enums.addAll(enumDefs.values());
        out.security = sec.finish(out.resources);
        checkUnsupported();
        return out;
    }

    /** Exposed for the UI: how each component schema was classified. */
    public static Map<String, String> reasonsOf(ApiModel m) {
        Map<String, String> r = new LinkedHashMap<>();
        for (ClassDef c : m.entities) r.put(c.key, c.reason);
        for (ClassDef c : m.dtos) r.put(c.key, c.reason);
        return r;
    }

    private Schema<?> component(String name) {
        return name == null ? null : components.get(name);
    }

    // ------------------------------------------------------------------ components

    private void collectComponents() {
        if (api.getComponents() == null || api.getComponents().getSchemas() == null) return;
        for (Map.Entry<String, Schema> e : api.getComponents().getSchemas().entrySet()) components.put(e.getKey(), e.getValue());
        for (Map.Entry<String, Schema<?>> e : components.entrySet()) {
            Schema<?> s = e.getValue();
            if (s != null && !SchemaUtil.isEnum(s) && SchemaUtil.isObjectLike(s, resolver)) objectComponents.add(e.getKey());
        }
    }

    private void createComponentUnits(Set<String> entities) {
        for (Map.Entry<String, Schema<?>> e : components.entrySet()) {
            String n = e.getKey();
            Schema<?> s = e.getValue();
            if (s == null) continue;
            if (SchemaUtil.isEnum(s)) {
                newUnit(n, s, "ENUM", false, "enum");
            } else if (objectComponents.contains(n)) {
                newUnit(n, s, entities.contains(n) ? "ENTITY" : "DTO", false, reasons.getOrDefault(n, "plain data type"));
            }
        }
    }

    private Unit newUnit(String key, Schema<?> s, String kind, boolean synthetic, String reason) {
        Unit u = new Unit();
        u.key = key;
        u.schema = s;
        u.kind = kind;
        u.synthetic = synthetic;
        u.reason = reason;
        u.className = makeClassName(key, kind);
        units.put(key, u);
        queue.add(u);
        return u;
    }

    private String makeClassName(String key, String kind) {
        String pattern = switch (kind) {
            case "ENTITY" -> cfg.naming.entity;
            case "ENUM" -> cfg.naming.enumType;
            default -> cfg.naming.model;
        };
        String base = Names.className(key);
        String n = pattern.replace("{Name}", base);
        if (!Names.isJavaIdentifier(n)) n = base;
        if (Names.RESERVED_CLASS_NAMES.contains(n)) {
            warnOnce("Schema '" + key + "' would generate the class name '" + n + "', which clashes with a JDK/framework type; renamed to '" + n + "Model'.");
            n = n + "Model";
        }
        String cand = n;
        int i = 2;
        while (!usedClassNames.add(cand)) cand = n + i++;
        return cand;
    }

    private Unit inlineUnit(Schema<?> s, String suggested, String kind) {
        Unit u = inlineUnits.get(s);
        if (u != null) return u;
        String base = Names.className(suggested == null || suggested.isBlank() ? "Inline" : suggested);
        String key = base;
        int i = 2;
        while (units.containsKey(key) || components.containsKey(key)) key = base + i++;
        u = newUnit(key, s, kind, true, "inline " + (kind.equals("ENUM") ? "enum" : "object") + " (" + suggested + ")");
        inlineUnits.put(s, u);
        return u;
    }

    private void drain() {
        while (!queue.isEmpty()) {
            Unit u = queue.poll();
            if (u.kind.equals("ENUM")) buildEnum(u);
            else buildClass(u);
        }
    }

    // ------------------------------------------------------------------ entity classification

    private Map<String, String> primaries(Map<String, List<RawOp>> groups) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<RawOp>> g : groups.entrySet()) {
            Map<String, Integer> score = new LinkedHashMap<>();
            for (RawOp ro : g.getValue()) {
                RequestBody rb = deref(ro.op.getRequestBody());
                if (rb != null && rb.getContent() != null && !rb.getContent().isEmpty()) {
                    String ct = pickMedia(rb.getContent().keySet());
                    MediaType mt = ct == null ? null : rb.getContent().get(ct);
                    String n = topRef(mt == null ? null : mt.getSchema(), 0);
                    if (n != null) score.merge(n, 2, Integer::sum);
                }
                ApiResponse resp = successResponse(ro.op);
                if (resp != null && resp.getContent() != null && !resp.getContent().isEmpty()) {
                    String ct = pickMedia(resp.getContent().keySet());
                    MediaType mt = ct == null ? null : resp.getContent().get(ct);
                    String n = topRef(mt == null ? null : mt.getSchema(), 0);
                    if (n != null) score.merge(n, 1, Integer::sum);
                }
            }
            String best = null;
            int bestScore = -1;
            String groupNorm = norm(Names.singular(g.getKey()));
            for (Map.Entry<String, Integer> e : score.entrySet()) {
                if (EXCLUDED_PRIMARY.matcher(e.getKey()).matches()) continue;
                int sc = e.getValue() * 10 + (norm(Names.singular(e.getKey())).equals(groupNorm) ? 5 : 0);
                if (sc > bestScore) {
                    best = e.getKey();
                    bestScore = sc;
                }
            }
            if (best != null) result.put(g.getKey(), best);
        }
        return result;
    }

    private static final Pattern EXCLUDED_PRIMARY = Pattern.compile(
        "(?i)(error|errors|problem|problemdetail|exception|apiresponse|message|messages|pageable|page|paged|status|health|result|response)");

    /** Name of the object component behind a (possibly array-wrapped / aliased) schema, or null. */
    private String topRef(Schema<?> s, int depth) {
        if (s == null || depth > 8) return null;
        if (s.get$ref() != null) {
            String n = SchemaUtil.refName(s.get$ref());
            if (objectComponents.contains(n)) return n;
            Schema<?> c = components.get(n);
            if (c != null && "array".equals(SchemaUtil.typeOf(c))) return topRef(SchemaUtil.items(c), depth + 1);
            return null;
        }
        if ("array".equals(SchemaUtil.typeOf(s))) return topRef(SchemaUtil.items(s), depth + 1);
        return null;
    }

    private Set<String> classify(Map<String, String> primaries) {
        Set<String> entities = new LinkedHashSet<>();
        if (cfg.options.isProxy()) {
            // the data lives behind the upstream API: no entities, repositories or database
            for (String n : objectComponents) reasons.put(n, "plain data type (proxy mode reads data from the upstream API)");
            if (overrides.values().stream().anyMatch("ENTITY"::equalsIgnoreCase))
                warnOnce("Proxy mode generates no entities; your 'entity' overrides were ignored.");
            return entities;
        }
        if ("ALL_ENTITIES".equalsIgnoreCase(cfg.options.entityDetection)) {
            for (String n : objectComponents) {
                entities.add(n);
                reasons.put(n, "entity (all object schemas are entities)");
            }
        } else {
            for (Map.Entry<String, String> e : primaries.entrySet()) {
                if (objectComponents.contains(e.getValue()) && entities.add(e.getValue()))
                    reasons.put(e.getValue(), "main body type of resource '" + e.getKey() + "'");
            }
            boolean changed = true;
            while (changed) {
                changed = false;
                for (String en : new ArrayList<>(entities)) {
                    SchemaUtil.View v = SchemaUtil.view(components.get(en), resolver);
                    for (Map.Entry<String, Schema<?>> p : v.props.entrySet()) {
                        String r = relationRef(p.getValue(), 0);
                        if (r == null || entities.contains(r) || !objectComponents.contains(r)) continue;
                        SchemaUtil.View rv = SchemaUtil.view(components.get(r), resolver);
                        if (findIdProp(r, rv.props.keySet()) != null) {
                            entities.add(r);
                            reasons.put(r, "has an id and is referenced by entity " + en + "." + p.getKey());
                            changed = true;
                        }
                    }
                }
            }
        }
        for (String n : objectComponents) {
            if (!entities.contains(n)) {
                SchemaUtil.View v = SchemaUtil.view(components.get(n), resolver);
                reasons.putIfAbsent(n, findIdProp(n, v.props.keySet()) != null
                    ? "has an id but is not the main type of any resource or related to an entity"
                    : "no id property / not a resource body: plain DTO");
            }
        }
        // user overrides from the UI
        for (Map.Entry<String, String> o : overrides.entrySet()) {
            if (!objectComponents.contains(o.getKey())) continue;
            if ("ENTITY".equalsIgnoreCase(o.getValue())) {
                entities.add(o.getKey());
                reasons.put(o.getKey(), "forced to entity by you");
            } else if ("DTO".equalsIgnoreCase(o.getValue())) {
                entities.remove(o.getKey());
                reasons.put(o.getKey(), "forced to DTO by you");
            }
        }
        return entities;
    }

    private String relationRef(Schema<?> s, int depth) {
        if (s == null || depth > 6) return null;
        if (s.get$ref() != null) {
            String n = SchemaUtil.refName(s.get$ref());
            if (objectComponents.contains(n)) return n;
            Schema<?> c = components.get(n);
            if (c != null && "array".equals(SchemaUtil.typeOf(c))) return relationRef(SchemaUtil.items(c), depth + 1);
            return null;
        }
        if ("array".equals(SchemaUtil.typeOf(s))) return relationRef(SchemaUtil.items(s), depth + 1);
        return null;
    }

    private String findIdProp(String schemaName, Collection<String> props) {
        String[] wanted = {"id", norm(schemaName) + "id", "uuid"};
        for (String w : wanted)
            for (String p : props) if (norm(p).equals(w)) return p;
        return null;
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    // ------------------------------------------------------------------ building classes & enums

    private void buildEnum(Unit u) {
        EnumDef d = new EnumDef();
        d.key = u.key;
        d.className = u.className;
        d.synthetic = u.synthetic;
        d.description = u.schema.getDescription() == null ? "" : u.schema.getDescription();
        String t = SchemaUtil.typeOf(u.schema);
        d.valueType = "integer".equals(t) ? ("int64".equals(u.schema.getFormat()) ? "Long" : "Integer")
            : "number".equals(t) ? "Double" : "String";
        Set<String> used = new HashSet<>();
        for (Object raw : u.schema.getEnum()) {
            if (raw == null) continue;
            String s = String.valueOf(raw);
            EnumConst c = new EnumConst();
            c.raw = s;
            c.name = constName(s, used);
            switch (d.valueType) {
                case "Integer" -> c.literal = s;
                case "Long" -> c.literal = s + "L";
                case "Double" -> c.literal = (s.contains(".") || s.toLowerCase(Locale.ROOT).contains("e")) ? s : s + ".0";
                default -> c.literal = "\"" + Names.escapeJava(s) + "\"";
            }
            if (!d.valueType.equals("String") && !s.matches("-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?")) {
                d.valueType = "String";
                for (EnumConst prev : d.values) prev.literal = "\"" + Names.escapeJava(prev.raw) + "\"";
                c.literal = "\"" + Names.escapeJava(s) + "\"";
            }
            d.values.add(c);
        }
        if (d.values.isEmpty()) {
            EnumConst c = new EnumConst();
            c.name = "UNKNOWN";
            c.raw = "unknown";
            c.literal = "\"unknown\"";
            d.values.add(c);
            warnOnce("Enum '" + u.key + "' has no usable values.");
        }
        enumDefs.put(u.key, d);
    }

    private String constName(String raw, Set<String> used) {
        String base;
        if (raw.matches("-[0-9.]+")) base = "MINUS_" + Names.upperSnake(raw.substring(1));
        else base = Names.upperSnake(raw);
        if (base.isEmpty()) base = raw.isEmpty() ? "EMPTY" : "VALUE";
        if (Character.isDigit(base.charAt(0))) base = "VALUE_" + base;
        if (Names.JAVA_KEYWORDS.contains(base.toLowerCase(Locale.ROOT))) base = base + "_";
        String c = base;
        int i = 2;
        while (!used.add(c)) c = base + "_" + i++;
        return c;
    }

    private void buildClass(Unit u) {
        ClassDef c = new ClassDef();
        c.key = u.key;
        c.className = u.className;
        c.kind = u.kind;
        c.synthetic = u.synthetic;
        c.reason = u.reason;
        SchemaUtil.View v = SchemaUtil.view(u.schema, resolver);
        c.description = v.description;
        if (v.unionUsed)
            warnOnce("'" + u.key + "' uses oneOf/anyOf: it is generated as one class holding the union of all alternatives' properties (all optional).");
        if (v.discriminator)
            warnOnce("The discriminator on '" + u.key + "' is not generated; add @JsonTypeInfo/@JsonSubTypes by hand if you need polymorphic (de)serialisation.");
        Set<String> usedNames = new HashSet<>();
        for (Map.Entry<String, Schema<?>> p : v.props.entrySet()) {
            Schema<?> ps = p.getValue();
            Schema<?> pd = SchemaUtil.deref(ps, resolver);
            if (pd == null) pd = ps;
            FieldDef f = new FieldDef();
            f.jsonName = p.getKey();
            String base = Names.javaIdent(p.getKey());
            String n = base;
            int i = 2;
            while (!usedNames.add(n)) n = base + i++;
            f.name = n;
            f.cap = Names.cap(n);
            f.jsonNameDiffers = !n.equals(p.getKey());
            f.description = pd.getDescription() == null ? "" : pd.getDescription();
            f.required = v.required.contains(p.getKey());
            f.readOnly = Boolean.TRUE.equals(pd.getReadOnly());
            f.writeOnly = Boolean.TRUE.equals(pd.getWriteOnly());
            f.deprecated = Boolean.TRUE.equals(pd.getDeprecated());
            f.nullable = Boolean.TRUE.equals(pd.getNullable());
            f.type = resolve(ps, u.key + Names.className(p.getKey()), 0);
            f.column = Names.snake(n);
            if (cfg.options.validation) f.validations.addAll(validations(pd, f.type, f.required && !f.readOnly, false));
            if ("String".equals(f.type.java) && pd.getMaxLength() != null && pd.getMaxLength() > 255) f.length = pd.getMaxLength();
            f.lob = "BINARY".equals(f.type.kind);
            f.enumType = "ENUM".equals(f.type.kind);
            c.fields.add(f);
        }
        if (u.kind.equals("ENTITY")) finishEntity(c, v, u);
        classDefs.put(u.key, c);
    }

    private void finishEntity(ClassDef c, SchemaUtil.View v, Unit u) {
        String idProp = findIdProp(u.key, v.props.keySet());
        FieldDef idf = null;
        if (idProp != null) {
            for (FieldDef f : c.fields) if (f.jsonName.equals(idProp)) idf = f;
            if (idf != null && !(idf.type.kind.equals("SCALAR") && Set.of("Long", "Integer", "Short", "String", "UUID").contains(idf.type.java))) {
                warnOnce("Entity '" + u.key + "': property '" + idProp + "' is not a usable id type (" + idf.type.java + "); a synthetic 'id' column was added.");
                idf = null;
            }
        }
        if (idf == null) {
            Set<String> names = new HashSet<>();
            for (FieldDef f : c.fields) names.add(f.name);
            String n = names.contains("id") ? "entityId" : "id";
            idf = new FieldDef();
            idf.name = n;
            idf.jsonName = n;
            idf.cap = Names.cap(n);
            idf.column = Names.snake(n);
            idf.type = TypeRef.scalar("Long");
            idf.synthetic = true;
            idf.description = "Surrogate key added by the generator.";
            c.fields.add(0, idf);
        }
        idf.id = true;
        idf.required = false;
        idf.validations.remove("@NotNull");
        c.idField = idf;
        String t = idf.type.java;
        c.idGeneration = (t.equals("Long") || t.equals("Integer") || t.equals("Short")) ? "IDENTITY" : t.equals("UUID") ? "UUID" : "NONE";
        c.table = tableName(u.key);
        for (FieldDef f : c.fields) {
            if (f.id) {
                f.persist = "BASIC";
                continue;
            }
            f.persist = persistKind(f);
            if (f.persist.equals("ELEMENT_COLLECTION")) f.enumElement = f.type.element != null && f.type.element.kind.equals("ENUM");
        }
    }

    private String persistKind(FieldDef f) {
        TypeRef t = f.type;
        switch (t.kind) {
            case "SCALAR", "ENUM", "BINARY":
                return "BASIC";
            case "ENTITY":
                return "MANY_TO_ONE";
            case "LIST", "SET":
                if (t.element != null) {
                    if (t.element.kind.equals("SCALAR") || t.element.kind.equals("ENUM")) return "ELEMENT_COLLECTION";
                    if (t.element.kind.equals("ENTITY")) return "MANY_TO_MANY";
                }
                return "JSON";
            default:
                return "JSON";
        }
    }

    private String tableName(String key) {
        String base = switch (cfg.naming.tableStyle.toUpperCase(Locale.ROOT)) {
            case "AS_IS" -> key;
            case "SNAKE" -> Names.snake(key);
            default -> Names.plural(Names.snake(key));
        };
        return (cfg.naming.tablePrefix == null ? "" : cfg.naming.tablePrefix) + base;
    }

    // ------------------------------------------------------------------ type resolution

    private TypeRef resolve(Schema<?> s, String suggested, int depth) {
        if (s == null || depth > 30) return objectType();
        String ref = s.get$ref();
        if (ref != null) {
            String n = SchemaUtil.refName(ref);
            Unit u = units.get(n);
            if (u != null) return unitType(u);
            Schema<?> c = components.get(n);
            if (c == null) {
                warnOnce("Unresolved reference '" + ref + "' was modelled as Object.");
                return objectType();
            }
            if (!aliasGuard.add(n)) return objectType();
            try {
                return resolve(c, n, depth + 1);
            } finally {
                aliasGuard.remove(n);
            }
        }
        if (SchemaUtil.isEnum(s)) return unitType(inlineUnit(s, suggested, "ENUM"));

        boolean noProps = s.getProperties() == null || s.getProperties().isEmpty();
        if (SchemaUtil.hasComposition(s) && noProps) {
            List<Schema> all = SchemaUtil.allOf(s);
            List<Schema> one = nonNull(SchemaUtil.oneOf(s));
            List<Schema> any = nonNull(SchemaUtil.anyOf(s));
            if (all.isEmpty()) {
                List<Schema> alts = !one.isEmpty() ? one : any;
                if (alts.size() == 1) return resolve(alts.get(0), suggested, depth + 1);
            }
            if (all.size() == 1 && one.isEmpty() && any.isEmpty()) return resolve(all.get(0), suggested, depth + 1);
            if (SchemaUtil.isObjectLike(s, resolver)) return unitType(inlineUnit(s, suggested, "DTO"));
            warnOnce("A oneOf/anyOf of non-object alternatives near '" + suggested + "' was modelled as Object.");
            return objectType();
        }

        String type = SchemaUtil.typeOf(s);
        String fmt = s.getFormat();
        if (type == null) return objectType();
        switch (type) {
            case "array": {
                TypeRef el = resolve(SchemaUtil.items(s), Names.singular(suggested), depth + 1);
                boolean set = Boolean.TRUE.equals(s.getUniqueItems());
                TypeRef t = new TypeRef((set ? "Set<" : "List<") + el.java + ">", set ? "SET" : "LIST");
                t.element = el;
                t.imports.add(set ? "java.util.Set" : "java.util.List");
                return t;
            }
            case "object": {
                if (!noProps) return unitType(inlineUnit(s, suggested, "DTO"));
                TypeRef val = objectType();
                Object ap = s.getAdditionalProperties();
                if (ap instanceof Schema<?> as) val = resolve(as, suggested + "Value", depth + 1);
                TypeRef t = new TypeRef("Map<String, " + val.java + ">", "MAP");
                t.element = val;
                t.imports.add("java.util.Map");
                return t;
            }
            case "string":
                return stringFormat(fmt);
            case "integer":
                return "int64".equals(fmt) ? TypeRef.scalar("Long") : TypeRef.scalar("Integer");
            case "number":
                if ("float".equals(fmt)) return TypeRef.scalar("Float");
                if ("double".equals(fmt)) return TypeRef.scalar("Double");
                return TypeRef.scalar("BigDecimal", "java.math.BigDecimal");
            case "boolean":
                return TypeRef.scalar("Boolean");
            default:
                warnOnce("Unsupported schema type '" + type + "' was modelled as Object.");
                return objectType();
        }
    }

    private TypeRef stringFormat(String fmt) {
        if (fmt == null) return stringType();
        switch (fmt) {
            case "date":
                return TypeRef.scalar("LocalDate", "java.time.LocalDate");
            case "date-time": {
                String t = cfg.options.dateTimeType;
                return TypeRef.scalar(t, "java.time." + t);
            }
            case "time":
                return TypeRef.scalar("LocalTime", "java.time.LocalTime");
            case "uuid":
                return TypeRef.scalar("UUID", "java.util.UUID");
            case "byte":
            case "binary":
                return new TypeRef("byte[]", "BINARY");
            default:
                return stringType();
        }
    }

    private static TypeRef stringType() {
        return TypeRef.scalar("String");
    }

    private static TypeRef objectType() {
        return new TypeRef("Object", "OBJECT");
    }

    private static TypeRef unitType(Unit u) {
        TypeRef t = new TypeRef(u.className, u.kind);
        t.ref = u.key;
        return t;
    }

    private static List<Schema> nonNull(List<Schema> in) {
        List<Schema> l = new ArrayList<>();
        for (Schema s : in) if (!SchemaUtil.isNullSchema(s)) l.add(s);
        return l;
    }

    // ------------------------------------------------------------------ validation annotations

    private List<String> validations(Schema<?> pd, TypeRef t, boolean required, boolean isParam) {
        List<String> v = new ArrayList<>();
        if (!cfg.options.validation) return v;
        if (required && !isParam) v.add("@NotNull");
        String type = SchemaUtil.typeOf(pd);
        if (t.kind.equals("SCALAR") && "string".equals(type)) {
            Integer min = pd.getMinLength();
            Integer max = pd.getMaxLength();
            if ((min != null && min > 0) || max != null) {
                StringJoiner j = new StringJoiner(", ");
                if (min != null && min > 0) j.add("min = " + min);
                if (max != null) j.add("max = " + max);
                v.add("@Size(" + j + ")");
            }
            if (pd.getPattern() != null) {
                try {
                    Pattern.compile(pd.getPattern());
                    v.add("@Pattern(regexp = \"" + Names.escapeJava(pd.getPattern()) + "\")");
                } catch (Exception e) {
                    warnOnce("Pattern '" + pd.getPattern() + "' is not a valid Java regex and was skipped.");
                }
            }
            if ("email".equals(pd.getFormat())) v.add("@Email");
        } else if (t.kind.equals("SCALAR") && ("integer".equals(type) || "number".equals(type))) {
            BigDecimal min = pd.getMinimum();
            BigDecimal max = pd.getMaximum();
            boolean minEx = Boolean.TRUE.equals(pd.getExclusiveMinimum());
            boolean maxEx = Boolean.TRUE.equals(pd.getExclusiveMaximum());
            if (pd.getExclusiveMinimumValue() != null) {
                min = pd.getExclusiveMinimumValue();
                minEx = true;
            }
            if (pd.getExclusiveMaximumValue() != null) {
                max = pd.getExclusiveMaximumValue();
                maxEx = true;
            }
            boolean integral = t.java.equals("Integer") || t.java.equals("Long") || t.java.equals("Short");
            if (min != null) v.add(bound("Min", "DecimalMin", min, minEx, integral));
            if (max != null) v.add(bound("Max", "DecimalMax", max, maxEx, integral));
        } else if (t.isCollection()) {
            Integer min = pd.getMinItems();
            Integer max = pd.getMaxItems();
            if ((min != null && min > 0) || max != null) {
                StringJoiner j = new StringJoiner(", ");
                if (min != null && min > 0) j.add("min = " + min);
                if (max != null) j.add("max = " + max);
                v.add("@Size(" + j + ")");
            }
        }
        if (!isParam && t.needsValid()) v.add("@Valid");
        return v;
    }

    private static String bound(String intAnn, String decAnn, BigDecimal b, boolean exclusive, boolean integral) {
        boolean whole = b.stripTrailingZeros().scale() <= 0 && b.abs().compareTo(new BigDecimal("9000000000000000000")) < 0;
        if (integral && whole && !exclusive) return "@" + intAnn + "(" + b.toBigInteger() + "L)";
        return "@" + decAnn + "(value = \"" + b.toPlainString() + "\"" + (exclusive ? ", inclusive = false" : "") + ")";
    }

    // ------------------------------------------------------------------ operations

    private List<RawOp> collectOperations() {
        List<RawOp> list = new ArrayList<>();
        if (api.getPaths() == null) {
            out.messages.add(new Msg("WARN", "The spec defines no paths; only data classes are generated."));
            return list;
        }
        for (Map.Entry<String, PathItem> e : api.getPaths().entrySet()) {
            PathItem item = e.getValue();
            if (item == null) continue;
            for (Map.Entry<PathItem.HttpMethod, Operation> o : item.readOperationsMap().entrySet()) {
                PathItem.HttpMethod m = o.getKey();
                if (m == PathItem.HttpMethod.TRACE) {
                    warnOnce("Skipped TRACE " + e.getKey() + ": Spring MVC does not dispatch TRACE requests to controllers.");
                    continue;
                }
                RawOp ro = new RawOp();
                ro.path = e.getKey().startsWith("/") ? e.getKey() : "/" + e.getKey();
                ro.httpMethod = m.name();
                ro.op = o.getValue();
                ro.pathLevel = item.getParameters() == null ? List.of() : item.getParameters();
                list.add(ro);
            }
        }
        if (list.isEmpty()) out.messages.add(new Msg("WARN", "The spec defines no operations; only data classes are generated."));
        return list;
    }

    private Map<String, List<RawOp>> group(List<RawOp> raw) {
        Map<String, List<RawOp>> g = new LinkedHashMap<>();
        for (RawOp ro : raw) {
            String name = null;
            if ("TAG".equalsIgnoreCase(cfg.naming.resourceGrouping) && ro.op.getTags() != null && !ro.op.getTags().isEmpty())
                name = Names.className(ro.op.getTags().get(0));
            if (name == null || name.isEmpty()) {
                name = "Default";
                for (String seg : ro.path.split("/")) {
                    if (seg.isEmpty() || seg.startsWith("{")) continue;
                    name = Names.className(seg);
                    break;
                }
            }
            ro.group = name;
            g.computeIfAbsent(name, k -> new ArrayList<>()).add(ro);
        }
        return g;
    }

    private void buildResources(Map<String, List<RawOp>> groups, Map<String, String> primaries) {
        for (Map.Entry<String, List<RawOp>> g : groups.entrySet()) {
            ResourceModel r = new ResourceModel();
            r.name = g.getKey();
            r.basePath = basePath;
            String ek = primaries.get(g.getKey());
            if (ek != null && classDefs.containsKey(ek) && classDefs.get(ek).kind.equals("ENTITY")) {
                r.entityKey = ek;
                r.entity = classDefs.get(ek);
                r.entityClass = r.entity.className;
                r.idType = r.entity.idField.type.java;
            }
            if (api.getTags() != null) {
                for (Tag t : api.getTags()) {
                    if (t.getName() != null && Names.className(t.getName()).equals(g.getKey())) {
                        r.tag = t.getName();
                        r.description = t.getDescription() == null ? "" : t.getDescription();
                    }
                }
            }
            Set<String> usedMethods = new HashSet<>();
            for (RawOp ro : g.getValue()) r.operations.addAll(buildOperations(r, ro, usedMethods));
            out.resources.add(r);
        }
        if (variantOps > 0)
            out.messages.add(new Msg("INFO", variantOps + " operation(s) accept request media types that bind differently; "
                + "one endpoint method is generated per variant, e.g. " + variantExample + "."));
    }

    /** One endpoint method per request-body variant (see {@link #bodyVariants}); the preferred variant keeps the plain name. */
    private List<OperationModel> buildOperations(ResourceModel r, RawOp ro, Set<String> usedMethods) {
        List<List<String>> variants = bodyVariants(ro.op);
        if (variants.isEmpty()) return List.of(buildOperation(r, ro, usedMethods, List.of(), ""));
        List<OperationModel> ops = new ArrayList<>();
        for (List<String> media : variants)
            ops.add(buildOperation(r, ro, usedMethods, media, ops.isEmpty() ? "" : mediaSuffix(media.get(0))));
        if (ops.size() > 1 && variantExample.isEmpty()) {
            StringJoiner names = new StringJoiner(", ");
            for (OperationModel o : ops) names.add(o.method + " (" + String.join(", ", o.consumes) + ")");
            variantExample = names.toString();
        }
        if (ops.size() > 1) variantOps++;
        return ops;
    }

    private OperationModel buildOperation(ResourceModel r, RawOp ro, Set<String> usedMethods, List<String> media, String suffix) {
        Operation op = ro.op;
        OperationModel o = new OperationModel();
        o.operationId = op.getOperationId() == null ? "" : op.getOperationId();
        o.httpMethod = ro.httpMethod;
        o.mapping = switch (ro.httpMethod) {
            case "POST" -> "PostMapping";
            case "PUT" -> "PutMapping";
            case "PATCH" -> "PatchMapping";
            case "DELETE" -> "DeleteMapping";
            case "HEAD", "OPTIONS" -> "RequestMapping";
            default -> "GetMapping";
        };
        if (o.mapping.equals("RequestMapping")) o.requestMethod = ro.httpMethod;
        o.path = ro.path;
        o.fullPath = joinPath(basePath, ro.path);
        o.summary = op.getSummary() == null ? "" : op.getSummary();
        o.description = op.getDescription() == null ? "" : op.getDescription();
        o.deprecated = Boolean.TRUE.equals(op.getDeprecated());

        String base = (!o.operationId.isEmpty() ? Names.javaIdent(o.operationId)
            : Names.javaIdent(ro.httpMethod.toLowerCase(Locale.ROOT) + " " + pathWords(ro.path))) + suffix;
        String name = base;
        int i = 2;
        while (!usedMethods.add(name)) name = base + i++;
        o.method = name;
        String methodPascal = Names.cap(name);

        if (op.getCallbacks() != null && !op.getCallbacks().isEmpty())
            warnOnce("Callbacks on '" + name + "' are not generated.");

        buildParameters(o, ro, methodPascal);
        buildBody(o, op, methodPascal, media);
        buildResponse(o, op, methodPascal);
        o.access = sec.expressionFor(op);
        o.publicAccess = o.access.isEmpty() || SecurityMapper.PUBLIC.equals(o.access);
        // forwarded credentials are validated (scopes included) by the upstream API; here we only require one to be present
        if (sec.proxyForward && !o.publicAccess) o.access = "isAuthenticated()";
        classifyOp(r, o);
        return o;
    }

    private void buildParameters(OperationModel o, RawOp ro, String methodPascal) {
        LinkedHashMap<String, Parameter> merged = new LinkedHashMap<>();
        for (Parameter p : ro.pathLevel) {
            Parameter d = deref(p);
            if (d != null && d.getName() != null) merged.put(d.getIn() + ":" + d.getName(), d);
        }
        if (ro.op.getParameters() != null) {
            for (Parameter p : ro.op.getParameters()) {
                Parameter d = deref(p);
                if (d != null && d.getName() != null) merged.put(d.getIn() + ":" + d.getName(), d);
            }
        }
        List<ParamDef> path = new ArrayList<>();
        List<ParamDef> query = new ArrayList<>();
        List<ParamDef> header = new ArrayList<>();
        List<ParamDef> cookie = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        Set<String> declaredPath = new HashSet<>();
        for (Parameter p : merged.values()) {
            String in = p.getIn() == null ? "query" : p.getIn().toLowerCase(Locale.ROOT);
            if (!Set.of("path", "query", "header", "cookie").contains(in)) {
                warnOnce("Parameter '" + p.getName() + "' of '" + o.method + "' has unsupported location '" + in + "' and was skipped.");
                continue;
            }
            if (in.equals("header") && Set.of("accept", "content-type", "authorization").contains(p.getName().toLowerCase(Locale.ROOT)))
                continue; // handled by the framework / security config
            ParamDef d = new ParamDef();
            d.name = p.getName();
            d.in = in;
            d.javaName = uniqueName(Names.javaIdent(p.getName()), usedNames);
            d.description = p.getDescription() == null ? "" : p.getDescription();
            d.required = in.equals("path") || Boolean.TRUE.equals(p.getRequired());
            Schema<?> sch = p.getSchema();
            if (sch == null && p.getContent() != null && !p.getContent().isEmpty()) {
                MediaType mt = p.getContent().values().iterator().next();
                sch = mt == null ? null : mt.getSchema();
            }
            Schema<?> sd = SchemaUtil.deref(sch, resolver);
            d.type = paramType(sch, methodPascal + Names.className(p.getName()));
            if (sd != null) {
                if (sd.getDefault() != null) d.defaultValue = String.valueOf(sd.getDefault());
                if (SchemaUtil.isEnum(sd)) d.allowedValues = joinEnum(sd);
                else if ("array".equals(SchemaUtil.typeOf(sd))) {
                    Schema<?> it = SchemaUtil.deref(SchemaUtil.items(sd), resolver);
                    if (it != null && SchemaUtil.isEnum(it)) d.allowedValues = joinEnum(it);
                }
                if (cfg.options.validation) d.validations.addAll(validations(sd, d.type, false, true));
            }
            if (!d.allowedValues.isEmpty() && cfg.options.validation && d.type.kind.equals("SCALAR")) {
                StringJoiner alt = new StringJoiner("|");
                for (String v : d.allowedValues.split(", ")) alt.add(v.replaceAll("[\\\\.^$|?*+()\\[\\]{}]", "\\\\$0"));
                d.validations.add("@Pattern(regexp = \"" + Names.escapeJava(alt.toString()) + "\")");
            }
            switch (in) {
                case "path" -> {
                    path.add(d);
                    declaredPath.add(p.getName());
                }
                case "query" -> query.add(d);
                case "header" -> header.add(d);
                default -> cookie.add(d);
            }
        }
        // path variables used in the URL but not declared as parameters
        Matcher m = Pattern.compile("\\{([^/{}]+)}").matcher(ro.path);
        List<ParamDef> orderedPath = new ArrayList<>();
        while (m.find()) {
            String pn = m.group(1);
            ParamDef found = null;
            for (ParamDef d : path) if (d.name.equals(pn)) found = d;
            if (found == null) {
                found = new ParamDef();
                found.name = pn;
                found.in = "path";
                found.javaName = uniqueName(Names.javaIdent(pn), usedNames);
                found.required = true;
                found.type = stringType();
                warnOnce("Path variable {" + pn + "} of '" + o.method + "' is not declared as a parameter; treated as String.");
            }
            orderedPath.add(found);
        }
        for (ParamDef d : path) if (!orderedPath.contains(d)) orderedPath.add(d);
        o.params.addAll(orderedPath);
        o.params.addAll(query);
        o.params.addAll(header);
        o.params.addAll(cookie);
    }

    private TypeRef paramType(Schema<?> s, String suggested) {
        Schema<?> d = SchemaUtil.deref(s, resolver);
        if (d == null) return stringType();
        if (SchemaUtil.isEnum(d)) return stringType();
        if ("array".equals(SchemaUtil.typeOf(d))) {
            TypeRef el = paramType(SchemaUtil.items(d), suggested);
            boolean set = Boolean.TRUE.equals(d.getUniqueItems());
            TypeRef t = new TypeRef((set ? "Set<" : "List<") + el.java + ">", set ? "SET" : "LIST");
            t.element = el;
            t.imports.add(set ? "java.util.Set" : "java.util.List");
            return t;
        }
        if ("object".equals(SchemaUtil.typeOf(d)) || SchemaUtil.isObjectLike(d, resolver)) {
            warnOnce("Object-typed parameter near '" + suggested + "' was mapped to String.");
            return stringType();
        }
        return resolve(s, suggested, 0);
    }

    private String joinEnum(Schema<?> s) {
        StringJoiner j = new StringJoiner(", ");
        for (Object v : s.getEnum()) if (v != null) j.add(String.valueOf(v));
        return j.toString();
    }

    /**
     * Groups the request media types by how Spring binds them (same family and, for structured/form bodies, same
     * schema). Each group becomes one endpoint method; the group holding the preferred media type comes first.
     */
    private List<List<String>> bodyVariants(Operation op) {
        RequestBody rb = deref(op.getRequestBody());
        if (rb == null || rb.getContent() == null || rb.getContent().isEmpty()) return List.of();
        Content content = rb.getContent();
        String preferred = pickMedia(content.keySet());
        LinkedHashMap<String, List<String>> groups = new LinkedHashMap<>();
        groups.put(bindingKey(preferred, content.get(preferred)), new ArrayList<>(List.of(preferred)));
        for (String ct : content.keySet()) {
            if (ct.equals(preferred)) continue;
            groups.computeIfAbsent(bindingKey(ct, content.get(ct)), k -> new ArrayList<>()).add(ct);
        }
        return new ArrayList<>(groups.values());
    }

    private static String bindingKey(String ct, MediaType mt) {
        String family = mediaFamily(ct);
        if (family.equals("TEXT") || family.equals("RAW")) return family;
        Schema<?> s = mt == null ? null : mt.getSchema();
        return family + "|" + (s == null ? "-" : s.get$ref() != null ? s.get$ref() : String.valueOf(s.hashCode()));
    }

    /** MULTIPART | FORM | TEXT (read as String) | STRUCTURED (JSON/XML, read by Jackson) | RAW (read as byte[]) */
    private static String mediaFamily(String ct) {
        String lc = ct.toLowerCase(Locale.ROOT);
        if (lc.startsWith("multipart/")) return "MULTIPART";
        if (lc.contains("x-www-form-urlencoded")) return "FORM";
        if (lc.startsWith("text/") || lc.contains("yaml")) return "TEXT";
        if (lc.contains("json") || lc.contains("xml") || lc.contains("*")) return "STRUCTURED";
        return "RAW";
    }

    /** "application/apply-patch+yaml" -> "ApplyPatchYaml", "text/plain" -> "TextPlain" */
    private static String mediaSuffix(String ct) {
        String lc = ct.split(";")[0].trim().toLowerCase(Locale.ROOT);
        String words = lc.startsWith("application/") ? lc.substring("application/".length()) : lc.replace('/', ' ');
        String s = Names.pascal(words.replace('*', ' '));
        return s.isEmpty() ? "Any" : s;
    }

    private void buildBody(OperationModel o, Operation op, String methodPascal, List<String> media) {
        RequestBody rb = deref(op.getRequestBody());
        if (rb == null || rb.getContent() == null || media.isEmpty()) return;
        Content content = rb.getContent();
        String ct = media.get(0);
        MediaType mt = content.get(ct);
        Schema<?> sch = mt == null ? null : mt.getSchema();
        String lc = ct.toLowerCase(Locale.ROOT);
        String family = mediaFamily(ct);
        boolean required = rb.getRequired() == null || rb.getRequired();
        Set<String> usedNames = new HashSet<>();
        for (ParamDef p : o.params) usedNames.add(p.javaName);
        o.consumes.addAll(media);
        if (media.size() > 1) {
            // one method serves several media types (e.g. JSON Patch and Merge Patch): let the implementation tell them apart
            ParamDef h = new ParamDef();
            h.name = "Content-Type";
            h.in = "header";
            h.javaName = uniqueName("contentType", usedNames);
            h.required = true;
            h.type = stringType();
            h.description = "media type of the request body";
            h.allowedValues = String.join(", ", media);
            h.contentTypeSelector = true;
            o.params.add(h);
        }
        if (lc.startsWith("multipart/") || lc.contains("x-www-form-urlencoded")) {
            boolean multipart = lc.startsWith("multipart/");
            Schema<?> d = SchemaUtil.deref(sch, resolver);
            if (d == null) return;
            SchemaUtil.View v = SchemaUtil.view(d, resolver);
            for (Map.Entry<String, Schema<?>> e : v.props.entrySet()) {
                Schema<?> pd = SchemaUtil.deref(e.getValue(), resolver);
                ParamDef p = new ParamDef();
                p.name = e.getKey();
                p.javaName = uniqueName(Names.javaIdent(e.getKey()), usedNames);
                p.required = v.required.contains(e.getKey());
                p.description = pd == null || pd.getDescription() == null ? "" : pd.getDescription();
                boolean binary = isBinary(pd);
                boolean binaryList = pd != null && "array".equals(SchemaUtil.typeOf(pd)) && isBinary(SchemaUtil.deref(SchemaUtil.items(pd), resolver));
                if (binary) {
                    p.in = "part";
                    p.file = true;
                    p.type = new TypeRef("MultipartFile", "FILE");
                    p.type.imports.add("org.springframework.web.multipart.MultipartFile");
                } else if (binaryList) {
                    p.in = "part";
                    p.file = true;
                    TypeRef el = new TypeRef("MultipartFile", "FILE");
                    el.imports.add("org.springframework.web.multipart.MultipartFile");
                    p.type = new TypeRef("List<MultipartFile>", "LIST");
                    p.type.element = el;
                    p.type.imports.add("java.util.List");
                } else if (multipart && SchemaUtil.isObjectLike(pd, resolver)) {
                    p.in = "part";
                    p.type = resolve(e.getValue(), methodPascal + Names.className(e.getKey()), 0);
                } else {
                    p.in = "form";
                    p.type = paramType(e.getValue(), methodPascal + Names.className(e.getKey()));
                    if (pd != null && pd.getDefault() != null) p.defaultValue = String.valueOf(pd.getDefault());
                }
                o.params.add(p);
            }
            return;
        }
        ParamDef b = new ParamDef();
        b.name = "body";
        b.javaName = uniqueName("body", usedNames);
        b.in = "body";
        b.required = required;
        b.description = rb.getDescription() == null ? "" : rb.getDescription();
        Schema<?> d = SchemaUtil.deref(sch, resolver);
        if (family.equals("TEXT")) {
            b.type = stringType();
        } else if (family.equals("RAW") || isBinary(d)) {
            b.type = new TypeRef("byte[]", "BINARY");
        } else if (sch == null) {
            b.type = lc.contains("json") ? objectType() : new TypeRef("byte[]", "BINARY");
        } else {
            b.type = resolve(sch, methodPascal + "Request", 0);
            boolean jsonPatch = media.stream().anyMatch(m -> m.toLowerCase(Locale.ROOT).contains("json-patch"));
            if (jsonPatch && b.type.kind.equals("MAP") && b.type.element != null && b.type.element.kind.equals("OBJECT")) {
                // a JSON Patch document (RFC 6902) is an array, so a free-form object schema must accept arrays too
                b.type = objectType();
            } else if (cfg.options.validation) {
                b.validations.addAll(validations(d == null ? sch : d, b.type, false, false));
            }
        }
        if (media.stream().anyMatch(m -> m.toLowerCase(Locale.ROOT).contains("xml"))) warnOnce("'" + o.method + "' uses an XML request body; add jackson-dataformat-xml to your project for it to work.");
        o.body = b;
        o.params.add(b);
    }

    private static boolean isBinary(Schema<?> s) {
        return s != null && "string".equals(SchemaUtil.typeOf(s)) && ("binary".equals(s.getFormat()) || "byte".equals(s.getFormat()));
    }

    private ApiResponse successResponse(Operation op) {
        ApiResponses rs = op.getResponses();
        String code = pickSuccess(rs);
        if (code == null) return null;
        return deref(rs.get(code));
    }

    private String pickSuccess(ApiResponses rs) {
        if (rs == null || rs.isEmpty()) return null;
        String best = null;
        int bestNum = Integer.MAX_VALUE;
        boolean bestHasContent = false;
        for (Map.Entry<String, ApiResponse> e : rs.entrySet()) {
            String k = e.getKey();
            if (!k.matches("2\\d\\d") && !k.equalsIgnoreCase("2XX")) continue;
            int n = k.matches("2\\d\\d") ? Integer.parseInt(k) : 200;
            ApiResponse r = deref(e.getValue());
            boolean hc = r != null && r.getContent() != null && !r.getContent().isEmpty();
            if (best == null || (hc && !bestHasContent) || (hc == bestHasContent && n < bestNum)) {
                best = k;
                bestNum = n;
                bestHasContent = hc;
            }
        }
        if (best == null && rs.containsKey("default")) best = "default";
        return best;
    }

    private void buildResponse(OperationModel o, Operation op, String methodPascal) {
        ApiResponses rs = op.getResponses();
        if (rs != null) {
            for (Map.Entry<String, ApiResponse> e : rs.entrySet()) {
                ResponseDoc d = new ResponseDoc();
                d.code = e.getKey();
                ApiResponse r = deref(e.getValue());
                d.description = r == null || r.getDescription() == null ? "" : r.getDescription();
                o.responses.add(d);
            }
        }
        String code = pickSuccess(rs);
        ApiResponse resp = code == null ? null : deref(rs.get(code));
        o.successStatus = code != null && code.matches("2\\d\\d") ? Integer.parseInt(code) : 200;
        if (resp != null && resp.getLinks() != null && !resp.getLinks().isEmpty())
            warnOnce("Response links on '" + o.method + "' are not generated.");
        if (resp == null || resp.getContent() == null || resp.getContent().isEmpty()) {
            o.returnType = new TypeRef("void", "VOID");
            o.returnsVoid = true;
            return;
        }
        String ct = pickMedia(resp.getContent().keySet());
        MediaType mt = ct == null ? null : resp.getContent().get(ct);
        Schema<?> sch = mt == null ? null : mt.getSchema();
        Schema<?> d = SchemaUtil.deref(sch, resolver);
        o.produces.add(ct);
        if (sch == null) {
            o.returnType = ct.toLowerCase(Locale.ROOT).startsWith("text/") ? stringType() : objectType();
        } else if (isBinary(d)) {
            o.returnType = new TypeRef("byte[]", "BINARY");
        } else {
            o.returnType = resolve(sch, methodPascal + "Response", 0);
        }
        o.returnsVoid = false;
    }

    /** Preference: json > form > multipart > text > octet-stream > anything. */
    private String pickMedia(Set<String> types) {
        if (types == null || types.isEmpty()) return null;
        String[] prefs = {"application/json", "json", "x-www-form-urlencoded", "multipart/form-data", "text/plain", "octet-stream"};
        for (String p : prefs) for (String t : types) if (t.toLowerCase(Locale.ROOT).contains(p)) return t;
        return types.iterator().next();
    }

    // ------------------------------------------------------------------ CRUD classification

    private void classifyOp(ResourceModel r, OperationModel o) {
        ClassDef e = r.entity;
        if (e == null) return;
        for (ParamDef p : o.params) if (p.in.equals("form") || p.in.equals("part")) return;
        String ek = e.key;
        String[] segs = Arrays.stream(o.path.split("/")).filter(s -> !s.isEmpty()).toArray(String[]::new);
        int paramSegs = 0;
        for (String s : segs) if (s.startsWith("{")) paramSegs++;
        boolean lastParam = segs.length > 0 && segs[segs.length - 1].startsWith("{");
        boolean collection = !lastParam && paramSegs == 0;
        boolean item = lastParam && paramSegs == 1;
        TypeRef ret = o.returnType;
        boolean retEntity = ret.kind.equals("ENTITY") && ek.equals(ret.ref);
        boolean retList = ret.kind.equals("LIST") && ret.element != null && ret.element.kind.equals("ENTITY") && ek.equals(ret.element.ref);
        boolean bodyEntity = o.body != null && o.body.type.kind.equals("ENTITY") && ek.equals(o.body.type.ref);
        String kind = "CUSTOM";
        switch (o.httpMethod) {
            case "GET":
                if (collection && retList) kind = "LIST";
                else if (item && retEntity) kind = "GET";
                break;
            case "POST":
                if (collection && bodyEntity && (retEntity || o.returnsVoid)) kind = "CREATE";
                break;
            case "PUT":
            case "PATCH":
                if (item && bodyEntity && (retEntity || o.returnsVoid)) kind = "UPDATE";
                break;
            case "DELETE":
                if (item && o.returnsVoid && o.body == null) kind = "DELETE";
                break;
            default:
                break;
        }
        if (kind.equals("CUSTOM")) return;
        if (kind.equals("LIST") || kind.equals("CREATE")) {
            o.kind = kind;
            return;
        }
        // item operations need a way to find the row
        ParamDef pp = null;
        for (ParamDef p : o.params) if (p.in.equals("path")) pp = p;
        if (pp == null) return;
        String pn = norm(pp.name);
        FieldDef idf = e.idField;
        Set<String> idNames = new HashSet<>(List.of(norm(idf.jsonName), norm(idf.name), "id", norm(e.key) + "id"));
        FieldDef prop = null;
        String lk = "";
        if (idNames.contains(pn)) lk = "ID";
        else {
            for (FieldDef f : e.fields) if (!f.id && f.type.kind.equals("SCALAR") && norm(f.jsonName).equals(pn)) prop = f;
            if (prop != null) lk = "PROPERTY";
            else if (pn.endsWith("id")) lk = "ID";
        }
        if (lk.isEmpty()) return;
        TypeRef target = lk.equals("ID") ? idf.type : prop.type;
        String conv = convert(pp.javaName, pp.type, target);
        if (conv == null) return;
        o.kind = kind;
        o.lookupKind = lk;
        o.lookupParam = pp.javaName;
        o.lookupExpr = conv;
        if (lk.equals("PROPERTY")) {
            o.finderMethod = "findBy" + prop.cap;
            boolean have = false;
            for (Finder f : e.finders) if (f.field.equals(prop.name)) have = true;
            if (!have) {
                Finder f = new Finder();
                f.method = o.finderMethod;
                f.field = prop.name;
                f.cap = prop.cap;
                f.javaType = prop.type.java;
                e.finders.add(f);
            }
        }
    }

    /** Java expression converting {@code expr} from one scalar type to another, or null if unsupported. */
    static String convert(String expr, TypeRef from, TypeRef to) {
        if (!from.kind.equals("SCALAR") || !to.kind.equals("SCALAR")) return null;
        if (from.java.equals(to.java)) return expr;
        switch (to.java) {
            case "Long":
                if (from.java.equals("Integer") || from.java.equals("Short") || from.java.equals("String")) return "Long.valueOf(" + expr + ")";
                return null;
            case "Integer":
                if (from.java.equals("Long")) return "Math.toIntExact(" + expr + ")";
                if (from.java.equals("String")) return "Integer.valueOf(" + expr + ")";
                return null;
            case "String":
                return "String.valueOf(" + expr + ")";
            case "UUID":
                return from.java.equals("String") ? "UUID.fromString(" + expr + ")" : null;
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ misc helpers

    private Parameter deref(Parameter p) {
        int guard = 0;
        while (p != null && p.get$ref() != null && guard++ < 10) {
            Map<String, Parameter> m = api.getComponents() == null ? null : api.getComponents().getParameters();
            Parameter n = m == null ? null : m.get(SchemaUtil.refName(p.get$ref()));
            if (n == null) {
                warnOnce("Unresolved parameter reference " + p.get$ref() + " was skipped.");
                return null;
            }
            p = n;
        }
        return p;
    }

    private RequestBody deref(RequestBody rb) {
        int guard = 0;
        while (rb != null && rb.get$ref() != null && guard++ < 10) {
            Map<String, RequestBody> m = api.getComponents() == null ? null : api.getComponents().getRequestBodies();
            RequestBody n = m == null ? null : m.get(SchemaUtil.refName(rb.get$ref()));
            if (n == null) {
                warnOnce("Unresolved request body reference " + rb.get$ref() + " was skipped.");
                return null;
            }
            rb = n;
        }
        return rb;
    }

    private ApiResponse deref(ApiResponse r) {
        int guard = 0;
        while (r != null && r.get$ref() != null && guard++ < 10) {
            Map<String, ApiResponse> m = api.getComponents() == null ? null : api.getComponents().getResponses();
            ApiResponse n = m == null ? null : m.get(SchemaUtil.refName(r.get$ref()));
            if (n == null) return null;
            r = n;
        }
        return r;
    }

    private static String uniqueName(String base, Set<String> used) {
        String n = base;
        int i = 2;
        while (!used.add(n)) n = base + i++;
        return n;
    }

    private static String pathWords(String path) {
        StringBuilder sb = new StringBuilder();
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            if (seg.startsWith("{") && seg.endsWith("}")) sb.append(" by ").append(seg, 1, seg.length() - 1);
            else sb.append(' ').append(seg);
        }
        return sb.toString().trim();
    }

    private static String joinPath(String a, String b) {
        String x = (a == null ? "" : a) + (b.startsWith("/") ? b : "/" + b);
        return x.replaceAll("/{2,}", "/");
    }

    private String computeBasePath() {
        String bp = cfg.options.basePath == null ? "" : cfg.options.basePath.trim();
        if (bp.isEmpty()) bp = serverPath();
        return normalizeBase(bp);
    }

    private static String normalizeBase(String bp) {
        if (bp == null) return "";
        bp = bp.trim();
        if (bp.isEmpty() || bp.equals("/")) return "";
        if (!bp.startsWith("/")) bp = "/" + bp;
        while (bp.endsWith("/")) bp = bp.substring(0, bp.length() - 1);
        return bp;
    }

    /** servers[0].url with its variables replaced by their defaults; "" when absent, null when it cannot be resolved. */
    private String serverUrl() {
        List<Server> servers = api.getServers();
        if (servers == null || servers.isEmpty() || servers.get(0).getUrl() == null) return "";
        Server s = servers.get(0);
        String url = s.getUrl();
        if (s.getVariables() != null) {
            for (Map.Entry<String, ServerVariable> v : s.getVariables().entrySet()) {
                String def = v.getValue() == null || v.getValue().getDefault() == null ? "" : v.getValue().getDefault();
                url = url.replace("{" + v.getKey() + "}", def);
            }
        }
        if (url.contains("{")) {
            warnOnce("The server URL '" + s.getUrl() + "' contains undefined variables; no base path was derived.");
            return null;
        }
        return url;
    }

    /** For the UI: the upstream URL proxy mode would use with the current settings. */
    public String suggestedUpstreamUrl() {
        return computeUpstreamUrl();
    }

    /** Absolute upstream base URL for PROXY mode: the configured one, else the spec's server URL, resolved against the spec's own URL when relative. */
    private String computeUpstreamUrl() {
        String configured = cfg.options.upstreamUrl == null ? "" : cfg.options.upstreamUrl.trim();
        String url = !configured.isEmpty() ? configured : serverUrl();
        if (url == null) url = "";
        if (!url.matches("(?i)https?://.*")) {
            if (sourceUrl.matches("(?i)https?://.*")) {
                try {
                    url = java.net.URI.create(sourceUrl).resolve(url.isEmpty() ? "/" : url).toString();
                } catch (IllegalArgumentException e) {
                    url = "";
                }
            } else {
                warnOnce("The upstream API's address is unknown (the spec has no absolute server URL and was not loaded from a URL); "
                    + "set app.upstream.base-url / UPSTREAM_BASE_URL before running the generated app.");
                url = "http://localhost:8081" + (url.startsWith("/") ? url : "");
            }
        }
        int q = url.indexOf('?');
        if (q >= 0) url = url.substring(0, q);
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    private String serverPath() {
        String url = serverUrl();
        if (url == null || url.isEmpty()) return "";
        int scheme = url.indexOf("://");
        String path;
        if (scheme >= 0) {
            int slash = url.indexOf('/', scheme + 3);
            path = slash < 0 ? "" : url.substring(slash);
        } else if (url.startsWith("/")) {
            path = url;
        } else {
            path = "";
        }
        int q = path.indexOf('?');
        return q >= 0 ? path.substring(0, q) : path;
    }

    private void checkUnsupported() {
        try {
            if (api.getWebhooks() != null && !api.getWebhooks().isEmpty()) warnOnce("Webhooks are not generated.");
        } catch (Throwable ignored) {
            // older swagger-core without webhooks support
        }
    }

    private void warnOnce(String text) {
        if (warned.add(text)) out.messages.add(new Msg("WARN", text));
    }
}
