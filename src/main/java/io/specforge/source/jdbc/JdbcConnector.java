package io.specforge.source.jdbc;

import io.specforge.jobs.Jobs;
import io.specforge.secrets.Redactor;
import io.specforge.snapshot.Snapshot;
import io.specforge.source.ConnectionSettings;
import io.specforge.source.SourceException;
import io.specforge.source.SourceKind;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Relational sources over JDBC. Read-only by construction (RO-1): {@code setReadOnly(true)}, the dialect's guards
 * at the start of the transaction, catalog metadata only, always rolled back.
 */
public class JdbcConnector {

    private final ConnectionSettings settings;
    private final SourceKind kind;
    private final Dialect dialect;

    public JdbcConnector(ConnectionSettings settings) {
        this.settings = settings;
        this.kind = settings.sourceKind();
        this.dialect = Dialect.forKind(kind);
    }

    /** What a successful probe found (CONN-3). */
    public static class ConnectionInfo {
        public String serverVersion = "";
        public List<String> schemas = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
    }

    public ConnectionInfo test() {
        try (Connection c = open()) {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(kind.probe)) {
                r.next();
            }
            ConnectionInfo info = new ConnectionInfo();
            DatabaseMetaData md = c.getMetaData();
            info.serverVersion = md.getDatabaseProductName() + " " + md.getDatabaseProductVersion();
            info.schemas = dialect.schemas(c);
            if (kind == SourceKind.SQLSERVER || kind == SourceKind.DB2)
                info.warnings.add(kind.label + " cannot enforce a read-only transaction; use a least-privilege login (see \"Least-privilege login\").");
            c.rollback();
            return info;
        } catch (SQLException e) {
            throw classify(e);
        }
    }

    /**
     * Reads every table, column, key, unique key and sequence of the given schemas (all visible non-system schemas
     * when empty) with bulk calls (INT-7).
     */
    public Snapshot introspect(List<String> requestedSchemas, Jobs.Job job) {
        try (Connection c = open()) {
            DatabaseMetaData md = c.getMetaData();
            Snapshot snap = new Snapshot();
            snap.engine = kind.name();
            snap.serverVersion = md.getDatabaseProductName() + " " + md.getDatabaseProductVersion();
            snap.capturedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
            Set<String> schemas = new LinkedHashSet<>();
            if (dialect.schemaless()) schemas.add("");
            else if (requestedSchemas == null || requestedSchemas.isEmpty()) schemas.addAll(dialect.defaultSchemas(c));
            else for (String s : requestedSchemas) if (!s.isBlank()) schemas.add(s.trim());
            snap.schemas = new ArrayList<>(schemas);
            snap.schemas.remove("");

            step(job, "Reading tables", 0.05);
            for (String schema : schemas) readTables(md, schema, snap);
            step(job, "Reading columns", 0.2);
            for (String schema : schemas) readColumns(md, schema, snap);
            step(job, "Reading primary keys", 0.45);
            dialect.readPrimaryKeys(c, snap, schemas);
            step(job, "Reading foreign keys", 0.6);
            dialect.readForeignKeys(c, snap, schemas);
            step(job, "Reading unique keys", 0.75);
            dialect.readUniqueKeys(c, snap, schemas);
            step(job, "Reading sequences and identity columns", 0.85);
            dialect.readSequences(c, snap, schemas);
            dialect.readColumnFlags(c, snap, schemas);
            step(job, "Reading row estimates", 0.95);
            dialect.readRowEstimates(c, snap, schemas);
            dialect.readComments(c, snap, schemas);
            for (Snapshot.Table t : snap.tables)
                if (t.primaryKey.isEmpty()) snap.warnings.add("Table " + t.qualifiedName() + " has no primary key: choose a key or generate it read-only (SEL-4).");
            c.rollback();
            return snap;
        } catch (SQLException e) {
            throw classify(e);
        }
    }

    private void step(Jobs.Job job, String phase, double progress) {
        if (job == null) return;
        job.checkCancelled();
        job.phase(phase, progress);
    }

    /** For tests that prove the guards hold (RO-1). */
    Connection openForTest() throws SQLException {
        return open();
    }

    private Connection open() throws SQLException {
        try {
            Class.forName(kind.driverClass);
        } catch (ClassNotFoundException e) {
            throw new SourceException("DRIVER_UNAVAILABLE", "The " + kind.label + " driver is not available in this build.",
                "This build was made without the " + kind.label + " driver; use the full build.", "Open About / drivers");
        }
        if (kind.fileBased && settings.effectiveUrl().isBlank()) checkFile();
        Properties p = dialect.connectionProperties();
        String user = settings.user == null ? "" : settings.user;
        if (!user.isEmpty()) p.setProperty("user", user);
        String pw = settings.effectivePassword();
        if (!pw.isEmpty()) p.setProperty("password", pw);
        DriverManager.setLoginTimeout(10);
        Connection c = DriverManager.getConnection(settings.connectUrl(), p);
        try {
            if (dialect.readOnlyAfterOpen()) c.setReadOnly(true);
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.setQueryTimeout(60);
                for (String g : dialect.guards()) s.execute(g);
            }
            return rollingBackOnClose(c);
        } catch (SQLException e) {
            c.close();
            throw e;
        }
    }

    /**
     * The connection, with close() rolling back first (RO-1). Db2 refuses to close a connection with an open
     * transaction, which would hide the error that ended the work (a refused write, a failed catalog query).
     */
    private static Connection rollingBackOnClose(Connection c) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                if (method.getName().equals("close") && method.getParameterCount() == 0) {
                    try {
                        if (!c.isClosed() && !c.getAutoCommit()) c.rollback();
                    } catch (SQLException ignored) {
                        // closing still releases the session; the rollback is a courtesy for strict drivers
                    }
                }
                try {
                    return method.invoke(c, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            });
    }

    /** File-based engines: a clear message for a wrong path; the drivers are opened without their create flag anyway. */
    private void checkFile() {
        String path = settings.filePath();
        java.nio.file.Path file = java.nio.file.Path.of(kind == SourceKind.H2 ? path + ".mv.db" : path);
        if (path.isBlank() || !java.nio.file.Files.isRegularFile(file))
            throw new SourceException("CONNECTION_FAILED", "No " + kind.label + " database file at " + (path.isBlank() ? "(empty path)" : file.toAbsolutePath()) + ".",
                "Check the path" + (kind == SourceKind.H2 ? " (the .mv.db file, or its name without the extension)" : "") + ".", "Retry");
    }

    private void readTables(DatabaseMetaData md, String schema, Snapshot snap) throws SQLException {
        try (ResultSet r = md.getTables(null, JdbcMetadata.pattern(schema), "%", new String[] {"TABLE", "BASE TABLE", "PARTITIONED TABLE"})) {
            while (r.next()) {
                String name = r.getString("TABLE_NAME");
                if (dialect.isSystemTable(name)) continue;
                Snapshot.Table t = new Snapshot.Table();
                t.schema = dialect.schemaless() || r.getString("TABLE_SCHEM") == null ? "" : r.getString("TABLE_SCHEM").trim();
                t.name = name;
                t.comment = nz(r.getString("REMARKS"));
                snap.tables.add(t);
            }
        }
    }

    private void readColumns(DatabaseMetaData md, String schema, Snapshot snap) throws SQLException {
        Map<String, Snapshot.Table> byName = index(snap);
        try (ResultSet r = md.getColumns(null, JdbcMetadata.pattern(schema), "%", "%")) {
            while (r.next()) {
                Snapshot.Table t = byName.get(qn(dialect.schemaless() ? null : r.getString("TABLE_SCHEM"), r.getString("TABLE_NAME")));
                if (t == null) continue; // a view or another relation kind
                Snapshot.Column c = new Snapshot.Column();
                c.name = r.getString("COLUMN_NAME");
                c.vendorType = nz(r.getString("TYPE_NAME"));
                c.jdbcType = r.getInt("DATA_TYPE");
                int size = r.getInt("COLUMN_SIZE");
                c.size = r.wasNull() ? null : size;
                int scale = r.getInt("DECIMAL_DIGITS");
                c.scale = r.wasNull() ? null : scale;
                c.nullable = r.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
                c.defaultValue = r.getString("COLUMN_DEF");
                c.autoIncrement = "YES".equalsIgnoreCase(r.getString("IS_AUTOINCREMENT"));
                c.generated = "YES".equalsIgnoreCase(r.getString("IS_GENERATEDCOLUMN"));
                c.comment = nz(r.getString("REMARKS"));
                c.position = r.getInt("ORDINAL_POSITION");
                t.columns.add(c);
            }
        }
        for (Snapshot.Table t : snap.tables) t.columns.sort((a, b) -> Integer.compare(a.position, b.position));
    }

    private static Map<String, Snapshot.Table> index(Snapshot snap) {
        Map<String, Snapshot.Table> m = new LinkedHashMap<>();
        for (Snapshot.Table t : snap.tables) m.put(t.qualifiedName(), t);
        return m;
    }

    private static String qn(String schema, String table) {
        return JdbcMetadata.qn(schema, table);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** Maps a driver error to a code from Appendix C, with every secret removed (SEC-5). */
    SourceException classify(SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        String msg = Redactor.redact(e, settings.secrets());
        String lower = msg.toLowerCase(Locale.ROOT);
        if (state.startsWith("28") || lower.contains("password authentication failed") || lower.contains("access denied") || lower.contains("login failed")
            || lower.contains("ora-01017") || lower.contains("invalid username/password") || lower.contains("errorcode=-4214"))
            return new SourceException("AUTH_FAILED", kind.label + " rejected the credentials: " + msg, "Check the user name and password.", "Re-enter credentials");
        if (state.startsWith("08") || lower.contains("connection refused") || lower.contains("unknownhost") || lower.contains("connect timed out"))
            return new SourceException("CONNECTION_FAILED", "Cannot connect to " + kind.label + ": " + msg, "Check host, port and that the server is running.", "Retry");
        if (state.equals("57014") || lower.contains("timeout") || lower.contains("timed out"))
            return new SourceException("TIMEOUT", kind.label + " did not answer in time: " + msg, "Narrow the schema filter or retry.", "Retry");
        if (state.equals("3D000") || lower.contains("does not exist") || lower.contains("unknown database") || lower.contains("not found"))
            return new SourceException("CONNECTION_FAILED", kind.label + ": " + msg, "Check the database name.", "Retry");
        return new SourceException("CONNECTION_FAILED", kind.label + ": " + msg, "", "Retry");
    }
}
