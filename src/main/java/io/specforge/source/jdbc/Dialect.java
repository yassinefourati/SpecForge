package io.specforge.source.jdbc;

import io.specforge.snapshot.Snapshot;
import io.specforge.source.SourceKind;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Per relational engine: the read-only guards (RO-1) and what {@code DatabaseMetaData} misses or cannot do in bulk
 * (INT-3, ADR 001). Every method reads catalog metadata for all given schemas at once, never one table at a time,
 * except the JDBC fallbacks in {@link JdbcMetadata} for drivers that refuse a null table name.
 * <p>
 * Schema names are as the engine spells them. For engines without schemas (SQLite) the schema is "".
 */
public interface Dialect {

    /** Driver properties: timeouts, application name, read-only hints. Never credentials. */
    Properties connectionProperties();

    /** Statements run at the start of the read-only transaction, which is always rolled back. */
    List<String> guards();

    boolean isSystemSchema(String schema);

    /** Engine-internal tables that look like user tables (Oracle recycle bin, materialized-view logs, ...). */
    default boolean isSystemTable(String table) {
        return false;
    }

    /** true when the engine has no schemas (SQLite): everything is read with a null schema pattern. */
    default boolean schemaless() {
        return false;
    }

    /** Whether {@link Connection#setReadOnly} may be called after opening (SQLite fixes the mode when it opens). */
    default boolean readOnlyAfterOpen() {
        return true;
    }

    /** The schemas a user can pick from (CONN-3). */
    default List<String> schemas(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet r = c.getMetaData().getSchemas()) {
            while (r.next()) {
                String s = r.getString("TABLE_SCHEM");
                if (s != null && !isSystemSchema(s.trim())) out.add(s.trim());
            }
        }
        return out;
    }

    /** The schemas read when the user names none. */
    default List<String> defaultSchemas(Connection c) throws SQLException {
        return schemas(c);
    }

    default void readPrimaryKeys(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        for (String schema : schemas) JdbcMetadata.primaryKeys(md, schema, snap);
    }

    default void readForeignKeys(Connection c, Snapshot snap, Set<String> schemas) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        for (String schema : schemas) JdbcMetadata.foreignKeys(md, schema, snap);
    }

    /** Unique constraints and unique indexes other than the primary key. */
    void readUniqueKeys(Connection c, Snapshot snapshot, Set<String> schemas) throws SQLException;

    void readSequences(Connection c, Snapshot snapshot, Set<String> schemas) throws SQLException;

    /** Identity and generated-column flags the driver does not report reliably. */
    void readColumnFlags(Connection c, Snapshot snapshot, Set<String> schemas) throws SQLException;

    /** Row counts from statistics, never COUNT(*) (INT-1). */
    void readRowEstimates(Connection c, Snapshot snapshot, Set<String> schemas) throws SQLException;

    /** Table and column comments where the driver does not report them as REMARKS. */
    default void readComments(Connection c, Snapshot snapshot, Set<String> schemas) throws SQLException {
        // REMARKS from getTables / getColumns are used as reported
    }

    static Dialect forKind(SourceKind kind) {
        return switch (kind) {
            case POSTGRESQL -> new PostgresDialect();
            case MYSQL -> new MySqlDialect(false);
            case MARIADB -> new MySqlDialect(true);
            case SQLSERVER -> new SqlServerDialect();
            case ORACLE -> new OracleDialect();
            case DB2 -> new Db2Dialect();
            case SQLITE -> new SqliteDialect();
            case H2 -> new H2Dialect();
        };
    }
}
