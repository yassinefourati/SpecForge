package io.specforge.source.jdbc;

import io.specforge.snapshot.Snapshot;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Keys through {@code DatabaseMetaData}. One call per schema with a null table name where the driver accepts it;
 * drivers that insist on a table name (MySQL, SQL Server, SQLite) get one call per table instead, which is slower
 * but still correct. Engine dialects replace these with catalog queries where that matters.
 */
final class JdbcMetadata {

    private JdbcMetadata() {}

    static void primaryKeys(DatabaseMetaData md, String schema, Snapshot snap) throws SQLException {
        Map<String, TreeMap<Integer, String>> keys = new LinkedHashMap<>();
        try {
            readPrimaryKeys(md, schema, null, keys);
        } catch (SQLException tableRequired) {
            keys.clear();
            for (Snapshot.Table t : tablesOf(snap, schema)) readPrimaryKeys(md, schema, t.name, keys);
        }
        for (Map.Entry<String, TreeMap<Integer, String>> e : keys.entrySet()) {
            Snapshot.Table t = snap.table(e.getKey());
            if (t != null) t.primaryKey = new ArrayList<>(e.getValue().values());
        }
    }

    private static void readPrimaryKeys(DatabaseMetaData md, String schema, String table, Map<String, TreeMap<Integer, String>> keys) throws SQLException {
        try (ResultSet r = md.getPrimaryKeys(null, pattern(schema), table)) {
            while (r.next()) {
                String t = qn(r.getString("TABLE_SCHEM"), r.getString("TABLE_NAME"));
                keys.computeIfAbsent(t, k -> new TreeMap<>()).put(r.getInt("KEY_SEQ"), r.getString("COLUMN_NAME"));
            }
        }
    }

    static void foreignKeys(DatabaseMetaData md, String schema, Snapshot snap) throws SQLException {
        Map<String, Snapshot.ForeignKey> fks = new LinkedHashMap<>();
        Map<String, TreeMap<Integer, String[]>> cols = new LinkedHashMap<>();
        try {
            readForeignKeys(md, schema, null, fks, cols);
        } catch (SQLException tableRequired) {
            fks.clear();
            cols.clear();
            for (Snapshot.Table t : tablesOf(snap, schema)) readForeignKeys(md, schema, t.name, fks, cols);
        }
        for (Map.Entry<String, Snapshot.ForeignKey> e : fks.entrySet()) {
            Snapshot.ForeignKey fk = e.getValue();
            for (String[] pair : cols.get(e.getKey()).values()) {
                fk.columns.add(pair[0]);
                fk.refColumns.add(pair[1]);
            }
            Snapshot.Table t = snap.table(e.getKey().substring(0, e.getKey().indexOf('#')));
            if (t != null) t.foreignKeys.add(fk);
        }
    }

    private static void readForeignKeys(DatabaseMetaData md, String schema, String table, Map<String, Snapshot.ForeignKey> fks,
                                        Map<String, TreeMap<Integer, String[]>> cols) throws SQLException {
        try (ResultSet r = md.getImportedKeys(null, pattern(schema), table)) {
            while (r.next()) {
                String fkTable = qn(r.getString("FKTABLE_SCHEM"), r.getString("FKTABLE_NAME"));
                String name = nz(r.getString("FK_NAME"));
                // unnamed keys (SQLite) are told apart by their target table
                String key = fkTable + "#" + (name.isEmpty() ? "->" + r.getString("PKTABLE_NAME") : name);
                Snapshot.ForeignKey fk = fks.computeIfAbsent(key, k -> new Snapshot.ForeignKey());
                fk.name = name;
                fk.refSchema = nz(r.getString("PKTABLE_SCHEM"));
                fk.refTable = r.getString("PKTABLE_NAME");
                fk.onUpdate = rule(r.getShort("UPDATE_RULE"));
                fk.onDelete = rule(r.getShort("DELETE_RULE"));
                cols.computeIfAbsent(key, k -> new TreeMap<>()).put((int) r.getShort("KEY_SEQ"),
                    new String[] {r.getString("FKCOLUMN_NAME"), r.getString("PKCOLUMN_NAME")});
            }
        }
    }

    static String rule(short r) {
        return switch (r) {
            case DatabaseMetaData.importedKeyCascade -> "CASCADE";
            case DatabaseMetaData.importedKeySetNull -> "SET NULL";
            case DatabaseMetaData.importedKeySetDefault -> "SET DEFAULT";
            case DatabaseMetaData.importedKeyRestrict -> "RESTRICT";
            default -> "NO ACTION";
        };
    }

    private static List<Snapshot.Table> tablesOf(Snapshot snap, String schema) {
        List<Snapshot.Table> out = new ArrayList<>();
        for (Snapshot.Table t : snap.tables) if (t.schema.equals(schema)) out.add(t);
        return out;
    }

    /** "" (no schemas) becomes a null pattern. */
    static String pattern(String schema) {
        return schema == null || schema.isEmpty() ? null : schema;
    }

    static String qn(String schema, String table) {
        return schema == null || schema.isBlank() ? table : schema.trim() + "." + table;
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }
}
