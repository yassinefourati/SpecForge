package io.specforge.mapper;

import io.specforge.config.GeneratorConfig;
import io.specforge.model.DbModel.DbEntity;
import io.specforge.model.DbModel.SourceFacts;
import io.specforge.model.Model.ApiModel;
import io.specforge.model.Model.ClassDef;
import io.specforge.model.Model.Msg;
import io.specforge.model.Model.Rule;
import io.specforge.model.Model.SchemeDef;
import io.specforge.model.Model.SecurityDef;
import io.specforge.plan.GenerationPlan;
import io.specforge.snapshot.Snapshot;
import io.specforge.source.SourceKind;
import io.specforge.suggest.SuggestionEngine;
import io.specforge.util.Names;

import java.util.Map;

/**
 * Database flow: snapshot + suggestions + the user's plan → the {@link ApiModel} every template understands (D1).
 * Entities carry their table facts in {@code ClassDef.db}; the db-*.ftl templates render them.
 */
public class DatabaseMapper {

    public ApiModel map(Snapshot snap, GenerationPlan plan, GeneratorConfig cfg, String title, String jdbcUrl) {
        boolean modular = "MODULAR".equalsIgnoreCase(cfg.architecture);
        // the hexagonal ports take a request and return a response record, whatever the style (ARCH-6)
        boolean split = cfg.style().requestResponse() || "HEXAGONAL".equalsIgnoreCase(cfg.architecture);
        SuggestionEngine.Result r = new SuggestionEngine().split(split).modular(modular).build(snap, plan);
        ApiModel out = new ApiModel();
        out.title = title == null || title.isBlank() ? "Database API" : title;
        out.version = "1.0.0";
        out.projectVersion = "0.0.1-SNAPSHOT";
        out.description = "Generated from the " + snap.engine + " schema" + (snap.schemas.size() == 1 ? " " + snap.schemas.get(0) : "s " + snap.schemas);
        out.artifactId = !cfg.options.artifactId.isBlank() ? cfg.options.artifactId.trim() : Names.slug(out.title);
        out.applicationClass = Names.className(out.title) + "Application";
        if (Names.RESERVED_CLASS_NAMES.contains(out.applicationClass)) out.applicationClass = "Application";
        out.basePath = "";

        SourceKind kind = SourceKind.of(snap.engine);
        SourceFacts src = new SourceFacts();
        src.kind = "DATABASE";
        src.engine = kind.name();
        src.engineLabel = kind.label;
        src.schemas = snap.schemas;
        src.jdbcUrl = jdbcUrl == null ? "" : jdbcUrl;
        src.initializrId = kind.initializrId;
        src.connectionName = title == null ? "" : title;
        out.source = src;

        for (DbEntity e : r.entities) listParameters(e, cfg, out);
        for (DbEntity e : r.entities) {
            ClassDef c = new ClassDef();
            c.key = e.qualifiedName;
            c.className = e.className;
            c.kind = "ENTITY";
            c.table = e.table;
            c.description = e.comment;
            c.reason = "table " + e.qualifiedName;
            c.db = e;
            out.entities.add(c);
        }
        out.useCases = r.useCases;
        if (modular) out.modules = r.modules;
        java.util.List<String> tables = new java.util.ArrayList<>();
        for (DbEntity e : r.entities) tables.add(e.qualifiedName);
        tables.addAll(r.joinTables);
        out.snapshot(snap, tables);
        for (String w : r.warnings) out.messages.add(new Msg("WARN", w));
        for (Map.Entry<String, String> x : r.excluded.entrySet())
            if (!x.getValue().equals("not selected")) out.messages.add(new Msg("INFO", "Not generated: " + x.getKey() + " (" + x.getValue() + ")."));
        for (String j : r.joinTables) out.messages.add(new Msg("INFO", "Join table " + j + " is mapped as a many-to-many relation, not as an entity."));
        if (r.entities.isEmpty()) out.messages.add(new Msg("WARN", "No table is selected, so nothing will be generated."));
        out.security = security(cfg, r);
        return out;
    }

