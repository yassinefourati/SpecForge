package io.specforge.source.jdbc;

import io.specforge.snapshot.Snapshot;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * SQLite: one file, no schemas. The file is opened read-only by the driver ({@code open_mode} without the create
 * flag, so a wrong path never creates an empty database) and {@code PRAGMA query_only} is set on top. Keys come from
 * the pragma table functions joined to sqlite_master, one query per kind for all tables.
 */
public class SqliteDialect extends CatalogDialect {

    /** SQLITE_OPEN_READONLY; without SQLITE_OPEN_CREATE a missing file is an error. */
    private static final String OPEN_READ_ONLY = "1";

    @Override
    public Properties connectionProperties() {
        Properties p = new Properties();
        p.setProperty("open_mode", OPEN_READ_ONLY);
        p.setProperty("busy_timeout", "5000");
        return p;
    }

    @Override
    public List<String> guards() {
        return List.of("PRAGMA query_only = ON");
    }

    @Override
    public boolean isSystemSchema(String schema) {
        return false;
    }

    @Override
    public boolean isSystemTable(String table) {
        return table.startsWith("sqlite_");
    }

    @Override
    public boolean schemaless() {
        return true;
    }

    @Override
    public boolean readOnlyAfterOpen() {
        return false;
    }

    @Override
    public List<String> schemas(Connection c) {
        return List.of();
    }

    @Override
    public void readPrimaryKeys(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        Keys keys = new Keys();
        query(c, """
            SELECT m.name, p.pk, p.name FROM sqlite_master m JOIN pragma_table_info(m.name) p
            WHERE m.type = 'table' AND p.pk > 0""", List.of(),
            r -> keys.add("", r.getString(1), "PRIMARY", true, r.getInt(2), r.getString(3)));
        keys.applyPrimary(snap);
    }

    @Override
    public void readUniqueKeys(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        Keys keys = new Keys();
        query(c, """
            SELECT m.name, il.name, ii.seqno, ii.name
            FROM sqlite_master m
            JOIN pragma_index_list(m.name) il
            JOIN pragma_index_info(il.name) ii
            WHERE m.type = 'table' AND il."unique" = 1 AND il.origin <> 'pk' AND il.partial = 0 AND ii.name IS NOT NULL""", List.of(),
            r -> keys.add("", r.getString(1), r.getString(2), false, r.getInt(3), r.getString(4)));
        keys.applyUnique(snap);
    }

    @Override
    public void readForeignKeys(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        ForeignKeys fks = new ForeignKeys();
        query(c, """
            SELECT m.name, f.id, f."table", f.seq, f."from", f."to", f.on_update, f.on_delete
            FROM sqlite_master m JOIN pragma_foreign_key_list(m.name) f
            WHERE m.type = 'table'""", List.of(),
            r -> fks.add("", r.getString(1), "fk_" + r.getString(1) + "_" + r.getInt(2), "", r.getString(3), r.getInt(4),
                r.getString(5), r.getString(6), rule(r.getString(7)), rule(r.getString(8))));
        fks.apply(snap);
    }

    @Override
    public void readSequences(Connection c, Snapshot snap, Set<String> schemas) {
        // SQLite has no sequences
    }

    /**
     * An INTEGER PRIMARY KEY column is the rowid: SQLite assigns it on insert, like an identity column. Generated
     * columns are hidden columns 2 (virtual) and 3 (stored) in table_xinfo.
     */
    @Override
    public void readColumnFlags(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        for (Snapshot.Table t : snap.tables) {
            if (t.primaryKey.size() != 1) continue;
            Snapshot.Column key = t.column(t.primaryKey.get(0));
            if (key != null && "INTEGER".equals(key.vendorType.toUpperCase(Locale.ROOT))) key.identity = true;
        }
        query(c, """
            SELECT m.name, x.name FROM sqlite_master m JOIN pragma_table_xinfo(m.name) x
            WHERE m.type = 'table' AND x.hidden IN (2, 3)""", List.of(), r -> {
            Snapshot.Column col = column(snap, "", r.getString(1), r.getString(2));
            if (col != null) col.generated = true;
        });
    }

    @Override
    public void readRowEstimates(Connection c, Snapshot snap, Set<String> schemas) {
        // only sqlite_stat1 after ANALYZE; never COUNT(*)
    }
}
