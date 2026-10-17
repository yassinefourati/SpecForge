package io.specforge.source.jdbc;

import io.specforge.snapshot.Snapshot;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * MySQL and MariaDB over information_schema. Both drivers are told to report databases as schemas
 * ({@code databaseTerm} / {@code useCatalogTerm}), so a MySQL database is a snapshot schema like any other.
 */
public class MySqlDialect extends CatalogDialect {

    private static final Set<String> SYSTEM = Set.of("information_schema", "mysql", "performance_schema", "sys");
    private final boolean mariadb;

    public MySqlDialect(boolean mariadb) {
        this.mariadb = mariadb;
    }

    @Override
    public Properties connectionProperties() {
        Properties p = new Properties();
        p.setProperty("connectTimeout", "10000");
        p.setProperty("socketTimeout", "120000");
        if (mariadb) {
            p.setProperty("useCatalogTerm", "SCHEMA");
        } else {
            p.setProperty("databaseTerm", "SCHEMA");
            p.setProperty("useInformationSchema", "true");
            p.setProperty("connectionAttributes", "program_name:SpecForge (read-only)");
        }
        return p;
    }

    @Override
    public List<String> guards() {
        return mariadb
            ? List.of("SET SESSION max_statement_time = 60", "SET SESSION lock_wait_timeout = 5", "START TRANSACTION READ ONLY")
            : List.of("SET SESSION MAX_EXECUTION_TIME = 60000", "SET SESSION lock_wait_timeout = 5", "START TRANSACTION READ ONLY");
    }

    @Override
    public boolean isSystemSchema(String schema) {
        return SYSTEM.contains(schema.toLowerCase(Locale.ROOT));
    }

    /** The database named in the connection; every visible one when none is named. */
    @Override
    public List<String> defaultSchemas(Connection c) throws SQLException {
        String current = c.getSchema();
        if (current == null || current.isBlank()) current = c.getCatalog();
        return current == null || current.isBlank() ? schemas(c) : List.of(current);
    }

    @Override
    public void readPrimaryKeys(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        Keys keys = new Keys();
        query(c, """
            SELECT TABLE_SCHEMA, TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME
            FROM information_schema.STATISTICS
            WHERE INDEX_NAME = 'PRIMARY' AND TABLE_SCHEMA IN ({schemas})""", schemas,
            r -> keys.add(r.getString(1), r.getString(2), r.getString(3), true, r.getInt(4), r.getString(5)));
        keys.applyPrimary(snap);
    }

    @Override
    public void readUniqueKeys(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        Keys keys = new Keys();
        query(c, """
            SELECT TABLE_SCHEMA, TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME
            FROM information_schema.STATISTICS
            WHERE NON_UNIQUE = 0 AND INDEX_NAME <> 'PRIMARY' AND COLUMN_NAME IS NOT NULL AND TABLE_SCHEMA IN ({schemas})
            ORDER BY TABLE_SCHEMA, TABLE_NAME, INDEX_NAME""", schemas,
            r -> keys.add(r.getString(1), r.getString(2), r.getString(3), false, r.getInt(4), r.getString(5)));
        keys.applyUnique(snap);
    }

    @Override
    public void readForeignKeys(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        ForeignKeys fks = new ForeignKeys();
        query(c, """
            SELECT k.TABLE_SCHEMA, k.TABLE_NAME, k.CONSTRAINT_NAME, k.REFERENCED_TABLE_SCHEMA, k.REFERENCED_TABLE_NAME,
                   k.ORDINAL_POSITION, k.COLUMN_NAME, k.REFERENCED_COLUMN_NAME, rc.UPDATE_RULE, rc.DELETE_RULE
            FROM information_schema.KEY_COLUMN_USAGE k
            JOIN information_schema.REFERENTIAL_CONSTRAINTS rc
              ON rc.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA AND rc.CONSTRAINT_NAME = k.CONSTRAINT_NAME AND rc.TABLE_NAME = k.TABLE_NAME
            WHERE k.REFERENCED_TABLE_NAME IS NOT NULL AND k.TABLE_SCHEMA IN ({schemas})
            ORDER BY k.TABLE_SCHEMA, k.TABLE_NAME, k.CONSTRAINT_NAME, k.ORDINAL_POSITION""", schemas,
            r -> fks.add(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getInt(6),
                r.getString(7), r.getString(8), rule(r.getString(9)), rule(r.getString(10))));
        fks.apply(snap);
    }

    @Override
    public void readSequences(Connection c, Snapshot snap, Set<String> schemas) {
        // keys come from AUTO_INCREMENT; MariaDB sequences used as column defaults are not detected yet
    }

    @Override
    public void readColumnFlags(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        query(c, """
            SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, EXTRA, COLUMN_TYPE
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA IN ({schemas})
              AND (EXTRA LIKE '%auto_increment%' OR EXTRA LIKE '%GENERATED%' OR EXTRA LIKE '%PERSISTENT%'
                   OR EXTRA LIKE '%on update%' OR COLUMN_TYPE LIKE '%unsigned%')""", schemas, r -> {
            Snapshot.Column col = column(snap, r.getString(1), r.getString(2), r.getString(3));
            if (col == null) return;
            String extra = JdbcMetadata.nz(r.getString(4)).toLowerCase(Locale.ROOT);
            if (extra.contains("auto_increment")) col.autoIncrement = true;
            if (extra.contains("generated") && !extra.contains("default_generated") || extra.contains("persistent")) col.generated = true;
            if (extra.contains("default_generated") && col.defaultValue == null) col.defaultValue = "CURRENT_TIMESTAMP";
            // "on update CURRENT_TIMESTAMP": the database maintains the value, like a default
            if (extra.contains("on update") && col.defaultValue == null) col.defaultValue = "CURRENT_TIMESTAMP";
            String type = JdbcMetadata.nz(r.getString(5)).toLowerCase(Locale.ROOT);
            if (type.contains("unsigned") && !col.vendorType.toLowerCase(Locale.ROOT).contains("unsigned"))
                col.vendorType = col.vendorType + " UNSIGNED";
        });
    }

    @Override
    public void readRowEstimates(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        query(c, """
            SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_ROWS
            FROM information_schema.TABLES
            WHERE TABLE_SCHEMA IN ({schemas}) AND TABLE_TYPE = 'BASE TABLE'""", schemas, r -> {
            Snapshot.Table t = table(snap, r.getString(1), r.getString(2));
            long n = r.getLong(3);
            if (t != null && !r.wasNull()) t.rowEstimate = n;
        });
    }
}
