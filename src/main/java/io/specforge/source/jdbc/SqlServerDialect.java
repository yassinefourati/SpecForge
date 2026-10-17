package io.specforge.source.jdbc;

import io.specforge.snapshot.Snapshot;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * SQL Server over the sys catalog views of the connected database. SQL Server has no read-only transaction (RO-2):
 * the guards are a lock timeout, the driver's read-only intent and rollback; the UI recommends a least-privilege login.
 */
public class SqlServerDialect extends CatalogDialect {

    private static final Set<String> SYSTEM = Set.of("sys", "information_schema", "guest", "db_owner", "db_accessadmin",
        "db_securityadmin", "db_ddladmin", "db_backupoperator", "db_datareader", "db_datawriter", "db_denydatareader",
        "db_denydatawriter");

    @Override
    public Properties connectionProperties() {
        Properties p = new Properties();
        p.setProperty("loginTimeout", "10");
        p.setProperty("socketTimeout", "120000");
        p.setProperty("applicationName", "SpecForge (read-only)");
        p.setProperty("applicationIntent", "ReadOnly");
        return p;
    }

    @Override
    public List<String> guards() {
        return List.of("SET LOCK_TIMEOUT 5000");
    }

    @Override
    public boolean isSystemSchema(String schema) {
        return SYSTEM.contains(schema.toLowerCase(Locale.ROOT));
    }

    /** Schemas that own at least one user table. */
    @Override
    public List<String> defaultSchemas(Connection c) throws SQLException {
        List<String> out = new java.util.ArrayList<>();
        try (var s = c.createStatement(); var r = s.executeQuery(
            "SELECT DISTINCT s.name FROM sys.tables t JOIN sys.schemas s ON s.schema_id = t.schema_id WHERE t.is_ms_shipped = 0 ORDER BY s.name")) {
            while (r.next()) if (!isSystemSchema(r.getString(1))) out.add(r.getString(1));
        }
        return out.isEmpty() ? schemas(c) : out;
    }

    @Override
    public void readPrimaryKeys(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        Keys keys = keys(c, schemas, "i.is_primary_key = 1", true);
        keys.applyPrimary(snap);
    }

    @Override
    public void readUniqueKeys(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        Keys keys = keys(c, schemas, "i.is_unique = 1 AND i.is_primary_key = 0 AND i.has_filter = 0", false);
        keys.applyUnique(snap);
    }

    private static Keys keys(Connection c, Set<String> schemas, String condition, boolean primary) throws SQLException {
        Keys keys = new Keys();
        query(c, """
            SELECT s.name, t.name, i.name, ic.key_ordinal, col.name
            FROM sys.indexes i
            JOIN sys.tables t ON t.object_id = i.object_id
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id AND ic.is_included_column = 0
            JOIN sys.columns col ON col.object_id = ic.object_id AND col.column_id = ic.column_id
            WHERE %s AND s.name IN ({schemas})""".formatted(condition), schemas,
            r -> keys.add(r.getString(1), r.getString(2), r.getString(3), primary, r.getInt(4), r.getString(5)));
        return keys;
    }

    @Override
    public void readForeignKeys(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        ForeignKeys fks = new ForeignKeys();
        query(c, """
            SELECT s.name, t.name, fk.name, rs.name, rt.name, fkc.constraint_column_id, pc.name, rc.name,
                   fk.update_referential_action_desc, fk.delete_referential_action_desc
            FROM sys.foreign_keys fk
            JOIN sys.tables t ON t.object_id = fk.parent_object_id
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            JOIN sys.tables rt ON rt.object_id = fk.referenced_object_id
            JOIN sys.schemas rs ON rs.schema_id = rt.schema_id
            JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id
            JOIN sys.columns pc ON pc.object_id = fkc.parent_object_id AND pc.column_id = fkc.parent_column_id
            JOIN sys.columns rc ON rc.object_id = fkc.referenced_object_id AND rc.column_id = fkc.referenced_column_id
            WHERE s.name IN ({schemas})""", schemas,
            r -> fks.add(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getInt(6),
                r.getString(7), r.getString(8), rule(r.getString(9)), rule(r.getString(10))));
        fks.apply(snap);
    }

    @Override
    public void readSequences(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        query(c, """
            SELECT s.name, q.name, CAST(q.start_value AS BIGINT), CAST(q.increment AS BIGINT)
            FROM sys.sequences q JOIN sys.schemas s ON s.schema_id = q.schema_id
            WHERE s.name IN ({schemas}) ORDER BY s.name, q.name""", schemas, r -> {
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
            SELECT s.name, t.name, col.name, col.is_identity, col.is_computed, ty.name
            FROM sys.columns col
            JOIN sys.tables t ON t.object_id = col.object_id
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            JOIN sys.types ty ON ty.user_type_id = col.user_type_id
            WHERE s.name IN ({schemas})""", schemas, r -> {
            Snapshot.Column col = column(snap, r.getString(1), r.getString(2), r.getString(3));
            if (col == null) return;
            // the driver reports "int identity": keep the plain type name
            col.vendorType = r.getString(6);
            if (r.getBoolean(4)) col.identity = true;
            // computed columns and rowversion are maintained by the database
            if (r.getBoolean(5) || "timestamp".equals(col.vendorType) || "rowversion".equals(col.vendorType)) col.generated = true;
        });
    }

    @Override
    public void readRowEstimates(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        query(c, """
            SELECT s.name, t.name, SUM(p.rows)
            FROM sys.tables t
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            JOIN sys.partitions p ON p.object_id = t.object_id AND p.index_id IN (0, 1)
            WHERE s.name IN ({schemas}) GROUP BY s.name, t.name""", schemas, r -> {
            Snapshot.Table t = table(snap, r.getString(1), r.getString(2));
            if (t != null) t.rowEstimate = r.getLong(3);
        });
    }

    /** MS_Description extended properties, which the driver does not report as REMARKS. */
    @Override
    public void readComments(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        query(c, """
            SELECT s.name, t.name, col.name, CAST(ep.value AS NVARCHAR(4000))
            FROM sys.extended_properties ep
            JOIN sys.tables t ON t.object_id = ep.major_id
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            LEFT JOIN sys.columns col ON col.object_id = ep.major_id AND col.column_id = ep.minor_id
            WHERE ep.class = 1 AND ep.name = 'MS_Description' AND s.name IN ({schemas})""", schemas, r -> {
            Snapshot.Table t = table(snap, r.getString(1), r.getString(2));
            if (t == null) return;
            String text = JdbcMetadata.nz(r.getString(4));
            if (r.getString(3) == null) t.comment = text;
            else if (t.column(r.getString(3)) != null) t.column(r.getString(3)).comment = text;
        });
    }
}
