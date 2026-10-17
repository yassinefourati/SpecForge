package io.specforge.suggest;

import io.specforge.model.DbModel;
import io.specforge.model.DbModel.DbEntity;
import io.specforge.model.DbModel.DbField;
import io.specforge.model.DbModel.DbRecord;
import io.specforge.model.DbModel.DbRelation;
import io.specforge.model.DbModel.DbUseCase;
import io.specforge.model.DbModel.DbUseCaseOp;
import io.specforge.plan.GenerationPlan;
import io.specforge.util.Names;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The plan's use-case controllers (OPS-3) → {@link DbUseCase}s, and their effect on the entities: operations moved
 * out of the entities' own controllers (OPS-4), service methods and child queries the use cases need. A decision that
 * cannot work (unknown table, no relation between parent and child, composite parent, read-only child, a route that
 * another controller already serves) is skipped with a warning, never generated half-way.
 */
final class UseCaseBuilder {

    private final Map<String, DbEntity> byTable;
    private final List<String> warnings;

    UseCaseBuilder(Map<String, DbEntity> byTable, List<String> warnings) {
        this.byTable = byTable;
        this.warnings = warnings;
    }

    List<DbUseCase> build(List<GenerationPlan.ControllerDecision> controllers) {
        List<DbUseCase> out = new ArrayList<>();
        if (controllers == null) return out;
        Set<String> names = new HashSet<>();
        for (GenerationPlan.ControllerDecision d : controllers) {
            String name = Names.className(d.name == null ? "" : d.name.trim().replaceAll("(?i)controller$", ""));
            if (name.isEmpty() || Names.RESERVED_CLASS_NAMES.contains(name)) {
                warnings.add("A use-case controller needs a name (got '" + d.name + "'); it was left out.");
                continue;
            }
            if (!names.add(name)) {
                warnings.add("Two use-case controllers are named " + name + "; the second was left out.");
                continue;
            }
            DbUseCase uc = new DbUseCase();
            uc.name = name;
            String raw = d.path == null ? "" : d.path.trim();
            // "/" is the root: the use case's own paths start right after the host
            // a use case is not a resource: its name is not pluralized ("Checkout" → /checkout)
            uc.path = raw.isEmpty() ? String.join("-", Names.words(name)).toLowerCase(Locale.ROOT)
                : raw.replaceAll("/", "").isEmpty() ? "" : SuggestionEngine.cleanPath(raw);
            Set<String> methods = new HashSet<>();
            for (GenerationPlan.UseCaseOperation o : d.operations) {
                DbUseCaseOp op = operation(uc, o);
                if (op == null) continue;
                List<String> expected = op.kind.equals("ENTITY") && op.entity.id.composite() && op.itemPath()
                    ? op.entity.id.keyFields.stream().map(f -> f.name).toList() : op.itemPath() ? List.of("id") : List.of();
                if (!variables(op.path).equals(expected)) {
                    warnings.add("Use case " + name + ": path " + op.path + " of " + op.method + " must name " + (expected.isEmpty() ? "no variables"
                        : "{" + String.join("}, {", expected) + "}") + "; the suggested path is used.");
                    op.path = null;
                    GenerationPlan.UseCaseOperation suggested = copyWithoutPath(o);
                    op = operation(uc, suggested);
                    if (op == null) continue;
                }
                if (!methods.add(op.method)) op.method = op.method + methods.size();
                uc.operations.add(op);
            }
            if (uc.operations.isEmpty()) {
                warnings.add("Use-case controller " + name + " has no operation that can be generated; it was left out.");
                continue;
            }
            out.add(uc);
        }
        checkRoutes(out);
        out.removeIf(uc -> {
            if (!uc.operations.isEmpty()) return false;
            warnings.add("Use-case controller " + uc.name + " has no operation left; it was left out.");
            return true;
        });
        return out;
    }

