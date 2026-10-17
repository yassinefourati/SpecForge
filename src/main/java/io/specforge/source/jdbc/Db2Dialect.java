package io.specforge.source.jdbc;

import io.specforge.snapshot.Snapshot;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Db2 for Linux, UNIX and Windows over the SYSCAT views. Db2 offers no per-transaction read-only switch through
 * JDBC beyond the driver hint: SpecForge relies on a least-privilege user, timeouts and rollback (RO-2).
 */
public class Db2Dialect extends CatalogDialect {

    private static final Set<String> SYSTEM = Set.of("NULLID", "SQLJ", "ERRORSCHEMA", "IBM_RTMON", "SYSIBMINTERNAL");

    @Override
    public Properties connectionProperties() {
        Properties p = new Properties();
        p.setProperty("loginTimeout", "10");
        p.setProperty("blockingReadConnectionTimeout", "120");
        p.setProperty("clientProgramName", "SpecForge");
        p.setProperty("retrieveMessagesFromServerOnGetMessage", "true");
        p.setProperty("readOnly", "true");
        return p;
    }

    @Override
    public List<String> guards() {
        return List.of("SET CURRENT LOCK TIMEOUT 5");
    }

    @Override
    public boolean isSystemSchema(String schema) {
        String s = schema.trim().toUpperCase(Locale.ROOT);
        return s.startsWith("SYS") || SYSTEM.contains(s);
    }

    /** Schemas that own at least one table. */
    @Override
    public List<String> defaultSchemas(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();
        try (var s = c.createStatement(); var r = s.executeQuery(
            "SELECT DISTINCT RTRIM(TABSCHEMA) FROM SYSCAT.TABLES WHERE TYPE = 'T' ORDER BY 1")) {
            while (r.next()) if (!isSystemSchema(r.getString(1))) out.add(r.getString(1));
        }
        return out;
    }

    @Override
    public void readPrimaryKeys(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        keys(c, schemas, "'P'", true).applyPrimary(snap);
    }

    @Override
    public void readUniqueKeys(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        keys(c, schemas, "'U'", false).applyUnique(snap);
    }

    private static Keys keys(Connection c, Set<String> schemas, String type, boolean primary) throws SQLException {
        Keys keys = new Keys();
        query(c, """
            SELECT RTRIM(k.TABSCHEMA), k.TABNAME, k.CONSTNAME, k.COLSEQ, k.COLNAME
            FROM SYSCAT.TABCONST t
            JOIN SYSCAT.KEYCOLUSE k ON k.CONSTNAME = t.CONSTNAME AND k.TABSCHEMA = t.TABSCHEMA AND k.TABNAME = t.TABNAME
            WHERE t.TYPE = %s AND RTRIM(t.TABSCHEMA) IN ({schemas})""".formatted(type), schemas,
            r -> keys.add(r.getString(1), r.getString(2), r.getString(3), primary, r.getInt(4), r.getString(5)));
        return keys;
    }

    @Override
    public void readForeignKeys(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        ForeignKeys fks = new ForeignKeys();
        query(c, """
            SELECT RTRIM(r.TABSCHEMA), r.TABNAME, r.CONSTNAME, RTRIM(r.REFTABSCHEMA), r.REFTABNAME, fk.COLSEQ, fk.COLNAME,
                   pk.COLNAME, r.UPDATERULE, r.DELETERULE
            FROM SYSCAT.REFERENCES r
            JOIN SYSCAT.KEYCOLUSE fk ON fk.CONSTNAME = r.CONSTNAME AND fk.TABSCHEMA = r.TABSCHEMA AND fk.TABNAME = r.TABNAME
            JOIN SYSCAT.KEYCOLUSE pk ON pk.CONSTNAME = r.REFKEYNAME AND pk.TABSCHEMA = r.REFTABSCHEMA AND pk.TABNAME = r.REFTABNAME
                 AND pk.COLSEQ = fk.COLSEQ
            WHERE RTRIM(r.TABSCHEMA) IN ({schemas})""", schemas,
            r -> fks.add(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getInt(6),
                r.getString(7), r.getString(8), db2Rule(r.getString(9)), db2Rule(r.getString(10))));
        fks.apply(snap);
    }

    /** A = no action, R = restrict, C = cascade, N = set null. */
    private static String db2Rule(String r) {
        return "A".equals(r == null ? "" : r.trim()) ? "NO ACTION" : rule(r);
    }

    @Override
    public void readSequences(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        query(c, """
            SELECT RTRIM(SEQSCHEMA), SEQNAME, START, INCREMENT FROM SYSCAT.SEQUENCES
            WHERE SEQTYPE = 'S' AND RTRIM(SEQSCHEMA) IN ({schemas}) ORDER BY 1, 2""", schemas, r -> {
            Snapshot.Sequence s = new Snapshot.Sequence();
            s.schema = r.getString(1);
            s.name = r.getString(2);
            s.start = r.getLong(3);
            s.increment = r.getLong(4);
            snap.sequences.add(s);
        });
    }

    @Override
    public void readColumnFlags(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        query(c, """
            SELECT RTRIM(TABSCHEMA), TABNAME, COLNAME, IDENTITY, GENERATED, TEXT
            FROM SYSCAT.COLUMNS
            WHERE RTRIM(TABSCHEMA) IN ({schemas}) AND (IDENTITY = 'Y' OR GENERATED <> ' ')""", schemas, r -> {
            Snapshot.Column col = column(snap, r.getString(1), r.getString(2), r.getString(3));
            if (col == null) return;
            if ("Y".equals(r.getString(4))) col.identity = true;
            else if (r.getString(6) != null) col.generated = true; // GENERATED ALWAYS AS (expression)
        });
    }

    @Override
    public void readRowEstimates(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        query(c, "SELECT RTRIM(TABSCHEMA), TABNAME, CARD FROM SYSCAT.TABLES WHERE TYPE = 'T' AND RTRIM(TABSCHEMA) IN ({schemas})", schemas, r -> {
            Snapshot.Table t = table(snap, r.getString(1), r.getString(2));
            long n = r.getLong(3);
            if (t != null && n >= 0) t.rowEstimate = n; // -1: statistics never collected
        });
    }
}
