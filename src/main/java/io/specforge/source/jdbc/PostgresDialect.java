package io.specforge.source.jdbc;

import io.specforge.snapshot.Snapshot;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** PostgreSQL: catalog queries over pg_catalog, one per object kind for all selected schemas. */
public class PostgresDialect implements Dialect {

    private static final String SEP = "\u001f";

    @Override
    public Properties connectionProperties() {
        Properties p = new Properties();
        p.setProperty("connectTimeout", "10");
        p.setProperty("socketTimeout", "120");
        p.setProperty("loginTimeout", "10");
        p.setProperty("ApplicationName", "SpecForge (read-only)");
        p.setProperty("readOnly", "true");
        return p;
    }

    @Override
    public List<String> guards() {
        return List.of("SET TRANSACTION READ ONLY", "SET LOCAL statement_timeout = '60s'", "SET LOCAL lock_timeout = '5s'");
    }

    @Override
    public boolean isSystemSchema(String s) {
        return s.equals("pg_catalog") || s.equals("information_schema") || s.startsWith("pg_toast") || s.startsWith("pg_temp_");
    }

    @Override
    public void readUniqueKeys(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        String sql = """
            SELECT n.nspname, t.relname, ic.relname,
                   string_agg(a.attname, chr(31) ORDER BY k.ord)
            FROM pg_index i
            JOIN pg_class ic ON ic.oid = i.indexrelid
            JOIN pg_class t ON t.oid = i.indrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord)
            JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
            WHERE i.indisunique AND NOT i.indisprimary AND i.indpred IS NULL AND i.indexprs IS NULL
              AND n.nspname = ANY(?)
            GROUP BY n.nspname, t.relname, ic.relname
            ORDER BY n.nspname, t.relname, ic.relname""";
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setArray(1, array(c, schemas));
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    Snapshot.Table t = snap.table(r.getString(1) + "." + r.getString(2));
                    if (t == null) continue;
                    Snapshot.UniqueKey u = new Snapshot.UniqueKey();
                    u.name = r.getString(3);
                    u.columns = List.of(r.getString(4).split(SEP));
                    t.uniqueKeys.add(u);
                }
            }
        }
    }

    @Override
    public void readSequences(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        String sql = """
            SELECT n.nspname, s.relname, q.seqstart, q.seqincrement, tn.nspname, t.relname, a.attname
            FROM pg_class s
            JOIN pg_namespace n ON n.oid = s.relnamespace
            JOIN pg_sequence q ON q.seqrelid = s.oid
            LEFT JOIN pg_depend d ON d.objid = s.oid AND d.classid = 'pg_class'::regclass
                 AND d.refclassid = 'pg_class'::regclass AND d.deptype IN ('a', 'i')
            LEFT JOIN pg_class t ON t.oid = d.refobjid
            LEFT JOIN pg_namespace tn ON tn.oid = t.relnamespace
            LEFT JOIN pg_attribute a ON a.attrelid = d.refobjid AND a.attnum = d.refobjsubid
            WHERE s.relkind = 'S' AND n.nspname = ANY(?)
            ORDER BY n.nspname, s.relname""";
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setArray(1, array(c, schemas));
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    Snapshot.Sequence s = new Snapshot.Sequence();
                    s.schema = r.getString(1);
                    s.name = r.getString(2);
                    s.start = r.getLong(3);
                    s.increment = r.getLong(4);
                    s.ownedBySchema = nz(r.getString(5));
                    s.ownedByTable = nz(r.getString(6));
                    s.ownedByColumn = nz(r.getString(7));
                    snap.sequences.add(s);
                }
            }
        }
    }

    @Override
    public void readColumnFlags(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        String sql = """
            SELECT n.nspname, t.relname, a.attname, a.attidentity, a.attgenerated
            FROM pg_attribute a
            JOIN pg_class t ON t.oid = a.attrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            WHERE n.nspname = ANY(?) AND a.attnum > 0 AND NOT a.attisdropped
              AND (a.attidentity <> '' OR a.attgenerated <> '')""";
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setArray(1, array(c, schemas));
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    Snapshot.Table t = snap.table(r.getString(1) + "." + r.getString(2));
                    Snapshot.Column col = t == null ? null : t.column(r.getString(3));
                    if (col == null) continue;
                    if (!nz(r.getString(4)).isEmpty()) col.identity = true;
                    if (!nz(r.getString(5)).isEmpty()) col.generated = true;
                }
            }
        }
    }

    @Override
    public void readRowEstimates(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        String sql = """
            SELECT n.nspname, t.relname, t.reltuples::bigint
            FROM pg_class t JOIN pg_namespace n ON n.oid = t.relnamespace
            WHERE t.relkind IN ('r', 'p') AND n.nspname = ANY(?)""";
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setArray(1, array(c, schemas));
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    Snapshot.Table t = snap.table(r.getString(1) + "." + r.getString(2));
                    long n = r.getLong(3);
                    if (t != null) t.rowEstimate = n >= 0 ? n : null; // -1: never analysed
                }
            }
        }
    }

    private static Array array(Connection c, Set<String> schemas) throws SQLException {
        return c.createArrayOf("text", schemas.toArray());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