    private DbUseCaseOp operation(DbUseCase uc, GenerationPlan.UseCaseOperation o) {
        String kind = o.kind == null ? "ENTITY" : o.kind.trim().toUpperCase(Locale.ROOT);
        DbEntity entity = byTable.get(o.entity);
        String where = "Use case " + uc.name + ": ";
        if (entity == null) {
            warnings.add(where + "table " + o.entity + " is not generated; its operation was left out.");
            return null;
        }
        DbUseCaseOp op = new DbUseCaseOp();
        op.kind = kind;
        op.entity = entity;
        if (kind.equals("ENTITY")) return entityOperation(uc, op, o, where);

        DbEntity child = byTable.get(o.child);
        if (child == null) {
            warnings.add(where + "child table " + o.child + " is not generated; " + kind + " was left out.");
            return null;
        }
        DbRelation found = null;
        for (DbRelation r : child.relations) {
            if (!r.targetClass.equals(entity.className)) continue;
            if (o.via != null && !o.via.isBlank() && !r.column.equalsIgnoreCase(o.via.trim())) continue;
            found = r;
            break;
        }
        final DbRelation link = found;
        if (link == null) {
            warnings.add(where + child.className + " has no foreign key to " + entity.className + "; " + kind + " was left out.");
            return null;
        }
        if (entity.id.composite()) {
            warnings.add(where + entity.className + " has a composite key; parent / children operations need a single-column key.");
            return null;
        }
        op.child = child;
        op.link = link;
        op.linkComponent = link.mapsId.isEmpty() ? link.dtoField.name : link.mapsId;
        op.parentProperty = entity.varName;
        String childPlural = Names.className(Inflection.plural(Names.snake(child.className)));
        op.childrenProperty = Names.decap(childPlural);
        op.childQuery = DbModel.childQueryName(link);
        if (child.childQueries.stream().noneMatch(r -> r.name.equals(link.name))) child.childQueries.add(link);
        String withChildren = entity.className + "With" + childPlural;

        switch (kind) {
            case "WITH_CHILDREN" -> {
                entity.serviceOps.put("read", true);
                op.method = name(o.name, "read" + withChildren);
                op.path = path(o.path, "/" + entity.path + "/{id}", "/{id}");
                op.responseRecord = withChildren;
                response(uc, op);
                use(uc.services, entity);
                use(uc.mappers, child);
                use(uc.repositories, child);
            }
            case "CHILDREN" -> {
                op.method = name(o.name, "list" + childPlural + "Of" + entity.className);
                op.path = path(o.path, "/" + entity.path + "/{id}/" + child.path, null);
                use(uc.mappers, child);
                use(uc.repositories, child);
                use(uc.repositories, entity);
                use(uc.services, child); // for its sort whitelist
            }
            case "AGGREGATE_CREATE", "AGGREGATE_UPDATE" -> {
                if (child.readOnly || entity.readOnly) {
                    warnings.add(where + kind + " writes " + entity.className + " and " + child.className + ", but one of them is read-only; it was left out.");
                    return null;
                }
                boolean create = kind.equals("AGGREGATE_CREATE");
                entity.serviceOps.put(create ? "create" : "update", true);
                child.serviceOps.put("create", true);
                op.method = name(o.name, (create ? "create" : "update") + withChildren);
                op.path = create ? path(o.path, "/" + entity.path, "") : path(o.path, "/" + entity.path + "/{id}", "/{id}");
                op.responseRecord = withChildren;
                op.requestRecord = withChildren + "Request";
                op.itemRecord = child.className + (child.relations.stream().filter(r -> r.targetClass.equals(entity.className)).count() > 1
                    ? "By" + link.cap : "") + "Input";
                response(uc, op);
                request(uc, op);
                use(uc.services, entity);
                use(uc.services, child);
                if (!create) use(uc.repositories, child);
            }
            default -> {
                warnings.add(where + "unknown operation kind '" + o.kind + "'; it was left out.");
                return null;
            }
        }
        uc.imports.addAll(entity.dtoImports);
        uc.imports.addAll(child.dtoImports);
        return op;
    }

    /** OPS-3 / OPS-4: an entity's own operation served by the use-case controller. */
    private DbUseCaseOp entityOperation(DbUseCase uc, DbUseCaseOp op, GenerationPlan.UseCaseOperation o, String where) {
        DbEntity e = op.entity;
        String operation = o.operation == null ? "" : o.operation.trim().toLowerCase(Locale.ROOT);
        if (!SuggestionEngine.OPERATIONS.contains(operation)) {
            warnings.add(where + "unknown operation '" + o.operation + "' on " + e.className + "; it was left out.");
            return null;
        }
        boolean write = !operation.equals("read") && !operation.equals("list");
        if (write && e.readOnly) {
            warnings.add(where + e.className + " is read-only (" + e.readOnlyReason + "); " + operation + " was left out.");
            return null;
        }
        op.operation = operation;
        e.serviceOps.put(operation, true);
        if (!Boolean.TRUE.equals(o.keepInEntityController)) e.ops.put(operation, false);
        String plural = Names.className(Inflection.plural(Names.snake(e.className)));
        op.method = name(o.name, operation.equals("list") ? "list" + plural : operation + e.className);
        String id = op.itemPath() ? idSegments(e) : "";
        op.path = path(o.path, "/" + e.path + id, id);
        use(uc.services, e);
        uc.imports.addAll(e.dtoImports);
        return op;
    }

