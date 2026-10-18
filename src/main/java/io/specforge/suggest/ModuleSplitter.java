package io.specforge.suggest;

import io.specforge.model.DbModel.DbEntity;
import io.specforge.model.DbModel.DbField;
import io.specforge.model.DbModel.DbManyToMany;
import io.specforge.model.DbModel.DbModule;
import io.specforge.model.DbModel.DbRelation;
import io.specforge.model.DbModel.DbUseCase;
import io.specforge.model.DbModel.DbUseCaseOp;
import io.specforge.plan.GenerationPlan;
import io.specforge.util.Names;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ARCH-3 … ARCH-5: which module each table belongs to, and what a module boundary does to the mapping.
 * <p>
 * Suggested modules are the groups of tables linked by foreign keys, each named after the table the others point
 * to most; the user's choice (plan.modules) wins. In the modular architecture a foreign key to another module's
 * table becomes a plain id property (the database still enforces it), so modules never reach into each other's
 * entities: they depend on {@code shared} only. Many-to-many links, inverse collections and use-case operations
 * across modules are left out, each with a warning.
 */
final class ModuleSplitter {

    /** names a module cannot take: the shared and security modules, layer and Java words */
    static final Set<String> RESERVED = Set.of("shared", "security", "exception", "model", "service", "controller", "repository",
        "entity", "internal", "config");

    private ModuleSplitter() {}

    /** Sets {@code e.module} for every entity (suggestion, then the plan) and returns the modules in order. */
    static List<DbModule> assign(Map<String, DbEntity> byTable, GenerationPlan plan, List<String> warnings) {
        // union-find over the relations between selected tables
        Map<String, String> parent = new HashMap<>();
        for (String q : byTable.keySet()) parent.put(q, q);
        Map<String, String> classToTable = new HashMap<>();
        for (DbEntity e : byTable.values()) classToTable.put(e.className, e.qualifiedName);
        Map<String, Integer> inbound = new HashMap<>();
        for (DbEntity e : byTable.values()) {
            for (DbRelation r : e.relations) link(parent, inbound, e.qualifiedName, classToTable.get(r.targetClass));
            for (DbManyToMany m : e.manyToMany) link(parent, inbound, e.qualifiedName, classToTable.get(m.targetClass));
        }
        Map<String, List<DbEntity>> groups = new LinkedHashMap<>();
        for (DbEntity e : byTable.values()) groups.computeIfAbsent(find(parent, e.qualifiedName), k -> new ArrayList<>()).add(e);

        Set<String> used = new LinkedHashSet<>();
        Map<String, String> suggested = new HashMap<>();
        for (List<DbEntity> g : groups.values()) {
            DbEntity anchor = g.stream().max(Comparator.<DbEntity>comparingInt(e -> inbound.getOrDefault(e.qualifiedName, 0))
                .thenComparing(e -> e.className, Comparator.reverseOrder())).orElseThrow();
            String name = unique(moduleName(anchor.className), used);
            for (DbEntity e : g) suggested.put(e.qualifiedName, name);
        }
        Map<String, DbModule> modules = new LinkedHashMap<>();
        for (DbEntity e : byTable.values()) {
            String chosen = plan.modules.get(e.qualifiedName);
            String name = suggested.get(e.qualifiedName);
            if (chosen != null && !chosen.isBlank()) {
                String c = chosen.trim().toLowerCase(Locale.ROOT);
                if (validName(c)) name = c;
                else warnings.add("Module name '" + chosen + "' for " + e.qualifiedName + " is not a valid package name (lower-case letters and digits, "
                    + "not " + String.join(", ", RESERVED) + "); the suggestion " + name + " is used.");
            }
            e.module = name;
            DbModule m = modules.computeIfAbsent(name, n -> {
                DbModule x = new DbModule();
                x.name = n;
                x.displayName = Character.toUpperCase(n.charAt(0)) + n.substring(1);
                return x;
            });
            m.tables.add(e.qualifiedName);
        }
        return new ArrayList<>(modules.values());
    }

