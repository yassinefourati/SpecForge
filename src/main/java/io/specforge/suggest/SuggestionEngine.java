package io.specforge.suggest;

import io.specforge.model.DbModel.DbEntity;
import io.specforge.model.DbModel.DbField;
import io.specforge.model.DbModel.DbId;
import io.specforge.model.DbModel.DbManyToMany;
import io.specforge.model.DbModel.DbRelation;
import io.specforge.plan.GenerationPlan;
import io.specforge.snapshot.Snapshot;
import io.specforge.util.Names;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Snapshot + the user's decisions → the effective entities (P1, PLAN-5). Everything here is a suggestion the plan
 * can override; the result is deterministic for a given {@link #RULESET_VERSION}, snapshot and plan.
 * <p>
 * Designed against the prior art's defects (§4.3): IDs come from detected identity / sequences, never from names
 * (MAP-7); a column named "timestamp" is just a column (MAP-2: no optimistic locking, soft delete or auditing is
 * applied in R1); vendor types are mapped first (MAP-6); names are inflected from the singular (MAP-10); only
 * many-to-one relations are generated, inverse collections are opt-in (MAP-4).
 */
public final class SuggestionEngine {

    public static final String RULESET_VERSION = "2026.10";
    public static final List<String> OPERATIONS = List.of("create", "read", "list", "update", "patch", "delete");

    /** Sequence references in column defaults: PostgreSQL / MariaDB nextval(...), SQL Server / H2 NEXT VALUE FOR, Oracle seq.NEXTVAL. */
    private static final List<Pattern> SEQUENCE_DEFAULTS = List.of(
        Pattern.compile("nextval\\(\\s*'?([^)']+?)'?(?:::regclass)?\\s*\\)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("next\\s+value\\s+for\\s+([\\w$#.\"`\\[\\]]+)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("((?:\"?[\\w$#]+\"?\\.)?\"?[\\w$#]+\"?)\\.\"?nextval\"?", Pattern.CASE_INSENSITIVE));
    private static final Set<String> FILTERABLE = Set.of("String", "Integer", "Long", "Short", "Boolean", "UUID", "LocalDate");
    private static final Set<String> SORTABLE = Set.of("String", "Integer", "Long", "Short", "Boolean", "UUID", "LocalDate",
        "LocalDateTime", "OffsetDateTime", "LocalTime", "BigDecimal", "Double", "Float");

    /** The outcome: entities to generate, tables represented as many-to-many, tables left out and why. */
    public static class Result {
        public List<DbEntity> entities = new ArrayList<>();
        public List<String> joinTables = new ArrayList<>();
        public Map<String, String> excluded = new LinkedHashMap<>();
        public List<String> warnings = new ArrayList<>();
        /** use-case controllers (OPS-3) */
        public List<io.specforge.model.DbModel.DbUseCase> useCases = new ArrayList<>();
    }

    /** Java types a column can be mapped to by the user (MAP-11), with the import each needs. */
    public static final Map<String, String> JAVA_TYPES = Map.ofEntries(
        Map.entry("String", ""), Map.entry("Integer", ""), Map.entry("Long", ""), Map.entry("Short", ""),
        Map.entry("Double", ""), Map.entry("Float", ""), Map.entry("Boolean", ""), Map.entry("byte[]", ""),
        Map.entry("BigDecimal", "java.math.BigDecimal"), Map.entry("BigInteger", "java.math.BigInteger"),
        Map.entry("UUID", "java.util.UUID"), Map.entry("LocalDate", "java.time.LocalDate"),
        Map.entry("LocalDateTime", "java.time.LocalDateTime"), Map.entry("LocalTime", "java.time.LocalTime"),
        Map.entry("OffsetDateTime", "java.time.OffsetDateTime"), Map.entry("OffsetTime", "java.time.OffsetTime"),
        Map.entry("Instant", "java.time.Instant"));

    public Result build(Snapshot snap, GenerationPlan plan) {
        Result res = new Result();
        GenerationPlan p = plan == null ? new GenerationPlan() : plan;

        // pure join tables become @ManyToMany between their two targets (MAP-4)
        Set<String> joinTables = new LinkedHashSet<>();
        for (Snapshot.Table t : snap.tables) {
            GenerationPlan.EntityDecision d = p.entities.get(t.qualifiedName());
            if (isPureJoinTable(t) && (d == null || !Boolean.TRUE.equals(d.asEntity))) joinTables.add(t.qualifiedName());
        }

        // selection (SEL-1, SEL-4)
        Map<String, Snapshot.Table> selected = new LinkedHashMap<>();
        Map<String, List<String>> keys = new LinkedHashMap<>();
        for (Snapshot.Table t : snap.tables) {
            String q = t.qualifiedName();
            if (!p.selected(q)) {
                res.excluded.put(q, "not selected");
                continue;
            }
            if (joinTables.contains(q)) continue;
            List<String> key = chooseKey(t, p.entities.get(q));
            if (key.isEmpty()) {
                res.excluded.put(q, "no primary key: choose key columns for it (SEL-4)");
                res.warnings.add("Table " + q + " has no primary key and no unique non-null column; choose its key columns to generate it.");
                continue;
            }
            selected.put(q, t);
            keys.put(q, key);
        }
        // a join table whose two targets are not both entities is generated as an entity of its own
        for (String q : joinTables) {
            Snapshot.Table t = snap.table(q);
            boolean bothTargets = t.foreignKeys.stream().allMatch(fk -> selected.containsKey(fk.refQualifiedName()));
            if (bothTargets && p.selected(q)) res.joinTables.add(q);
            else if (p.selected(q)) {
                selected.put(q, t);
                keys.put(q, t.primaryKey);
            }
        }

        // names first: relations refer to their target's class
        Map<String, String> classNames = names(selected, p, res.warnings);

        Map<String, DbEntity> byTable = new LinkedHashMap<>();
        for (Map.Entry<String, Snapshot.Table> e : selected.entrySet()) {
            DbEntity ent = entity(snap, e.getValue(), keys.get(e.getKey()), classNames, selected.keySet(), p.entities.get(e.getKey()));
            byTable.put(e.getKey(), ent);
            res.entities.add(ent);
        }
        for (String q : res.joinTables) addManyToMany(snap.table(q), byTable, res.warnings);
        syncReferenceTypes(res.entities);
        addInverses(res.entities);
        res.useCases = new UseCaseBuilder(byTable, res.warnings).build(p.controllers);
        // Oracle: the generated app does not quote every identifier (its driver cannot return generated keys for
        // quoted schema.table names), so a name that is not plain uppercase is quoted on its own (INT-8)
        if ("ORACLE".equals(snap.engine)) for (DbEntity ent : res.entities) quoteWhereNeeded(ent);
        // in MySQL and MariaDB a schema is the database itself: mappings stay unqualified so DB_URL decides
        if ("MYSQL".equals(snap.engine) || "MARIADB".equals(snap.engine)) {
            for (DbEntity ent : res.entities) {
                ent.schema = "";
                ent.id.sequenceSchema = "";
                for (DbManyToMany mm : ent.manyToMany) mm.joinSchema = "";
            }
        }
        for (DbEntity ent : res.entities) {
            for (String w : ent.warnings) res.warnings.add(ent.qualifiedName + ": " + w);
        }
        return res;
    }

    // ------------------------------------------------------------------ selection and names

    public static boolean isPureJoinTable(Snapshot.Table t) {
        if (t.foreignKeys.size() != 2 || t.columns.size() != 2 || t.primaryKey.size() != 2) return false;
        Set<String> fkCols = new HashSet<>();
        for (Snapshot.ForeignKey fk : t.foreignKeys) {
            if (fk.columns.size() != 1) return false;
            fkCols.add(fk.columns.get(0));
        }
        return fkCols.equals(new HashSet<>(t.primaryKey));
    }

    private static List<String> chooseKey(Snapshot.Table t, GenerationPlan.EntityDecision d) {
        if (d != null && !d.keyColumns.isEmpty()) return d.keyColumns;
        if (!t.primaryKey.isEmpty()) return t.primaryKey;
        for (Snapshot.UniqueKey u : t.uniqueKeys) {
            boolean allNotNull = u.columns.stream().allMatch(c -> t.column(c) != null && !t.column(c).nullable);
            if (allNotNull) return u.columns;
        }
        return List.of();
    }

    private static Map<String, String> names(Map<String, Snapshot.Table> selected, GenerationPlan p, List<String> warnings) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Snapshot.Table t : selected.values()) counts.merge(suggestedClass(t.name), 1, Integer::sum);
        Set<String> used = new HashSet<>();
        for (Map.Entry<String, Snapshot.Table> e : selected.entrySet()) {
            Snapshot.Table t = e.getValue();
            GenerationPlan.EntityDecision d = p.entities.get(e.getKey());
            String name = d != null && d.className != null && !d.className.isBlank() ? d.className.trim() : suggestedClass(t.name);
            if (counts.getOrDefault(name, 0) > 1 && (d == null || d.className == null)) name = Names.className(t.schema) + name;
            if (Names.RESERVED_CLASS_NAMES.contains(name) || Names.JAVA_KEYWORDS.contains(name.toLowerCase(Locale.ROOT))) {
                warnings.add("Table " + e.getKey() + " would become class '" + name + "', which clashes with a Java or framework type; renamed to '" + name + "Entity'.");
                name = name + "Entity";
            }
            String unique = name;
            for (int i = 2; !used.add(unique); i++) unique = name + i;
            out.put(e.getKey(), unique);
        }
        return out;
    }

    static String suggestedClass(String table) {
        String c = Names.className(Inflection.singular(table));
        return c.isEmpty() ? "Record" : c;
    }

    // ------------------------------------------------------------------ one entity

    private DbEntity entity(Snapshot snap, Snapshot.Table t, List<String> keyCols, Map<String, String> classNames,
                            Set<String> selected, GenerationPlan.EntityDecision decision) {
        DbEntity e = new DbEntity();
        e.schema = t.schema;
        e.table = t.name;
        e.qualifiedName = t.qualifiedName();
        e.className = classNames.get(e.qualifiedName);
        e.varName = Names.decap(e.className);
        // known now so use-case records can refer to it; the generator assigns the same name (and the others)
        e.dtoName = e.className + "Dto";
        // URLs follow the table, not a class name adjusted for Java (configurations → /configurations)
        e.path = pathOf(suggestedClass(t.name));
        e.comment = t.comment;
        if (decision != null) {
            if (decision.path != null && !decision.path.isBlank()) e.path = cleanPath(decision.path);
            if (decision.controllerName != null && decision.controllerName.trim().matches("[A-Z][A-Za-z0-9_]*")) e.controllerNameOverride = decision.controllerName.trim();
            if (Boolean.FALSE.equals(decision.controller)) e.controller = false;
        }
        Map<String, GenerationPlan.ColumnDecision> columns = decision == null ? Map.of() : decision.columns;
        Set<String> usedNames = new HashSet<>();

        // key (MAP-1, MAP-7, SEL-4)
        Set<String> keySet = new LinkedHashSet<>(keyCols);
        if (keyCols.size() == 1) {
            Snapshot.Column c = t.column(keyCols.get(0));
            DbField f = field(snap.engine, c, e, usedNames, columns.get(c.name));
            e.id.kind = "SINGLE";
            e.id.field = f;
            e.id.javaType = f.javaType;
            idStrategy(snap, t, c, e.id, e.warnings);
            if (decision != null && decision.idStrategy != null) applyIdDecision(snap, e, decision);
            if (e.id.generated()) f.validations.clear();
            else if (!f.nullable && !f.validations.contains("@NotNull")) f.validations.add(0, "@NotNull");
        } else {
            e.id.kind = "COMPOSITE";
            e.id.strategy = "ASSIGNED";
            e.id.javaType = e.className + ".Key";
            for (String col : keyCols) e.id.keyFields.add(field(snap.engine, t.column(col), e, usedNames, columns.get(col)));
        }

        // foreign keys (MAP-4, MAP-9, SEL-3)
        Set<String> relationColumns = new HashSet<>();
        for (Snapshot.ForeignKey fk : t.foreignKeys) {
            if (fk.columns.size() != 1) {
                e.warnings.add("composite foreign key " + fk.name + " is mapped as plain columns");
                continue;
            }
            String col = fk.columns.get(0);
            String target = fk.refQualifiedName();
            GenerationPlan.ColumnDecision colDecision = columns.get(col);
            if (colDecision != null && Boolean.FALSE.equals(colDecision.include) && !keySet.contains(col)) {
                relationColumns.add(col);
                if (!t.column(col).nullable && t.column(col).defaultValue == null) markReadOnly(e, "required column " + col + " was left out");
                continue;
            }
            if (!selected.contains(target)) {
                String how = decision == null ? null : decision.unselectedForeignKeys.get(col);
                if ("IGNORE".equalsIgnoreCase(how)) {
                    relationColumns.add(col);
                    if (!t.column(col).nullable && t.column(col).defaultValue == null) markReadOnly(e, "required column " + col + " was ignored");
                } else {
                    e.warnings.add("foreign key " + col + " → " + target + " is mapped as a plain id: " + target + " is not selected (SEL-3)");
                }
                continue;
            }
            Snapshot.Table tt = snap.table(target);
            if (tt.primaryKey.size() != 1 || !tt.primaryKey.get(0).equals(fk.refColumns.get(0))) {
                e.warnings.add("foreign key " + col + " references " + target + "." + fk.refColumns.get(0) + ", not its primary key; mapped as a plain column");
                continue;
            }
            if (keyCols.size() == 1 && keySet.contains(col)) {
                e.warnings.add("primary key " + col + " is also a foreign key (shared key); mapped as a plain id");
                continue;
            }
            DbRelation r = new DbRelation();
            String lower = col.toLowerCase(Locale.ROOT);
            String base = lower.replaceAll("_?id$", "");
            // "customer_id" → relation customer, DTO customerId; "country_code" → relation country, DTO countryCode
            boolean idSuffix = !base.equals(lower);
            String name = base.isEmpty() || !idSuffix ? Names.decap(classNames.get(target)) : Names.javaIdent(base);
            if (usedNames.contains(name) || !idSuffix && Names.javaIdent(col).equals(name)) name = name + "Ref";
            GenerationPlan.RelationDecision relDecision = decision == null ? null : decision.relations.get(col);
            if (relDecision != null && validIdent(relDecision.name) && !usedNames.contains(relDecision.name.trim())) name = relDecision.name.trim();
            usedNames.add(name);
            r.name = name;
            r.cap = Names.cap(name);
            r.targetClass = classNames.get(target);
            r.targetIdName = Names.javaIdent(tt.primaryKey.get(0));
            r.targetIdCap = Names.cap(r.targetIdName);
            r.column = col;
            r.nullable = t.column(col).nullable;
            Snapshot.Column targetKey = tt.column(tt.primaryKey.get(0));
            TypeRules.Mapping targetType = TypeRules.map(snap.engine, targetKey);
            r.targetIdType = targetType.supported() ? targetType.javaType() : "Long";
            if (keySet.contains(col)) {
                // the key field carries the value; the relation reuses it (@MapsId, MAP-9)
                for (DbField kf : e.id.keyFields) if (kf.column.equals(col)) {
                    r.mapsId = kf.name;
                    kf.relation = r.name;
                }
            } else {
                DbField idField = new DbField();
                idField.column = col;
                String dtoName = relDecision != null && validIdent(relDecision.dtoName) ? relDecision.dtoName.trim() : idSuffix ? name + "Id" : Names.javaIdent(col);
                idField.name = uniqueName(dtoName, usedNames);
                idField.cap = Names.cap(idField.name);
                TypeRules.Mapping m = TypeRules.map(snap.engine, targetKey);
                idField.javaType = m.supported() ? m.javaType() : "Long";
                if (m.importName() != null) e.dtoImports.add(m.importName());
                idField.nullable = r.nullable;
                idField.relation = r.name;
                if (!r.nullable) idField.validations.add("@NotNull");
                r.dtoField = idField;
            }
            if (relDecision != null && Boolean.TRUE.equals(relDecision.inverse)) {
                r.inverse = true;
                r.inverseName = validIdent(relDecision.inverseName) ? relDecision.inverseName.trim() : "";
            }
            relationColumns.add(col);
            e.relations.add(r);
        }

        // plain columns (MAP-3, MAP-6)
        for (Snapshot.Column c : t.columns) {
            if (keySet.contains(c.name) || relationColumns.contains(c.name)) continue;
            GenerationPlan.ColumnDecision cd = columns.get(c.name);
            if (cd != null && Boolean.FALSE.equals(cd.include)) {
                if (!c.nullable && c.defaultValue == null && !c.generated && !c.identity) markReadOnly(e, "required column " + c.name + " was left out");
                continue;
            }
            TypeRules.Mapping m = TypeRules.map(snap.engine, c);
            if (!m.supported() && (cd == null || cd.javaType == null || !JAVA_TYPES.containsKey(cd.javaType))) {
                e.warnings.add("column " + c.name + " is left out: " + m.unsupportedReason());
                if (!c.nullable && c.defaultValue == null && !c.generated) markReadOnly(e, "required column " + c.name + " has an unsupported type");
                continue;
            }
            e.fields.add(field(snap.engine, c, e, usedNames, cd));
        }

        for (Snapshot.UniqueKey u : t.uniqueKeys) if (!new LinkedHashSet<>(u.columns).equals(keySet)) e.uniqueColumns.add(u.columns);

        // operations (OPS-1, OPS-2); a read-only entity only reads
        if (decision != null && Boolean.TRUE.equals(decision.readOnly)) markReadOnly(e, "chosen read-only");
        for (String op : OPERATIONS) {
            boolean on = !e.readOnly || op.equals("read") || op.equals("list");
            if (decision != null && decision.operations.containsKey(op)) on = on && Boolean.TRUE.equals(decision.operations.get(op));
            e.ops.put(op, on);
        }

        for (DbField f : e.dtoFields()) if (FILTERABLE.contains(f.javaType) && !TypeRules.opaque(f.jdbcTypeCode)) e.filterable.add(f.name);
        for (DbRelation r : e.relations) if (r.dtoField != null && FILTERABLE.contains(r.dtoField.javaType)) e.filterable.add(r.dtoField.name);
        for (DbField f : e.dtoFields()) if (SORTABLE.contains(f.javaType) && !TypeRules.opaque(f.jdbcTypeCode)) e.sortable.add(f.name);
        List<DbField> components = new ArrayList<>(e.dtoFields());
        for (DbRelation r : e.relations) if (r.dtoField != null) components.add(r.dtoField);
        for (DbField f : components) {
            GenerationPlan.ColumnDecision cd = columns.get(f.column);
            if (cd == null) continue;
            if (cd.filter != null) {
                e.filterable.remove(f.name);
                if (cd.filter && FILTERABLE.contains(f.javaType) && !TypeRules.opaque(f.jdbcTypeCode)) e.filterable.add(f.name);
                else if (cd.filter) e.warnings.add("column " + f.column + " (" + f.javaType + ") cannot be used as a filter");
            }
            if (cd.sort != null) {
                e.sortable.remove(f.name);
                if (cd.sort && SORTABLE.contains(f.javaType) && !TypeRules.opaque(f.jdbcTypeCode)) e.sortable.add(f.name);
                else if (cd.sort) e.warnings.add("column " + f.column + " (" + f.javaType + ") cannot be used for sorting");
            }
        }
        return e;
    }

    /** MAP-1, MAP-11: the user's key strategy for a single-column key. Impossible choices keep the suggestion with a warning. */
    private static void applyIdDecision(Snapshot snap, DbEntity e, GenerationPlan.EntityDecision d) {
        String strategy = d.idStrategy.trim().toUpperCase(Locale.ROOT);
        switch (strategy) {
            case "IDENTITY", "ASSIGNED" -> {
                e.id.strategy = strategy;
                e.id.sequenceName = "";
                e.id.sequenceSchema = "";
                e.id.allocationSize = 1;
            }
            case "UUID" -> {
                if (!"UUID".equals(e.id.javaType)) {
                    e.warnings.add("key strategy UUID needs a UUID key; " + e.id.field.column + " is " + e.id.javaType + ", so the suggestion is kept");
                    return;
                }
                e.id.strategy = "UUID";
                e.id.sequenceName = "";
                e.id.sequenceSchema = "";
            }
            case "SEQUENCE" -> {
                String ref = d.sequenceName == null ? "" : d.sequenceName.trim();
                if (ref.isEmpty() && e.id.sequenceName.isEmpty()) {
                    e.warnings.add("key strategy SEQUENCE needs a sequence name; the suggestion is kept");
                    return;
                }
                if (!ref.isEmpty()) {
                    int dot = ref.lastIndexOf('.');
                    e.id.sequenceSchema = dot > 0 ? ref.substring(0, dot) : e.schema;
                    e.id.sequenceName = dot > 0 ? ref.substring(dot + 1) : ref;
                }
                e.id.strategy = "SEQUENCE";
                Snapshot.Sequence seq = null;
                for (Snapshot.Sequence s : snap.sequences) if (s.name.equalsIgnoreCase(e.id.sequenceName)) seq = s;
                if (seq == null) e.warnings.add("sequence " + e.id.sequenceName + " was not found in the snapshot");
                else if (d.allocationSize == null) e.id.allocationSize = Math.max(1, seq.increment);
                if (seq != null && d.allocationSize != null && d.allocationSize != seq.increment)
                    e.warnings.add("allocationSize " + d.allocationSize + " differs from the sequence's INCREMENT BY " + seq.increment + " (MAP-7)");
            }
            default -> {
                e.warnings.add("unknown key strategy '" + d.idStrategy + "'; the suggestion is kept");
                return;
            }
        }
        if (d.allocationSize != null && d.allocationSize > 0 && "SEQUENCE".equals(e.id.strategy)) e.id.allocationSize = d.allocationSize;
    }

    /** A reference has its target key's Java type, also when the user changed that key's type (MAP-11). */
    private static void syncReferenceTypes(List<DbEntity> entities) {
        Map<String, DbEntity> byClass = new LinkedHashMap<>();
        for (DbEntity e : entities) byClass.put(e.className, e);
        for (DbEntity e : entities) {
            for (DbRelation r : e.relations) {
                DbEntity target = byClass.get(r.targetClass);
                if (target == null || target.id.composite()) continue;
                r.targetIdType = target.id.javaType;
                if (r.dtoField != null && !r.dtoField.javaType.equals(target.id.javaType)) {
                    r.dtoField.javaType = target.id.javaType;
                    String imp = JAVA_TYPES.get(target.id.javaType);
                    if (imp != null && !imp.isEmpty()) e.dtoImports.add(imp);
                }
            }
        }
    }

    /** MAP-4: opt-in inverse one-to-many collections on the referenced entities. */
    private static void addInverses(List<DbEntity> entities) {
        Map<String, DbEntity> byClass = new LinkedHashMap<>();
        for (DbEntity e : entities) byClass.put(e.className, e);
        for (DbEntity source : entities) {
            for (DbRelation r : source.relations) {
                if (!r.inverse) continue;
                DbEntity target = byClass.get(r.targetClass);
                if (target == null) continue;
                Set<String> used = new HashSet<>();
                for (DbField f : target.dtoComponents()) used.add(f.name);
                for (DbRelation tr : target.relations) used.add(tr.name);
                for (io.specforge.model.DbModel.DbInverse inv : target.inverses) used.add(inv.name);
                String base = r.inverseName.isEmpty() ? Names.javaIdent(Inflection.plural(Names.snake(source.className))) : r.inverseName;
                io.specforge.model.DbModel.DbInverse inv = new io.specforge.model.DbModel.DbInverse();
                inv.name = uniqueName(base, used);
                inv.cap = Names.cap(inv.name);
                inv.sourceClass = source.className;
                inv.mappedBy = r.name;
                target.inverses.add(inv);
                target.entityImports.add("java.util.List");
                target.entityImports.add("java.util.ArrayList");
            }
        }
    }

    static String cleanPath(String path) {
        String p = path.trim().replaceAll("^/+|/+$", "").replaceAll("/{2,}", "/");
        return p.matches("[A-Za-z0-9._~/-]+") ? p : pathOf(Names.className(p));
    }

    private static boolean validIdent(String s) {
        return s != null && s.trim().matches("[a-z_$][A-Za-z0-9_$]*") && !Names.JAVA_KEYWORDS.contains(s.trim());
    }

    private static void markReadOnly(DbEntity e, String reason) {
        if (!e.readOnly) e.readOnlyReason = reason;
        e.readOnly = true;
    }

    private DbField field(String engine, Snapshot.Column c, DbEntity e, Set<String> usedNames, GenerationPlan.ColumnDecision cd) {
        TypeRules.Mapping m = TypeRules.map(engine, c);
        DbField f = new DbField();
        f.column = c.name;
        String name = cd != null && validIdent(cd.name) ? cd.name.trim() : Names.javaIdent(c.name);
        f.name = uniqueName(name, usedNames);
        f.cap = Names.cap(f.name);
        f.javaType = m.supported() ? m.javaType() : "String";
        f.jdbcTypeCode = m.jdbcTypeCode();
        if (m.importName() != null) {
            e.entityImports.add(m.importName());
            e.dtoImports.add(m.importName());
        }
        if (cd != null && cd.javaType != null && JAVA_TYPES.containsKey(cd.javaType) && !cd.javaType.equals(f.javaType)) {
            // the user's type (MAP-11); the column's SQL type code applied to the suggested type only
            f.javaType = cd.javaType;
            f.jdbcTypeCode = "";
            String imp = JAVA_TYPES.get(cd.javaType);
            if (!imp.isEmpty()) {
                e.entityImports.add(imp);
                e.dtoImports.add(imp);
            }
        }
        f.nullable = c.nullable;
        f.writable = !c.generated;
        f.dbDefault = c.defaultValue != null || c.identity || c.autoIncrement || c.generated;
        boolean lob = c.jdbcType == java.sql.Types.LONGVARCHAR || c.jdbcType == java.sql.Types.LONGNVARCHAR
            || c.jdbcType == java.sql.Types.CLOB || c.jdbcType == java.sql.Types.NCLOB;
        if (f.javaType.equals("String") && c.size != null && c.size > 0 && c.size < 65_535 && !"text".equalsIgnoreCase(c.vendorType)
            && !lob && !TypeRules.opaque(f.jdbcTypeCode)) f.length = c.size;
        // NUMBER without precision reports size 0 and scale -127: no @Digits then
        if (f.javaType.equals("BigDecimal") && c.size != null && c.size > 0 && c.size < 1000 && (c.scale == null || c.scale >= 0)) {
            f.precision = c.size;
            f.scale = c.scale == null ? 0 : c.scale;
        }
        if (!f.nullable && !f.dbDefault && f.writable) f.validations.add("@NotNull");
        if (f.length != null) f.validations.add("@Size(max = " + f.length + ")");
        if (f.precision != null) f.validations.add("@Digits(integer = " + (f.precision - f.scale) + ", fraction = " + f.scale + ")");
        return f;
    }

    /** MAP-7: identity, then a nextval() sequence, then a UUID default, else an assigned key. */
    private static void idStrategy(Snapshot snap, Snapshot.Table t, Snapshot.Column c, DbId id, List<String> warnings) {
        String def = c.defaultValue == null ? "" : c.defaultValue;
        String[] ref = sequenceReference(def);
        if (c.identity) {
            id.strategy = "IDENTITY";
        } else if (ref != null) {
            String seqSchema = ref[0] != null ? ref[0] : t.schema;
            String seqName = ref[1];
            Snapshot.Sequence seq = null;
            for (Snapshot.Sequence s : snap.sequences) if (s.name.equals(seqName) && s.schema.equals(seqSchema)) seq = s;
            if (seq == null) for (Snapshot.Sequence s : snap.sequences) if (s.name.equals(seqName)) seq = s;
            // unquoted names in a default may differ in case from the catalog (H2, Oracle)
            if (seq == null) for (Snapshot.Sequence s : snap.sequences) if (s.name.equalsIgnoreCase(seqName)) seq = s;
            if (seq != null) seqName = seq.name;
            id.strategy = "SEQUENCE";
            id.sequenceSchema = seq != null ? seq.schema : seqSchema;
            id.sequenceName = seqName;
            id.allocationSize = seq != null && seq.increment > 0 ? seq.increment : 1;
            if (seq == null) warnings.add(t.qualifiedName() + ": sequence " + seqName + " was not found; allocationSize 1 assumed");
        } else if (c.autoIncrement) {
            id.strategy = "IDENTITY";
        } else if (("uuid".equalsIgnoreCase(c.vendorType) || "uniqueidentifier".equalsIgnoreCase(c.vendorType)) && !def.isEmpty()) {
            id.strategy = "UUID";
        } else {
            id.strategy = "ASSIGNED";
        }
    }

    private static final Pattern FOLDED_UPPER = Pattern.compile("[A-Z][A-Z0-9_$#]*");

    /** Backtick-quotes (Hibernate's per-identifier quoting) every name Oracle would not find unquoted. */
    private static void quoteWhereNeeded(DbEntity e) {
        e.table = q(e.table);
        e.schema = q(e.schema);
        e.id.sequenceName = q(e.id.sequenceName);
        e.id.sequenceSchema = q(e.id.sequenceSchema);
        if (e.id.field != null) e.id.field.column = q(e.id.field.column);
        for (DbField f : e.id.keyFields) f.column = q(f.column);
        for (DbField f : e.fields) f.column = q(f.column);
        for (DbRelation r : e.relations) r.column = q(r.column);
        for (DbManyToMany m : e.manyToMany) {
            m.joinSchema = q(m.joinSchema);
            m.joinTable = q(m.joinTable);
            m.joinColumn = q(m.joinColumn);
            m.inverseJoinColumn = q(m.inverseJoinColumn);
        }
        List<List<String>> unique = new ArrayList<>();
        for (List<String> cols : e.uniqueColumns) unique.add(cols.stream().map(SuggestionEngine::q).toList());
        e.uniqueColumns = unique;
    }

    private static String q(String name) {
        return name == null || name.isEmpty() || FOLDED_UPPER.matcher(name).matches() ? name : "`" + name + "`";
    }

    /** {schema or null, name} of the sequence a column default draws from, or null. Quotes and brackets are removed. */
    static String[] sequenceReference(String def) {
        for (Pattern p : SEQUENCE_DEFAULTS) {
            Matcher m = p.matcher(def);
            if (!m.find()) continue;
            List<String> names = new ArrayList<>();
            for (String part : m.group(1).trim().split("\\.")) {
                String n = part.replaceAll("[\"`\\[\\]]", "").trim();
                if (!n.isEmpty()) names.add(n);
            }
            if (names.isEmpty()) continue;
            return new String[] {names.size() > 1 ? names.get(names.size() - 2) : null, names.get(names.size() - 1)};
        }
        return null;
    }

    private static void addManyToMany(Snapshot.Table join, Map<String, DbEntity> byTable, List<String> warnings) {
        List<Snapshot.ForeignKey> fks = new ArrayList<>(join.foreignKeys);
        fks.sort((a, b) -> Integer.compare(join.column(a.columns.get(0)).position, join.column(b.columns.get(0)).position));
        DbEntity owner = byTable.get(fks.get(0).refQualifiedName());
        DbEntity target = byTable.get(fks.get(1).refQualifiedName());
        if (owner == null || target == null || target.id.composite() || owner.id.composite()) {
            warnings.add("Join table " + join.qualifiedName() + " could not be mapped as many-to-many");
            return;
        }
        DbManyToMany mm = new DbManyToMany();
        Set<String> used = new HashSet<>();
        for (DbField f : owner.dtoFields()) used.add(f.name);
        for (DbRelation r : owner.relations) used.add(r.name);
        mm.name = uniqueName(Names.javaIdent(Inflection.plural(Names.snake(target.className))), used);
        mm.cap = Names.cap(mm.name);
        mm.targetClass = target.className;
        mm.joinSchema = join.schema;
        mm.joinTable = join.name;
        mm.joinColumn = fks.get(0).columns.get(0);
        mm.inverseJoinColumn = fks.get(1).columns.get(0);
        mm.targetIdType = target.id.javaType;
        mm.targetIdCap = target.id.field.cap;
        mm.dtoName = uniqueName(Names.decap(target.className) + "Ids", used);
        mm.dtoCap = Names.cap(mm.dtoName);
        owner.manyToMany.add(mm);
        owner.entityImports.add("java.util.Set");
        owner.entityImports.add("java.util.LinkedHashSet");
        owner.dtoImports.add("java.util.Set");
        String idImport = importOf(target.id.javaType);
        if (idImport != null) owner.dtoImports.add(idImport);
    }

    /** The import a key type needs in another class (DTO id sets, mappers), or null for java.lang types. */
    private static String importOf(String javaType) {
        return switch (javaType) {
            case "BigDecimal", "BigInteger" -> "java.math." + javaType;
            case "UUID" -> "java.util.UUID";
            case "LocalDate", "LocalDateTime", "LocalTime", "OffsetDateTime", "OffsetTime" -> "java.time." + javaType;
            default -> null;
        };
    }

    static String pathOf(String className) {
        List<String> words = Names.words(className);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i).toLowerCase(Locale.ROOT);
            if (i == words.size() - 1) w = Inflection.plural(w);
            if (sb.length() > 0) sb.append('-');
            sb.append(w);
        }
        return sb.toString();
    }

    private static String uniqueName(String base, Set<String> used) {
        String n = base;
        for (int i = 2; !used.add(n); i++) n = base + i;
        return n;
    }
}
