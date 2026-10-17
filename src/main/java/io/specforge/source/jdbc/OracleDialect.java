package io.specforge.source.jdbc;

import io.specforge.snapshot.Snapshot;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Oracle over the ALL_* dictionary views, so a reader with SELECT on another schema's tables sees them without DBA
 * rights. Schemas are users that Oracle does not maintain itself and that own tables. Identifiers stay uppercase as
 * Oracle stores them (INT-8).
 */
public class OracleDialect extends CatalogDialect {

    @Override
    public Properties connectionProperties() {
        Properties p = new Properties();
        p.setProperty("oracle.net.CONNECT_TIMEOUT", "10000");
        p.setProperty("oracle.jdbc.ReadTimeout", "120000");
        // no parentheses: the value travels inside the TNS connect descriptor
        p.setProperty("v$session.program", "SpecForge");
        // comments are read in bulk below; asking the driver for REMARKS makes every getColumns call slow
        p.setProperty("remarksReporting", "false");
        p.setProperty("includeSynonyms", "false");
        return p;
    }

    @Override
    public List<String> guards() {
        return List.of("SET TRANSACTION READ ONLY");
    }

    @Override
    public boolean isSystemSchema(String schema) {
        return false; // decided by ORACLE_MAINTAINED in schemas()
    }

    @Override
    public boolean isSystemTable(String table) {
        return table.startsWith("BIN$") || table.startsWith("MLOG$_") || table.startsWith("RUPD$_") || table.startsWith("DR$")
            || table.startsWith("SYS_IOT_") || table.startsWith("ISEQ$$_");
    }

    @Override
    public List<String> schemas(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();
        try (var s = c.createStatement(); var r = s.executeQuery("""
            SELECT u.USERNAME FROM ALL_USERS u
            WHERE u.ORACLE_MAINTAINED = 'N' AND EXISTS (SELECT 1 FROM ALL_TABLES t WHERE t.OWNER = u.USERNAME)
            ORDER BY u.USERNAME""")) {
            while (r.next()) out.add(r.getString(1));
        }
        return out;
    }

    /** The connected user's own schema when it owns tables, else every listed schema. */
    @Override
    public List<String> defaultSchemas(Connection c) throws SQLException {
        List<String> all = schemas(c);
        String own = c.getMetaData().getUserName();
        return own != null && all.contains(own) ? List.of(own) : all;
    }

    @Override
    public void readPrimaryKeys(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        keys(c, schemas, "'P'", true).applyPrimary(snap);
    }

    @Override
    public void readUniqueKeys(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        Keys keys = keys(c, schemas, "'U'", false);
        // unique indexes without a constraint
        query(c, """
            SELECT i.TABLE_OWNER, i.TABLE_NAME, i.INDEX_NAME, ic.COLUMN_POSITION, ic.COLUMN_NAME
            FROM ALL_INDEXES i
            JOIN ALL_IND_COLUMNS ic ON ic.INDEX_OWNER = i.OWNER AND ic.INDEX_NAME = i.INDEX_NAME
            WHERE i.UNIQUENESS = 'UNIQUE' AND i.INDEX_TYPE = 'NORMAL' AND i.TABLE_OWNER IN ({schemas})
              AND NOT EXISTS (SELECT 1 FROM ALL_CONSTRAINTS k WHERE k.OWNER = i.TABLE_OWNER AND k.INDEX_NAME = i.INDEX_NAME
                              AND k.CONSTRAINT_TYPE IN ('P', 'U'))""", schemas,
            r -> keys.add(r.getString(1), r.getString(2), r.getString(3), false, r.getInt(4), r.getString(5)));
        keys.applyUnique(snap);
    }

    private static Keys keys(Connection c, Set<String> schemas, String type, boolean primary) throws SQLException {
        Keys keys = new Keys();
        query(c, """
            SELECT k.OWNER, k.TABLE_NAME, k.CONSTRAINT_NAME, kc.POSITION, kc.COLUMN_NAME
            FROM ALL_CONSTRAINTS k
            JOIN ALL_CONS_COLUMNS kc ON kc.OWNER = k.OWNER AND kc.CONSTRAINT_NAME = k.CONSTRAINT_NAME
            WHERE k.CONSTRAINT_TYPE = %s AND k.OWNER IN ({schemas}) AND k.TABLE_NAME NOT LIKE 'BIN$%%'""".formatted(type), schemas,
            r -> keys.add(r.getString(1), r.getString(2), r.getString(3), primary, r.getInt(4), r.getString(5)));
        return keys;
    }

