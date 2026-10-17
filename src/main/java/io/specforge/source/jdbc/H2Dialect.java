package io.specforge.source.jdbc;

import io.specforge.snapshot.Snapshot;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * H2 2.x over INFORMATION_SCHEMA. Read-only is set in the URL ({@code ACCESS_MODE_DATA=r}, and {@code IFEXISTS} so a
 * wrong path never creates a database), see {@code ConnectionSettings.connectUrl()}.
 */
public class H2Dialect extends CatalogDialect {

    @Override
    public Properties connectionProperties() {
        return new Properties();
    }

    @Override
    public List<String> guards() {
        return List.of("SET LOCK_TIMEOUT 5000");
    }

    @Override
    public boolean isSystemSchema(String schema) {
        return schema.equals("INFORMATION_SCHEMA");
    }

    @Override
    public void readUniqueKeys(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        Keys keys = new Keys();
        query(c, """
            SELECT k.TABLE_SCHEMA, k.TABLE_NAME, k.CONSTRAINT_NAME, k.ORDINAL_POSITION, k.COLUMN_NAME
            FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS t
            JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE k
              ON k.CONSTRAINT_SCHEMA = t.CONSTRAINT_SCHEMA AND k.CONSTRAINT_NAME = t.CONSTRAINT_NAME
            WHERE t.CONSTRAINT_TYPE = 'UNIQUE' AND t.TABLE_SCHEMA IN ({schemas})""", schemas,
            r -> keys.add(r.getString(1), r.getString(2), r.getString(3), false, r.getInt(4), r.getString(5)));
        keys.applyUnique(snap);
    }

    @Override
    public void readSequences(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        query(c, """
            SELECT SEQUENCE_SCHEMA, SEQUENCE_NAME, START_VALUE, INCREMENT FROM INFORMATION_SCHEMA.SEQUENCES
            WHERE SEQUENCE_SCHEMA IN ({schemas}) ORDER BY 1, 2""", schemas, r -> {
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
            SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, IS_IDENTITY, IS_GENERATED FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA IN ({schemas}) AND (IS_IDENTITY = 'YES' OR IS_GENERATED = 'ALWAYS')""", schemas, r -> {
            Snapshot.Column col = column(snap, r.getString(1), r.getString(2), r.getString(3));
            if (col == null) return;
            if ("YES".equals(r.getString(4))) col.identity = true;
            else col.generated = true;
        });
    }

    @Override
    public void readRowEstimates(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        query(c, """
            SELECT TABLE_SCHEMA, TABLE_NAME, ROW_COUNT_ESTIMATE FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_SCHEMA IN ({schemas}) AND TABLE_TYPE = 'BASE TABLE'""", schemas, r -> {
            Snapshot.Table t = table(snap, r.getString(1), r.getString(2));
            long n = r.getLong(3);
            if (t != null && !r.wasNull()) t.rowEstimate = n;
        });
    }
}
