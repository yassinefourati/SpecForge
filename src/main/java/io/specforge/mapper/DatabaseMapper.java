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
        SuggestionEngine.Result r = new SuggestionEngine().build(snap, plan);
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
        for (String w : r.warnings) out.messages.add(new Msg("WARN", w));
        for (Map.Entry<String, String> x : r.excluded.entrySet())
            if (!x.getValue().equals("not selected")) out.messages.add(new Msg("INFO", "Not generated: " + x.getKey() + " (" + x.getValue() + ")."));
        for (String j : r.joinTables) out.messages.add(new Msg("INFO", "Join table " + j + " is mapped as a many-to-many relation, not as an entity."));
        if (r.entities.isEmpty()) out.messages.add(new Msg("WARN", "No table is selected, so nothing will be generated."));
        out.security = security(cfg, r);
        return out;
    }

    /** R1: with security on, every endpoint requires HTTP Basic with the development user (GEN-3). */
    private static SecurityDef security(GeneratorConfig cfg, SuggestionEngine.Result r) {
        SecurityDef sd = new SecurityDef();
        if (!cfg.security.enabled) return sd;
        sd.present = true;
        sd.usesBasic = true;
        SchemeDef basic = new SchemeDef();
        basic.name = "basicAuth";
        basic.javaName = "basicAuth";
        basic.type = "HTTP_BASIC";
        basic.description = "Development user from application.properties; replace with your own user store.";
        basic.used = true;
        sd.schemes.add(basic);
        for (DbEntity e : r.entities) {
            if (!e.hasController()) continue;
            String base = "/" + e.path;
            String item = base + (e.id.composite() ? e.id.keyFields.stream().map(f -> "/{" + f.name + "}").reduce("", String::concat) : "/{id}");
            if (e.op("list")) sd.rules.add(rule("GET", base, e.className + ".list"));
            if (e.op("create")) sd.rules.add(rule("POST", base, e.className + ".create"));
            if (e.op("read")) sd.rules.add(rule("GET", item, e.className + ".read"));
            if (e.op("update")) sd.rules.add(rule("PUT", item, e.className + ".update"));
            if (e.op("patch")) sd.rules.add(rule("PATCH", item, e.className + ".patch"));
            if (e.op("delete")) sd.rules.add(rule("DELETE", item, e.className + ".delete"));
        }
        for (io.specforge.model.DbModel.DbUseCase uc : r.useCases)
            for (io.specforge.model.DbModel.DbUseCaseOp op : uc.operations)
                sd.rules.add(rule(op.httpMethod(), (uc.path.isEmpty() ? "" : "/" + uc.path) + op.path, uc.name + "." + op.method));
        return sd;
    }

    private static Rule rule(String method, String pattern, String operation) {
        Rule r = new Rule();
        r.method = method;
        r.pattern = pattern;
        r.operation = operation;
        r.expression = "isAuthenticated()";
        r.kind = "AUTHENTICATED";
        return r;
    }
}