    /** The modular architecture: no JPA link crosses a module boundary. */
    static void cut(Map<String, DbEntity> byTable, List<String> warnings) {
        Map<String, DbEntity> byClass = new HashMap<>();
        for (DbEntity e : byTable.values()) byClass.put(e.className, e);
        for (DbEntity e : byTable.values()) {
            for (Iterator<DbRelation> it = e.relations.iterator(); it.hasNext(); ) {
                DbRelation r = it.next();
                DbEntity target = byClass.get(r.targetClass);
                if (target == null || target.module.equals(e.module)) continue;
                if (r.dtoField == null || !r.mapsId.isEmpty()) {
                    warnings.add(e.className + "." + r.name + " is part of the key and refers to module " + target.module
                        + "; it stays a relation, which ModularStructureTests will report.");
                    continue;
                }
                // the id of a row of another module: a plain column, enforced by the database's foreign key
                DbField f = new DbField();
                f.column = r.column;
                f.name = r.dtoField.name;
                f.cap = Names.cap(f.name);
                f.javaType = r.dtoField.javaType;
                f.nullable = r.nullable;
                f.validations = new ArrayList<>(r.dtoField.validations);
                if (!r.nullable && !f.validations.contains("@NotNull")) f.validations.add(0, "@NotNull");
                String imp = SuggestionEngine.JAVA_TYPES.getOrDefault(f.javaType, "");
                if (!imp.isEmpty()) {
                    e.entityImports.add(imp);
                    e.dtoImports.add(imp);
                }
                e.fields.add(f);
                it.remove();
                e.childQueries.removeIf(q -> q == r);
                target.inverses.removeIf(inv -> inv.sourceClass.equals(e.className) && inv.mappedBy.equals(r.name));
            }
            for (Iterator<DbManyToMany> it = e.manyToMany.iterator(); it.hasNext(); ) {
                DbManyToMany m = it.next();
                DbEntity target = byClass.get(m.targetClass);
                if (target == null || target.module.equals(e.module)) continue;
                warnings.add(e.className + "." + m.name + " links to " + m.targetClass + " in module " + target.module
                    + "; many-to-many links across modules are not generated (join table " + m.joinTable + ").");
                it.remove();
            }
        }
    }

    /** A use case lives in its first operation's module; operations on other modules' tables are left out. */
    static void useCases(List<DbUseCase> useCases, List<String> warnings) {
        for (Iterator<DbUseCase> it = useCases.iterator(); it.hasNext(); ) {
            DbUseCase u = it.next();
            if (u.operations.isEmpty()) continue;
            u.module = u.operations.get(0).entity.module;
            for (Iterator<DbUseCaseOp> ops = u.operations.iterator(); ops.hasNext(); ) {
                DbUseCaseOp op = ops.next();
                boolean foreign = !op.entity.module.equals(u.module) || op.child != null && !op.child.module.equals(u.module);
                if (!foreign) continue;
                warnings.add("Use case " + u.name + ": " + op.method + " uses module " + (op.entity.module.equals(u.module) ? op.child.module : op.entity.module)
                    + ", outside its module " + u.module + "; it is not generated.");
                ops.remove();
            }
            if (u.operations.isEmpty()) it.remove();
        }
    }

    static boolean validName(String n) {
        return n.matches("[a-z][a-z0-9]{0,29}") && Names.isJavaIdentifier(n) && !RESERVED.contains(n);
    }

    private static String moduleName(String className) {
        String n = className.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (n.isEmpty() || !Character.isLetter(n.charAt(0))) n = "m" + n;
        if (n.length() > 30) n = n.substring(0, 30);
        return validName(n) ? n : n + "s";
    }

    private static String unique(String n, Set<String> used) {
        String c = n;
        for (int i = 2; !used.add(c); i++) c = n + i;
        return c;
    }

    private static void link(Map<String, String> parent, Map<String, Integer> inbound, String from, String to) {
        if (to == null) return;
        inbound.merge(to, 1, Integer::sum);
        String a = find(parent, from), b = find(parent, to);
        if (!a.equals(b)) parent.put(a, b);
    }

    private static String find(Map<String, String> parent, String x) {
        while (!parent.get(x).equals(x)) x = parent.get(x);
        return x;
    }
}