    /**
     * CONTRACT-3: the list parameters this entity gets with the chosen list options. A parameter name that another
     * parameter already uses is left out with a warning, never silently shadowed.
     */
    private static void listParameters(DbEntity e, GeneratorConfig cfg, ApiModel out) {
        java.util.Set<String> taken = new java.util.HashSet<>(java.util.List.of("page", "size", "sort"));
        taken.addAll(e.filterable);
        if (!cfg.lists().q()) e.searchable.clear();
        else if (!e.searchable.isEmpty() && !taken.add("q")) {
            out.messages.add(new Msg("WARN", e.className + ": the filter 'q' takes the name of the text search, so " + e.className + " has no text search."));
            e.searchable.clear();
        }
        if (!cfg.lists().rangeFilters) {
            e.rangeable.clear();
            return;
        }
        for (java.util.Iterator<String> it = e.rangeable.iterator(); it.hasNext(); ) {
            String p = it.next();
            if (!taken.contains(p + "From") && !taken.contains(p + "To")) {
                taken.add(p + "From");
                taken.add(p + "To");
                continue;
            }
            out.messages.add(new Msg("WARN", e.className + ": no range on " + p + ", because " + p + "From or " + p + "To is already a parameter."));
            it.remove();
        }
    }

    /**
     * With security on, every endpoint requires a signed-in caller (GEN-3): HTTP Basic with a development user, or
     * a JWT from an OAuth2 authorization server such as Keycloak, optionally with a role for reads and one for
     * writes (CONTRACT-7).
     */
    private static SecurityDef security(GeneratorConfig cfg, SuggestionEngine.Result r) {
        SecurityDef sd = new SecurityDef();
        if (!cfg.security.enabled) return sd;
        sd.present = true;
        boolean jwt = cfg.security.databaseJwt();
        String read = jwt ? role(cfg.security.readRole) : "";
        String write = jwt ? role(cfg.security.writeRole) : "";
        SchemeDef scheme = new SchemeDef();
        if (jwt) {
            sd.usesJwt = true;
            scheme.name = "bearerAuth";
            scheme.javaName = "bearerAuth";
            scheme.type = "HTTP_BEARER";
            scheme.bearerFormat = "JWT";
            scheme.description = "Access tokens (JWT) from your authorization server, e.g. a Keycloak realm.";
        } else {
            sd.usesBasic = true;
            scheme.name = "basicAuth";
            scheme.javaName = "basicAuth";
            scheme.type = "HTTP_BASIC";
            scheme.description = "Development user from application.properties; replace with your own user store.";
        }
        scheme.used = true;
        sd.schemes.add(scheme);
        for (DbEntity e : r.entities) {
            if (!e.hasController()) continue;
            String base = "/" + e.path;
            String item = base + (e.id.composite() ? e.id.keyFields.stream().map(f -> "/{" + f.name + "}").reduce("", String::concat) : "/{id}");
            if (e.op("list")) sd.rules.add(rule("GET", base, e.className + ".list", read));
            if (e.op("create")) sd.rules.add(rule("POST", base, e.className + ".create", write));
            if (e.op("read")) sd.rules.add(rule("GET", item, e.className + ".read", read));
            if (e.op("update")) sd.rules.add(rule("PUT", item, e.className + ".update", write));
            if (e.op("patch")) sd.rules.add(rule("PATCH", item, e.className + ".patch", write));
            if (e.op("delete")) sd.rules.add(rule("DELETE", item, e.className + ".delete", write));
        }
        for (io.specforge.model.DbModel.DbUseCase uc : r.useCases)
            for (io.specforge.model.DbModel.DbUseCaseOp op : uc.operations) {
                String method = op.httpMethod();
                sd.rules.add(rule(method, (uc.path.isEmpty() ? "" : "/" + uc.path) + op.path, uc.name + "." + op.method,
                    method.equals("GET") ? read : write));
            }
        return sd;
    }

    /** A role name as hasRole() wants it: trimmed, without the ROLE_ prefix Spring adds itself. */
    private static String role(String r) {
        String t = r == null ? "" : r.trim();
        return t.startsWith("ROLE_") ? t.substring(5) : t;
    }

    private static Rule rule(String method, String pattern, String operation, String role) {
        Rule r = new Rule();
        r.method = method;
        r.pattern = pattern;
        r.operation = operation;
        if (role.isEmpty()) {
            r.expression = "isAuthenticated()";
            r.kind = "AUTHENTICATED";
        } else {
            r.expression = "hasRole('" + role + "')";
            r.kind = "EXPRESSION";
        }
        return r;
    }
}
