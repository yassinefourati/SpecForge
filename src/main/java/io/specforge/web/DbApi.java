package io.specforge.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.specforge.App;
import io.specforge.config.GeneratorConfig;
import io.specforge.jobs.Jobs;
import io.specforge.mapper.DatabaseMapper;
import io.specforge.model.DbModel.DbEntity;
import io.specforge.model.DbModel.DbField;
import io.specforge.model.DbModel.DbManyToMany;
import io.specforge.model.DbModel.DbRelation;
import io.specforge.model.Model.ApiModel;
import io.specforge.plan.GenerationPlan;
import io.specforge.secrets.SecretStore;
import io.specforge.snapshot.Snapshot;
import io.specforge.snapshot.SnapshotStore;
import io.specforge.source.ConnectionSettings;
import io.specforge.source.ConnectionStore;
import io.specforge.source.SourceException;
import io.specforge.source.SourceKind;
import io.specforge.source.jdbc.Drivers;
import io.specforge.source.jdbc.JdbcConnector;
import io.specforge.suggest.SuggestionEngine;
import io.specforge.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The database flow's endpoints (Appendix C): engines, drivers, saved connections, test, introspection jobs,
 * snapshots and the default plan. Errors carry a code from the catalog and never a secret (SEC-4, SEC-5).
 */
public class DbApi {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SourceReq {
        /** a saved connection … */
        public String connectionId;
        /** … or unsaved settings */
        public ConnectionSettings connection;
        public List<String> schemas = new ArrayList<>();
        /** ignore a cached snapshot and read the database again */
        public boolean refresh;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlanReq {
        public String snapshotId;
        public GenerationPlan plan;
    }

    private final Path home;
    private final Jobs jobs = new Jobs();

    /** The job runner shared with Studio's compile check (polled through /api/jobs/{id}). */
    Jobs jobs() {
        return jobs;
    }
    private final SnapshotStore snapshots;
    private volatile SecretStore secrets;
    private volatile ConnectionStore connections;

    public DbApi(Path home) {
        this.home = home;
        this.snapshots = new SnapshotStore(home);
    }

    /** Handles database-flow routes; null when the path is not one of them. */
    public Api.Resp handle(String method, String path, Map<String, String> query, byte[] body) throws IOException {
        try {
            if (path.equals("/api/db/kinds") && method.equals("GET")) return Api.json(kinds());
            if (path.equals("/api/db/drivers") && method.equals("GET")) return Api.json(Drivers.report());
            if (path.equals("/api/db/connections") && method.equals("GET")) return Api.json(Map.of("connections", connections().list()));
            if (path.equals("/api/db/connections") && method.equals("POST")) {
                ConnectionSettings c = parse(body, ConnectionSettings.class);
                c.id = "";
                return Api.json(connections().save(c));
            }
            if (path.startsWith("/api/db/connections/")) {
                String id = path.substring("/api/db/connections/".length());
                if (method.equals("PUT")) {
                    ConnectionSettings c = parse(body, ConnectionSettings.class);
                    c.id = id;
                    return Api.json(connections().save(c));
                }
                if (method.equals("DELETE")) {
                    connections().delete(id);
                    return Api.json(Map.of("deleted", id));
                }
            }
            if (path.equals("/api/db/test") && method.equals("POST")) {
                ConnectionSettings cs = resolve(parse(body, SourceReq.class));
                return Api.json(new JdbcConnector(cs).test());
            }
            if (path.equals("/api/db/introspect") && method.equals("POST")) return introspect(parse(body, SourceReq.class));
            if (path.startsWith("/api/jobs/")) {
                String id = path.substring("/api/jobs/".length());
                if (method.equals("DELETE")) return Api.json(Map.of("cancelled", jobs.cancel(id)));
                Jobs.Job j = jobs.get(id);
                if (j == null) throw new Api.ApiException(404, "No such job (it may have expired).");
                return Api.json(j.view());
            }
            if (path.startsWith("/api/db/snapshot/")) {
                String id = path.substring("/api/db/snapshot/".length());
                if (method.equals("DELETE")) return Api.json(Map.of("deleted", snapshots.delete(id)));
                return Api.json(snapshotView(snapshot(id), query));
            }
            if (path.equals("/api/db/plan/default") && method.equals("POST")) {
                PlanReq r = parse(body, PlanReq.class);
                return Api.json(planView(snapshot(r.snapshotId), r.plan));
            }
            return null;
        } catch (SourceException e) {
            return sourceError(e);
        }
    }

