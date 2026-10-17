package io.specforge.source.jdbc;

import io.specforge.snapshot.Snapshot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Base for dialects that read the engine's own catalog: one parameterised query per object kind for every selected
 * schema. A query names the schema list as {@code {schemas}}; it becomes {@code ?, ?, …} and is bound as values,
 * never concatenated.
 */
abstract class CatalogDialect implements Dialect {

    @FunctionalInterface
    interface Row {
        void accept(ResultSet r) throws SQLException;
    }

    static void query(Connection c, String sql, Collection<String> schemas, Row row) throws SQLException {
        List<String> values = schemas.isEmpty() ? List.of("") : new ArrayList<>(schemas);
        String in = String.join(", ", Collections.nCopies(values.size(), "?"));
        int uses = (sql.length() - sql.replace("{schemas}", "").length()) / "{schemas}".length();
        try (PreparedStatement p = c.prepareStatement(sql.replace("{schemas}", in))) {
            p.setQueryTimeout(120);
            int i = 1;
            for (int u = 0; u < uses; u++) for (String v : values) p.setString(i++, v);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) row.accept(r);
            }
        }
    }

    static Snapshot.Table table(Snapshot snap, String schema, String table) {
        return snap.table(JdbcMetadata.qn(schema, table == null ? null : table.trim()));
    }

    static Snapshot.Column column(Snapshot snap, String schema, String table, String column) {
        Snapshot.Table t = table(snap, schema, table);
        return t == null || column == null ? null : t.column(column.trim());
    }

    /** Collects ordered key columns per (table, constraint) and adds them as primary or unique keys. */
    static final class Keys {
        private final Map<String, TreeMap<Integer, String>> columns = new LinkedHashMap<>();
        private final Map<String, String[]> owner = new LinkedHashMap<>();

        void add(String schema, String table, String constraint, boolean primary, int position, String column) {
            String key = (primary ? "P" : "U") + "\u001f" + JdbcMetadata.qn(schema, table.trim()) + "\u001f" + constraint;
            columns.computeIfAbsent(key, k -> new TreeMap<>()).put(position, column.trim());
            owner.putIfAbsent(key, new String[] {schema, table.trim(), constraint});
        }

        void applyPrimary(Snapshot snap) {
            for (Map.Entry<String, TreeMap<Integer, String>> e : columns.entrySet()) {
                if (!e.getKey().startsWith("P")) continue;
                String[] o = owner.get(e.getKey());
                Snapshot.Table t = table(snap, o[0], o[1]);
                if (t != null) t.primaryKey = new ArrayList<>(e.getValue().values());
            }
        }

        void applyUnique(Snapshot snap) {
            for (Map.Entry<String, TreeMap<Integer, String>> e : columns.entrySet()) {
                if (!e.getKey().startsWith("U")) continue;
                String[] o = owner.get(e.getKey());
                Snapshot.Table t = table(snap, o[0], o[1]);
                if (t == null) continue;
                List<String> cols = new ArrayList<>(e.getValue().values());
                // a unique index that repeats the key or another unique key adds nothing
                if (cols.equals(t.primaryKey) || t.uniqueKeys.stream().anyMatch(u -> u.columns.equals(cols))) continue;
                Snapshot.UniqueKey u = new Snapshot.UniqueKey();
                u.name = o[2];
                u.columns = cols;
                t.uniqueKeys.add(u);
            }
        }
    }

    /** Collects foreign-key column pairs per (table, constraint). */
    static final class ForeignKeys {
        private final Map<String, Snapshot.ForeignKey> fks = new LinkedHashMap<>();
        private final Map<String, TreeMap<Integer, String[]>> pairs = new LinkedHashMap<>();
        private final Map<String, String[]> owner = new LinkedHashMap<>();

        void add(String schema, String table, String name, String refSchema, String refTable, int position, String column,
                 String refColumn, String onUpdate, String onDelete) {
            String key = JdbcMetadata.qn(schema, table.trim()) + "\u001f" + name;
            Snapshot.ForeignKey fk = fks.computeIfAbsent(key, k -> new Snapshot.ForeignKey());
            fk.name = name;
            fk.refSchema = refSchema == null ? "" : refSchema.trim();
            fk.refTable = refTable.trim();
            fk.onUpdate = onUpdate;
            fk.onDelete = onDelete;
            pairs.computeIfAbsent(key, k -> new TreeMap<>()).put(position, new String[] {column.trim(), refColumn == null ? null : refColumn.trim()});
            owner.putIfAbsent(key, new String[] {schema, table.trim()});
        }

        void apply(Snapshot snap) {
            for (Map.Entry<String, Snapshot.ForeignKey> e : fks.entrySet()) {
                String[] o = owner.get(e.getKey());
                Snapshot.Table t = table(snap, o[0], o[1]);
                if (t == null) continue;
                Snapshot.ForeignKey fk = e.getValue();
                Snapshot.Table target = snap.table(fk.refQualifiedName());
                List<String[]> cols = new ArrayList<>(pairs.get(e.getKey()).values());
                for (int i = 0; i < cols.size(); i++) {
                    fk.columns.add(cols.get(i)[0]);
                    // SQLite may omit the referenced columns: then they are the target's primary key
                    String ref = cols.get(i)[1];
                    if (ref == null && target != null && i < target.primaryKey.size()) ref = target.primaryKey.get(i);
                    fk.refColumns.add(ref);
                }
                t.foreignKeys.add(fk);
            }
        }
    }

    /** Engine words such as "SET_NULL" or "N" → the snapshot's rule names. */
    static String rule(String r) {
        if (r == null) return "NO ACTION";
        return switch (r.trim().toUpperCase(java.util.Locale.ROOT).replace('_', ' ')) {
            case "CASCADE", "C" -> "CASCADE";
            case "SET NULL", "N" -> "SET NULL";
            case "SET DEFAULT" -> "SET DEFAULT";
            case "RESTRICT", "R" -> "RESTRICT";
            default -> "NO ACTION";
        };
    }
}