    private static List<String> variables(String path) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{([^}]+)}").matcher(path);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static GenerationPlan.UseCaseOperation copyWithoutPath(GenerationPlan.UseCaseOperation o) {
        GenerationPlan.UseCaseOperation c = new GenerationPlan.UseCaseOperation();
        c.kind = o.kind;
        c.entity = o.entity;
        c.operation = o.operation;
        c.child = o.child;
        c.via = o.via;
        c.name = o.name;
        c.keepInEntityController = o.keepInEntityController;
        return c;
    }

    private static void response(DbUseCase uc, DbUseCaseOp op) {
        DbRecord r = new DbRecord();
        r.name = op.responseRecord;
        r.doc = op.entity.className + " with its " + op.childrenProperty + " (" + op.child.className + " rows referencing it).";
        r.components.add(component(op.parentProperty, op.entity.dtoName, List.of()));
        r.components.add(component(op.childrenProperty, "List<" + op.child.dtoName + ">", List.of()));
        r.imports.add("java.util.List");
        add(uc, r);
    }

    private static void request(DbUseCase uc, DbUseCaseOp op) {
        DbRecord item = new DbRecord();
        item.name = op.itemRecord;
        item.doc = "A " + op.child.className + " inside a request for its " + op.entity.className + "; the "
            + op.linkComponent + " is taken from the " + op.entity.className + ".";
        for (DbField f : op.child.dtoComponents()) {
            if (f.name.equals(op.linkComponent)) continue;
            item.components.add(component(f.name, f.javaType, f.validations));
        }
        item.imports.addAll(op.child.dtoImports);
        add(uc, item);

        DbRecord req = new DbRecord();
        req.name = op.requestRecord;
        req.doc = op.entity.className + " and its " + op.childrenProperty + ", written in one transaction.";
        req.components.add(component(op.parentProperty, op.entity.dtoName, List.of("@Valid", "@NotNull")));
        req.components.add(component(op.childrenProperty, "List<" + op.itemRecord + ">", List.of("@Valid", "@NotNull")));
        req.imports.add("java.util.List");
        add(uc, req);
    }

    private static DbField component(String name, String type, List<String> validations) {
        DbField f = new DbField();
        f.name = name;
        f.cap = Names.cap(name);
        f.javaType = type;
        f.validations = new ArrayList<>(validations);
        return f;
    }

    private static void add(DbUseCase uc, DbRecord r) {
        if (uc.records.stream().noneMatch(x -> x.name.equals(r.name))) uc.records.add(r);
    }

    private static void use(List<DbEntity> list, DbEntity e) {
        if (!list.contains(e)) list.add(e);
    }

    private static String name(String chosen, String suggested) {
        String n = chosen == null ? "" : chosen.trim();
        return n.matches("[a-z][A-Za-z0-9]*") && !Names.JAVA_KEYWORDS.contains(n) ? n : Names.decap(suggested);
    }

    /**
     * The user's path, or the suggestion. A chosen path for an item operation gets the key segments appended when it
     * has none; {@code keys == null} means the path must name its variables itself (else the suggestion is used).
     */
    private String path(String chosen, String suggested, String keys) {
        if (chosen == null || chosen.isBlank()) return suggested;
        String p = chosen.trim().replaceAll("/+$", "");
        if (!p.startsWith("/")) p = "/" + p;
        if (!p.matches("[A-Za-z0-9._~/{}-]*")) return suggested;
        if (!p.contains("{")) {
            if (keys == null) return suggested;
            p = p + keys;
        }
        return p;
    }

    /** Two controllers serving the same method and path make the application fail to start: warn and drop the later. */
    private void checkRoutes(List<DbUseCase> useCases) {
        Map<String, String> routes = new LinkedHashMap<>();
        for (DbEntity e : byTable.values()) {
            if (!e.controller) continue;
            String base = "/" + e.path;
            String item = base + idSegments(e);
            for (String op : SuggestionEngine.OPERATIONS) {
                if (!e.op(op)) continue;
                String route = switch (op) {
                    case "list" -> "GET " + base;
                    case "create" -> "POST " + base;
                    case "read" -> "GET " + item;
                    case "update" -> "PUT " + item;
                    case "patch" -> "PATCH " + item;
                    default -> "DELETE " + item;
                };
                routes.put(normalize(route), e.className + " controller");
            }
        }
        for (DbUseCase uc : useCases) {
            List<DbUseCaseOp> kept = new ArrayList<>();
            for (DbUseCaseOp op : uc.operations) {
                String route = normalize(op.httpMethod() + " /" + uc.path + op.path);
                String owner = routes.putIfAbsent(route, uc.name + " controller");
                if (owner != null) warnings.add("Use case " + uc.name + ": " + route + " is already served by the " + owner + "; " + op.method + " was left out.");
                else kept.add(op);
            }
            uc.operations = kept;
        }
    }

    static String idSegments(DbEntity e) {
        if (!e.id.composite()) return "/{id}";
        StringBuilder sb = new StringBuilder();
        for (DbField f : e.id.keyFields) sb.append("/{").append(f.name).append('}');
        return sb.toString();
    }

    /** Path variables differ in name only: /a/{id} and /a/{orderId} are the same route. */
    private static String normalize(String route) {
        return route.replaceAll("\\{[^}]+}", "{}").replaceAll("/+", "/");
    }
}
