package io.specforge.ddl;

import io.specforge.snapshot.Snapshot;
import io.specforge.source.SourceKind;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MIG-2: the schema of the selected tables as DDL for the engine they come from, so a fresh database (a test
 * container, a new developer's laptop) gets the same tables as the real one. Same engine in and out, so column
 * types and defaults are the source's own, written back as it reported them; only what cannot be reproduced from
 * the snapshot is refused, with the reason (MIG-3): computed columns, user-defined types, unknown types.
 * <p>
 * Supported: PostgreSQL, MySQL, MariaDB, H2. Tables first (keys, unique keys), foreign keys last, so the order of
 * the tables never matters. Names are quoted the way the generated mappings quote them.
 */
public final class DdlWriter {

    /** The engines this writer supports; the others are offered the Flyway baseline only. */
    public static final Set<SourceKind> SUPPORTED = Set.of(SourceKind.POSTGRESQL, SourceKind.MYSQL, SourceKind.MARIADB, SourceKind.H2);

    /** The DDL, or why it cannot be written (then {@link #sql} is empty). */
    public static final class Result {
        public String sql = "";
        public final List<String> problems = new ArrayList<>();

        public boolean ok() {
            return problems.isEmpty();
        }
    }

    private static final Set<String> PG_TYPES = Set.of("int2", "int4", "int8", "smallint", "integer", "bigint", "numeric", "decimal",
        "float4", "float8", "real", "double precision", "bool", "boolean", "varchar", "bpchar", "char", "text", "date", "time", "timetz",
        "timestamp", "timestamptz", "interval", "uuid", "json", "jsonb", "bytea", "money", "inet", "cidr", "macaddr", "xml",
        "serial", "bigserial", "smallserial", "bit", "varbit");
    private static final Set<String> SIZED_PG = Set.of("varchar", "bpchar", "char", "bit", "varbit");
    private static final Set<String> MYSQL_SIZED = Set.of("VARCHAR", "CHAR", "VARBINARY", "BINARY", "BIT");
    private static final Set<String> MYSQL_DECIMAL = Set.of("DECIMAL", "NUMERIC");
    private static final Set<String> H2_SIZED = Set.of("CHARACTER VARYING", "CHARACTER", "BINARY VARYING", "BINARY", "VARCHAR", "CHAR", "VARCHAR_IGNORECASE");
    private static final Pattern MYSQL_FUNCTION = Pattern.compile("(?i)(current_timestamp|now|localtimestamp|current_date|curdate|uuid)\\s*(\\(\\s*\\d*\\s*\\))?");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

    private DdlWriter() {}

    /**
     * @param tables the tables to create (entities and join tables), qualified names as in the snapshot
     */
    public static Result write(Snapshot snap, Collection<String> tables) {
        Result r = new Result();
        SourceKind kind = SourceKind.of(snap.engine);
        if (!SUPPORTED.contains(kind)) {
            r.problems.add(kind.label + " is not supported yet (PostgreSQL, MySQL, MariaDB and H2 are)");
            return r;
        }
        List<Snapshot.Table> selected = new ArrayList<>();
        for (String q : tables) {
            Snapshot.Table t = snap.table(q);
            if (t != null) selected.add(t);
        }
        StringBuilder sb = new StringBuilder();
        boolean qualified = kind == SourceKind.POSTGRESQL || kind == SourceKind.H2;
        if (qualified) {
            Set<String> schemas = new LinkedHashSet<>();
            for (Snapshot.Table t : selected) if (!t.schema.isEmpty()) schemas.add(t.schema);
            for (String s : schemas) sb.append("create schema if not exists ").append(q(kind, s)).append(";\n");
            if (!schemas.isEmpty()) sb.append('\n');
            Set<String> inSchemas = new LinkedHashSet<>(schemas);
            for (Snapshot.Sequence s : snap.sequences) {
                if (!inSchemas.contains(s.schema) || ownedByIdentity(snap, s)) continue;
                sb.append("create sequence if not exists ").append(name(kind, s.schema, s.name))
                    .append(" start with ").append(s.start).append(" increment by ").append(s.increment).append(";\n");
            }
            if (!snap.sequences.isEmpty()) sb.append('\n');
        }
        for (Snapshot.Table t : selected) {
            List<String> parts = new ArrayList<>();
            for (Snapshot.Column c : t.columns) {
                String def = column(kind, t, c, r);
                if (def != null) parts.add(def);
            }
            if (!t.primaryKey.isEmpty()) parts.add("primary key (" + list(kind, t.primaryKey) + ")");
            for (Snapshot.UniqueKey u : t.uniqueKeys) parts.add("unique (" + list(kind, u.columns) + ")");
            sb.append("create table ").append(name(kind, qualified ? t.schema : "", t.name)).append(" (\n    ")
                .append(String.join(",\n    ", parts)).append("\n);\n\n");
        }
        Set<String> created = new LinkedHashSet<>();
        for (Snapshot.Table t : selected) created.add(t.qualifiedName());
        for (Snapshot.Table t : selected) {
            for (Snapshot.ForeignKey fk : t.foreignKeys) {
                if (!created.contains(fk.refQualifiedName())) {
                    r.problems.add("foreign key " + t.qualifiedName() + " → " + fk.refQualifiedName() + " refers to a table that is not selected");
                    continue;
                }
                sb.append("alter table ").append(name(kind, qualified ? t.schema : "", t.name)).append(" add foreign key (")
                    .append(list(kind, fk.columns)).append(") references ").append(name(kind, qualified ? fk.refSchema : "", fk.refTable))
                    .append(" (").append(list(kind, fk.refColumns)).append(")").append(action("delete", fk.onDelete))
                    .append(action("update", fk.onUpdate)).append(";\n");
            }
        }
        if (r.ok()) r.sql = sb.toString().stripTrailing() + "\n";
        return r;
    }

    /** An identity column brings its own sequence; creating it as well would clash. */
    private static boolean ownedByIdentity(Snapshot snap, Snapshot.Sequence s) {
        if (s.ownedByTable == null || s.ownedByTable.isEmpty()) return false;
        Snapshot.Table t = snap.table((s.ownedBySchema == null || s.ownedBySchema.isEmpty() ? "" : s.ownedBySchema + ".") + s.ownedByTable);
        if (t == null) return false;
        Snapshot.Column c = t.column(s.ownedByColumn);
        return c != null && c.identity;
    }

    private static String action(String on, String rule) {
        String a = rule == null ? "" : rule.trim().toUpperCase(Locale.ROOT);
        return a.isEmpty() || a.equals("NO ACTION") || a.equals("RESTRICT") ? "" : " on " + on + " " + a.toLowerCase(Locale.ROOT);
    }

    private static String column(SourceKind kind, Snapshot.Table t, Snapshot.Column c, Result r) {
        String where = t.qualifiedName() + "." + c.name;
        // MySQL reports DEFAULT CURRENT_TIMESTAMP (and other expression defaults) as generated: that is a default
        boolean mysqlDefault = (kind == SourceKind.MYSQL || kind == SourceKind.MARIADB) && c.defaultValue != null && !c.defaultValue.isBlank();
        if (c.generated && !mysqlDefault) {
            r.problems.add(where + " is computed by the database; its expression is not part of the snapshot");
            return null;
        }
        String type = switch (kind) {
            case POSTGRESQL -> pgType(c, where, r);
            case MYSQL, MARIADB -> mysqlType(c);
            default -> h2Type(c);
        };
        if (type == null) return null;
        StringBuilder sb = new StringBuilder(q(kind, c.name)).append(' ').append(type);
        boolean identity = c.identity || (c.autoIncrement && kind != SourceKind.POSTGRESQL && kind != SourceKind.H2);
        if (identity) {
            sb.append(switch (kind) {
                case POSTGRESQL, H2 -> " generated by default as identity";
                default -> " auto_increment";
            });
        } else if (c.defaultValue != null && !c.defaultValue.isBlank()) {
            String d = defaultOf(kind, c);
            if (d != null) sb.append(" default ").append(d);
        }
        if (!c.nullable) sb.append(" not null");
        return sb.toString();
    }

    private static String pgType(Snapshot.Column c, String where, Result r) {
        String v = c.vendorType.toLowerCase(Locale.ROOT);
        if (v.startsWith("_")) {
            String element = v.substring(1);
            if (!PG_TYPES.contains(element)) {
                r.problems.add(where + " has the type " + c.vendorType + ", which the snapshot cannot recreate");
                return null;
            }
            return element + "[]";
        }
        if (!PG_TYPES.contains(v)) {
            r.problems.add(where + " has the user-defined or unknown type " + c.vendorType + " (enums, domains and extensions are not part of the snapshot)");
            return null;
        }
        // the sequence behind a serial default is created on its own; the column keeps the source's default
        v = switch (v) {
            case "serial" -> "int4";
            case "bigserial" -> "int8";
            case "smallserial" -> "int2";
            default -> v;
        };
        if (SIZED_PG.contains(v) && c.size != null && c.size > 0 && c.size < 10_485_760) return v + "(" + c.size + ")";
        if ((v.equals("numeric") || v.equals("decimal")) && c.size != null && c.size > 0 && c.size < 1000)
            return v + "(" + c.size + ", " + (c.scale == null ? 0 : c.scale) + ")";
        return v;
    }

    private static String mysqlType(Snapshot.Column c) {
        String v = c.vendorType.toUpperCase(Locale.ROOT).trim();
        boolean unsigned = v.endsWith(" UNSIGNED");
        String base = unsigned ? v.substring(0, v.length() - " UNSIGNED".length()) : v;
        String sized = base;
        if (MYSQL_SIZED.contains(base) && c.size != null && c.size > 0) sized = base + "(" + c.size + ")";
        else if (MYSQL_DECIMAL.contains(base) && c.size != null && c.size > 0) sized = base + "(" + c.size + ", " + (c.scale == null ? 0 : c.scale) + ")";
        else if (base.equals("TINYINT") && c.size != null && c.size == 1) sized = "TINYINT(1)";
        else if ((base.equals("DATETIME") || base.equals("TIMESTAMP") || base.equals("TIME")) && c.scale != null && c.scale > 0) sized = base + "(" + c.scale + ")";
        return (sized + (unsigned ? " UNSIGNED" : "")).toLowerCase(Locale.ROOT);
    }

    private static String h2Type(Snapshot.Column c) {
        String v = c.vendorType.toUpperCase(Locale.ROOT).trim();
        if (H2_SIZED.contains(v) && c.size != null && c.size > 0 && c.size < 1_000_000_000) return v + "(" + c.size + ")";
        if ((v.equals("NUMERIC") || v.equals("DECIMAL")) && c.size != null && c.size > 0 && c.size < 100_000)
            return v + "(" + c.size + ", " + (c.scale == null ? 0 : c.scale) + ")";
        return v;
    }

    /** The source's default, re-quoted where its catalog reports a text default without quotes (MySQL). */
    private static String defaultOf(SourceKind kind, Snapshot.Column c) {
        String d = c.defaultValue.trim();
        if (kind != SourceKind.MYSQL && kind != SourceKind.MARIADB) return d;
        if (d.equalsIgnoreCase("NULL")) return null;
        if (d.startsWith("'") || d.startsWith("(") || NUMBER.matcher(d).matches() || MYSQL_FUNCTION.matcher(d).matches()
            || d.equalsIgnoreCase("TRUE") || d.equalsIgnoreCase("FALSE") || d.startsWith("b'")) return d;
        return "'" + d.replace("'", "''") + "'";
    }

    private static String list(SourceKind kind, List<String> cols) {
        List<String> out = new ArrayList<>();
        for (String c : cols) out.add(q(kind, c));
        return String.join(", ", out);
    }

    private static String name(SourceKind kind, String schema, String table) {
        return schema == null || schema.isEmpty() ? q(kind, table) : q(kind, schema) + "." + q(kind, table);
    }

    /** Every name quoted, so its case and spelling are kept (the generated mappings quote every name too). */
    static String q(SourceKind kind, String name) {
        return switch (kind) {
            case MYSQL, MARIADB -> "`" + name.replace("`", "``") + "`";
            default -> "\"" + name.replace("\"", "\"\"") + "\"";
        };
    }
}