    /**
     * Database flow generation (D1): the snapshot and the user's plan become the ApiModel. Pass the config from
     * {@link #databaseConfig}, and generate with that same config.
     */
    public ApiModel model(String snapshotId, GenerationPlan plan, GeneratorConfig dbCfg) throws IOException {
        Snapshot snap = snapshot(snapshotId);
        return new DatabaseMapper().map(snap, plan == null ? new GenerationPlan() : plan, dbCfg, snap.title, snap.jdbcUrl);
    }

    /** A copy of the settings for the database flow: proxy mode applies to the spec flow only. */
    public GeneratorConfig databaseConfig(GeneratorConfig cfg) {
        GeneratorConfig dbCfg = Json.MAPPER.convertValue(cfg, GeneratorConfig.class);
        dbCfg.options.serviceMode = "DATABASE";
        return dbCfg;
    }

    // ------------------------------------------------------------------ endpoints

    private List<Map<String, Object>> kinds() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SourceKind k : SourceKind.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", k.name());
            m.put("label", k.label);
            m.put("tier", k.tier);
            m.put("defaultPort", k.defaultPort);
            m.put("example", k.example);
            m.put("fileBased", k.fileBased);
            m.put("controls", k.controls);
            m.put("limits", k.limits);
            m.put("ssl", k == SourceKind.POSTGRESQL || k == SourceKind.MYSQL || k == SourceKind.MARIADB || k == SourceKind.SQLSERVER || k == SourceKind.DB2);
            m.put("readOnlyEnforced", k != SourceKind.SQLSERVER && k != SourceKind.DB2);
            m.put("databaseLabel", k == SourceKind.ORACLE ? "Service name" : "Database");
            m.put("leastPrivilegeSql", io.specforge.source.LeastPrivilege.sql(k));
            m.put("available", Drivers.available(k));
            out.add(m);
        }
        return out;
    }

    private Api.Resp introspect(SourceReq r) throws IOException {
        ConnectionSettings cs = resolve(r);
        TreeSet<String> schemas = new TreeSet<>(r.schemas == null ? List.of() : r.schemas);
        String fingerprint = secrets().fingerprint(Json.write(Map.of("kind", cs.kind, "url", cs.jdbcUrl(), "user", nz(cs.user),
            "password", cs.effectivePassword(), "schemas", schemas)));
        if (!r.refresh && snapshots.get(fingerprint) != null) return Api.json(Map.of("snapshotId", fingerprint, "cached", true));
        String title = cs.name != null && !cs.name.isBlank() ? cs.name : !nz(cs.database).isBlank() ? cs.database : SourceKind.of(cs.kind).label;
        String url = sanitizedUrl(cs);
        Jobs.Job job = jobs.start("introspect", j -> {
            Snapshot snap = new JdbcConnector(cs).introspect(new ArrayList<>(schemas), j);
            snap.id = fingerprint;
            snap.title = title;
            snap.jdbcUrl = url;
            snapshots.put(snap);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("snapshotId", snap.id);
            m.put("tables", snap.tables.size());
            m.put("warnings", snap.warnings.size());
            return m;
        });
        return Api.json(Map.of("jobId", job.id));
    }

    private Map<String, Object> snapshotView(Snapshot s, Map<String, String> query) {
        String q = query.getOrDefault("q", "").toLowerCase(Locale.ROOT);
        int offset = Math.max(0, parseInt(query.get("offset"), 0));
        int limit = Math.min(500, Math.max(1, parseInt(query.get("limit"), 200)));
        List<Map<String, Object>> rows = new ArrayList<>();
        int total = 0;
        for (Snapshot.Table t : s.tables) {
            if (!q.isEmpty() && !t.qualifiedName().toLowerCase(Locale.ROOT).contains(q)) continue;
            total++;
            if (total <= offset || rows.size() >= limit) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("qualifiedName", t.qualifiedName());
            m.put("schema", t.schema);
            m.put("name", t.name);
            m.put("comment", t.comment);
            m.put("columns", t.columns.size());
            m.put("primaryKey", t.primaryKey);
            List<String> fks = new ArrayList<>();
            for (Snapshot.ForeignKey fk : t.foreignKeys) fks.add(String.join(",", fk.columns) + " → " + fk.refQualifiedName());
            m.put("foreignKeys", fks);
            m.put("references", t.foreignKeys.stream().map(Snapshot.ForeignKey::refQualifiedName).distinct().toList());
            m.put("rowEstimate", t.rowEstimate);
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", s.id);
        out.put("title", s.title);
        out.put("engine", s.engine);
        out.put("serverVersion", s.serverVersion);
        out.put("capturedAt", s.capturedAt);
        out.put("schemas", s.schemas);
        out.put("sequences", s.sequences.size());
        out.put("warnings", s.warnings);
        out.put("total", total);
        out.put("offset", offset);
        out.put("tables", rows);
        return out;
    }

    /**
     * The plan and what it leads to, for the Select / Map / API steps: per entity the effective mapping (every snapshot
     * column, also those left out, so they can be mapped), and the suggestion each value would have without the user's
     * decisions (P1, MAP-11); the use-case controllers (OPS-3); warnings.
     */
    private Map<String, Object> planView(Snapshot s, GenerationPlan plan) {
        GenerationPlan p = plan == null ? new GenerationPlan() : plan;
        p.planVersion = 1;
        p.specforgeVersion = App.VERSION;
        p.rulesetVersion = SuggestionEngine.RULESET_VERSION;
        p.source.engine = s.engine;
        p.source.schemas = s.schemas;
        SuggestionEngine.Result r = new SuggestionEngine().build(s, p);
        // the same selection without any other decision: what SpecForge suggests
        GenerationPlan bare = new GenerationPlan();
        bare.select = p.select;
        for (Map.Entry<String, GenerationPlan.EntityDecision> d : p.entities.entrySet()) {
            if (Boolean.TRUE.equals(d.getValue().asEntity)) {
                GenerationPlan.EntityDecision keep = new GenerationPlan.EntityDecision();
                keep.asEntity = true;
                keep.keyColumns = d.getValue().keyColumns;
                bare.entities.put(d.getKey(), keep);
            } else if (!d.getValue().keyColumns.isEmpty()) {
                GenerationPlan.EntityDecision keep = new GenerationPlan.EntityDecision();
                keep.keyColumns = d.getValue().keyColumns;
                bare.entities.put(d.getKey(), keep);
            }
        }
        Map<String, DbEntity> suggested = new LinkedHashMap<>();
        for (DbEntity e : new SuggestionEngine().build(s, bare).entities) suggested.put(e.qualifiedName, e);

        List<Map<String, Object>> entities = new ArrayList<>();
        for (DbEntity e : r.entities) {
            DbEntity sug = suggested.get(e.qualifiedName);
            Snapshot.Table t = s.table(e.qualifiedName);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", e.qualifiedName);
            m.put("className", e.className);
            m.put("path", "/" + e.path);
            m.put("controller", e.controller);
            m.put("controllerName", e.controllerNameOverride.isEmpty() ? e.className + "Controller" : e.controllerNameOverride);
            m.put("readOnly", e.readOnly);
            m.put("readOnlyReason", e.readOnlyReason);
            m.put("joinTable", t != null && SuggestionEngine.isPureJoinTable(t));
            Map<String, Object> id = new LinkedHashMap<>();
            id.put("kind", e.id.kind);
            id.put("strategy", e.id.strategy);
            id.put("javaType", e.id.javaType);
            id.put("columns", e.id.composite() ? e.id.keyFields.stream().map(f -> f.column).toList() : List.of(e.id.field.column));
            id.put("sequence", e.id.sequenceName.isEmpty() ? "" : (e.id.sequenceSchema.isEmpty() ? "" : e.id.sequenceSchema + ".") + e.id.sequenceName);
            id.put("allocationSize", e.id.allocationSize);
            m.put("id", id);
            m.put("columns", columns(s, t, e, sug));
            List<Map<String, Object>> rels = new ArrayList<>();
            for (DbRelation rel : e.relations) {
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("column", rel.column.replace("`", ""));
                rm.put("name", rel.name);
                rm.put("dtoName", rel.dtoField == null ? "" : rel.dtoField.name);
                rm.put("target", rel.targetClass);
                rm.put("mapsId", rel.mapsId);
                rm.put("inverse", rel.inverse);
                DbRelation sr = sug == null ? null : sug.relations.stream().filter(x -> x.column.equals(rel.column)).findFirst().orElse(null);
                rm.put("suggested", sr == null ? Map.of() : Map.of("name", sr.name, "dtoName", sr.dtoField == null ? "" : sr.dtoField.name));
                rels.add(rm);
            }
            m.put("relations", rels);
            List<Map<String, Object>> mms = new ArrayList<>();
            for (DbManyToMany mm : e.manyToMany) mms.add(Map.of("name", mm.name, "target", mm.targetClass,
                "joinTable", (mm.joinSchema.isEmpty() ? "" : mm.joinSchema + ".") + mm.joinTable, "dtoName", mm.dtoName));
            m.put("manyToMany", mms);
            m.put("inverses", e.inverses.stream().map(i -> Map.of("name", i.name, "source", i.sourceClass, "mappedBy", i.mappedBy)).toList());
            m.put("operations", e.ops);
            m.put("serviceOperations", e.serviceOps);
            m.put("filterable", e.filterable);
            m.put("sortable", e.sortable);
            m.put("warnings", e.warnings);
            if (sug != null) {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("className", sug.className);
                sm.put("path", "/" + sug.path);
                sm.put("idStrategy", sug.id.strategy);
                sm.put("sequence", sug.id.sequenceName.isEmpty() ? "" : (sug.id.sequenceSchema.isEmpty() ? "" : sug.id.sequenceSchema + ".") + sug.id.sequenceName);
                m.put("suggested", sm);
            }
            entities.add(m);
        }
        List<Map<String, Object>> useCases = new ArrayList<>();
        for (io.specforge.model.DbModel.DbUseCase uc : r.useCases) {
            List<Map<String, Object>> ops = new ArrayList<>();
            for (io.specforge.model.DbModel.DbUseCaseOp op : uc.operations) {
                Map<String, Object> om = new LinkedHashMap<>();
                om.put("kind", op.kind);
                om.put("method", op.method);
                om.put("http", op.httpMethod());
                om.put("path", (uc.path.isEmpty() ? "" : "/" + uc.path) + op.path);
                om.put("entity", op.entity.className);
                om.put("child", op.child == null ? "" : op.child.className);
                ops.add(om);
            }
            useCases.add(Map.of("name", uc.name, "path", "/" + uc.path, "operations", ops));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan", p);
        out.put("entities", entities);
        out.put("useCases", useCases);
        out.put("joinTables", r.joinTables);
        out.put("excluded", r.excluded);
        out.put("warnings", r.warnings);
        out.put("javaTypes", new TreeSet<>(SuggestionEngine.JAVA_TYPES.keySet()));
        return out;
    }

    /** Every column of the table with its effective mapping, its suggestion, and why it is left out when it is. */
    private static List<Map<String, Object>> columns(Snapshot s, Snapshot.Table t, DbEntity e, DbEntity sug) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (t == null) return out;
        Map<String, DbField> byColumn = new LinkedHashMap<>();
        for (DbField f : e.dtoFields()) byColumn.put(f.column.replace("`", ""), f);
        Map<String, DbRelation> relByColumn = new LinkedHashMap<>();
        for (DbRelation rel : e.relations) relByColumn.put(rel.column.replace("`", ""), rel);
        Map<String, DbField> sugByColumn = new LinkedHashMap<>();
        if (sug != null) {
            for (DbField f : sug.dtoFields()) sugByColumn.put(f.column.replace("`", ""), f);
            for (DbRelation rel : sug.relations) if (rel.dtoField != null) sugByColumn.put(rel.column.replace("`", ""), rel.dtoField);
        }
        Set<String> keys = new java.util.HashSet<>(e.id.composite() ? e.id.keyFields.stream().map(f -> f.column.replace("`", "")).toList()
            : List.of(e.id.field.column.replace("`", "")));
        for (Snapshot.Column c : t.columns) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("column", c.name);
            m.put("sqlType", c.vendorType + (c.size != null && c.size > 0 && c.size < 100_000 ? "(" + c.size + (c.scale != null && c.scale > 0 ? "," + c.scale : "") + ")" : ""));
            m.put("nullable", c.nullable);
            m.put("key", keys.contains(c.name));
            DbField f = byColumn.get(c.name);
            DbRelation rel = relByColumn.get(c.name);
            if (f == null && rel != null) f = rel.dtoField;
            if (f != null) {
                m.put("included", true);
                m.put("name", f.name);
                m.put("javaType", f.javaType);
                m.put("validations", f.validations);
                m.put("filter", e.filterable.contains(f.name));
                m.put("sort", e.sortable.contains(f.name));
                m.put("generated", !f.writable);
                if (rel != null) m.put("relation", rel.name);
            } else if (rel != null) {
                m.put("included", true);
                m.put("name", rel.mapsId);
                m.put("relation", rel.name);
            } else {
                m.put("included", false);
                io.specforge.suggest.TypeRules.Mapping tm = io.specforge.suggest.TypeRules.map(s.engine, c);
                m.put("javaType", tm.supported() ? tm.javaType() : "");
                m.put("reason", tm.supported() ? "left out" : tm.unsupportedReason());
            }
            DbField sf = sugByColumn.get(c.name);
            if (sf != null) m.put("suggested", Map.of("name", sf.name, "javaType", sf.javaType));
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private ConnectionSettings resolve(SourceReq r) throws IOException {
        if (r.connectionId != null && !r.connectionId.isBlank()) return connections().get(r.connectionId);
        if (r.connection == null) throw new Api.ApiException(400, "Choose a saved connection or enter connection settings.");
        SourceKind.of(r.connection.kind);
        return r.connection;
    }

    private Snapshot snapshot(String id) throws IOException {
        Snapshot s = snapshots.get(id);
        if (s == null) throw new Api.ApiException(404, "This database snapshot is no longer available. Connect and read the database again.");
        return s;
    }

    /** The URL without user info or query parameters, which may hold credentials. */
    private static String sanitizedUrl(ConnectionSettings cs) {
        String url = cs.jdbcUrl();
        url = url.replaceAll("//[^/@]*@", "//");
        int q = url.indexOf('?');
        if (q >= 0) url = url.substring(0, q);
        int semi = url.indexOf(';');
        if (semi >= 0 && SourceKind.of(cs.kind) != SourceKind.SQLSERVER) url = url.substring(0, semi);
        return url.replaceAll("(?i);(password|user)=[^;]*", "");
    }

    private SecretStore secrets() throws IOException {
        if (secrets == null) {
            synchronized (this) {
                if (secrets == null) secrets = SecretStore.open(home);
            }
        }
        return secrets;
    }

    private ConnectionStore connections() throws IOException {
        if (connections == null) {
            synchronized (this) {
                if (connections == null) connections = new ConnectionStore(home, secrets());
            }
        }
        return connections;
    }

    private static Api.Resp sourceError(SourceException e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", e.getMessage());
        m.put("code", e.code);
        m.put("message", e.getMessage());
        m.put("hint", e.hint);
        m.put("action", e.action);
        return new Api.Resp(422, "application/json; charset=utf-8", Json.write(m).getBytes(StandardCharsets.UTF_8));
    }

    private static <T> T parse(byte[] body, Class<T> type) {
        try {
            return Json.MAPPER.readValue(body == null || body.length == 0 ? "{}".getBytes(StandardCharsets.UTF_8) : body, type);
        } catch (IOException e) {
            throw new Api.ApiException(400, "Malformed request body.");
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