    @Override
    public void readForeignKeys(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        ForeignKeys fks = new ForeignKeys();
        query(c, """
            SELECT f.OWNER, f.TABLE_NAME, f.CONSTRAINT_NAME, r.OWNER, r.TABLE_NAME, fc.POSITION, fc.COLUMN_NAME, rc.COLUMN_NAME,
                   f.DELETE_RULE
            FROM ALL_CONSTRAINTS f
            JOIN ALL_CONS_COLUMNS fc ON fc.OWNER = f.OWNER AND fc.CONSTRAINT_NAME = f.CONSTRAINT_NAME
            JOIN ALL_CONSTRAINTS r ON r.OWNER = f.R_OWNER AND r.CONSTRAINT_NAME = f.R_CONSTRAINT_NAME
            JOIN ALL_CONS_COLUMNS rc ON rc.OWNER = r.OWNER AND rc.CONSTRAINT_NAME = r.CONSTRAINT_NAME AND rc.POSITION = fc.POSITION
            WHERE f.CONSTRAINT_TYPE = 'R' AND f.OWNER IN ({schemas})""", schemas,
            r -> fks.add(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getInt(6),
                r.getString(7), r.getString(8), "NO ACTION", rule(r.getString(9))));
        fks.apply(snap);
    }

    @Override
    public void readSequences(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        query(c, """
            SELECT SEQUENCE_OWNER, SEQUENCE_NAME, MIN_VALUE, INCREMENT_BY
            FROM ALL_SEQUENCES
            WHERE SEQUENCE_OWNER IN ({schemas}) AND SEQUENCE_NAME NOT LIKE 'ISEQ$$%'
            ORDER BY SEQUENCE_OWNER, SEQUENCE_NAME""", schemas, r -> {
            Snapshot.Sequence s = new Snapshot.Sequence();
            s.schema = r.getString(1);
            s.name = r.getString(2);
            // MIN_VALUE can exceed a long (NUMBER(28)); clamp it, it is informational only
            s.start = r.getBigDecimal(3).max(java.math.BigDecimal.valueOf(Long.MIN_VALUE))
                .min(java.math.BigDecimal.valueOf(Long.MAX_VALUE)).longValue();
            s.increment = r.getLong(4);
            snap.sequences.add(s);
        });
    }

    @Override
    public void readColumnFlags(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        query(c, "SELECT OWNER, TABLE_NAME, COLUMN_NAME FROM ALL_TAB_IDENTITY_COLS WHERE OWNER IN ({schemas})", schemas, r -> {
            Snapshot.Column col = column(snap, r.getString(1), r.getString(2), r.getString(3));
            if (col != null) col.identity = true;
        });
        query(c, """
            SELECT OWNER, TABLE_NAME, COLUMN_NAME FROM ALL_TAB_COLS
            WHERE VIRTUAL_COLUMN = 'YES' AND HIDDEN_COLUMN = 'NO' AND OWNER IN ({schemas})""", schemas, r -> {
            Snapshot.Column col = column(snap, r.getString(1), r.getString(2), r.getString(3));
            if (col != null) col.generated = true;
        });
    }

    @Override
    public void readRowEstimates(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        query(c, "SELECT OWNER, TABLE_NAME, NUM_ROWS FROM ALL_TABLES WHERE OWNER IN ({schemas})", schemas, r -> {
            Snapshot.Table t = table(snap, r.getString(1), r.getString(2));
            long n = r.getLong(3);
            if (t != null && !r.wasNull()) t.rowEstimate = n;
        });
    }

    @Override
    public void readComments(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        query(c, "SELECT OWNER, TABLE_NAME, COMMENTS FROM ALL_TAB_COMMENTS WHERE COMMENTS IS NOT NULL AND OWNER IN ({schemas})", schemas, r -> {
            Snapshot.Table t = table(snap, r.getString(1), r.getString(2));
            if (t != null) t.comment = r.getString(3);
        });
        query(c, "SELECT OWNER, TABLE_NAME, COLUMN_NAME, COMMENTS FROM ALL_COL_COMMENTS WHERE COMMENTS IS NOT NULL AND OWNER IN ({schemas})", schemas, r -> {
            Snapshot.Column col = column(snap, r.getString(1), r.getString(2), r.getString(3));
            if (col != null) col.comment = r.getString(4);
        });
    }
}
