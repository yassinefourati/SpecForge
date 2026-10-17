package io.specforge.source;

import java.util.Arrays;
import java.util.List;

/**
 * Every engine SpecForge can read (CONN-1), ported from querywise's KINDS registry: how to reach it, how it is
 * probed, its Initializr dependency id, and plain-language read-only controls and limits shown before connecting
 * (P6). {@link #driverClass} is loaded by the driver self-check (DRV-3); an engine whose driver is not in this
 * build is listed as unavailable.
 */
public enum SourceKind {

    POSTGRESQL("PostgreSQL", 1, 5432, "jdbc:postgresql://{host}:{port}/{database}", "localhost:5432/shop", "SELECT 1",
        "postgresql", "org.postgresql.Driver", false,
        "Read-only transaction, statement and lock timeouts, always rolled back. Only catalog metadata is read.",
        ""),
    MYSQL("MySQL", 1, 3306, "jdbc:mysql://{host}:{port}/{database}", "localhost:3306/shop", "SELECT 1",
        "mysql", "com.mysql.cj.jdbc.Driver", false,
        "START TRANSACTION READ ONLY, MAX_EXECUTION_TIME, always rolled back. Only catalog metadata is read.",
        "In MySQL a database is a schema: pick the database to read."),
    MARIADB("MariaDB", 1, 3306, "jdbc:mariadb://{host}:{port}/{database}", "localhost:3306/shop", "SELECT 1",
        "mariadb", "org.mariadb.jdbc.Driver", false,
        "START TRANSACTION READ ONLY, max_statement_time, always rolled back. Only catalog metadata is read.",
        "In MariaDB a database is a schema: pick the database to read."),
    SQLSERVER("SQL Server", 1, 1433, "jdbc:sqlserver://{host}:{port};databaseName={database}", "localhost:1433/shop", "SELECT 1",
        "sqlserver", "com.microsoft.sqlserver.jdbc.SQLServerDriver", false,
        "SQL Server has no read-only transaction mode: SpecForge relies on query and lock timeouts and rollback. Use a least-privilege login.",
        ""),
    ORACLE("Oracle", 1, 1521, "jdbc:oracle:thin:@//{host}:{port}/{database}", "localhost:1521/FREEPDB1", "SELECT 1 FROM DUAL",
        "oracle", "oracle.jdbc.OracleDriver", false,
        "SET TRANSACTION READ ONLY, network timeout, always rolled back. Only catalog metadata is read.",
        "The database field is the service name."),
    DB2("Db2 LUW", 1, 50000, "jdbc:db2://{host}:{port}/{database}", "localhost:50000/SHOP", "SELECT 1 FROM SYSIBM.SYSDUMMY1",
        "db2", "com.ibm.db2.jcc.DB2Driver", false,
        "Relies on a least-privilege user, query timeouts and rollback.",
        ""),
    SQLITE("SQLite", 1, 0, "jdbc:sqlite:{path}", "data/shop.sqlite", "SELECT 1",
        "sqlite", "org.sqlite.JDBC", true,
        "The file is opened read-only.",
        ""),
    H2("H2", 1, 0, "jdbc:h2:file:{path}", "./data/shop", "SELECT 1",
        "h2", "org.h2.Driver", true,
        "The database is opened read-only.",
        "");

    public final String label;
    public final int tier;
    public final int defaultPort;
    public final String urlTemplate;
    public final String example;
    public final String probe;
    public final String initializrId;
    public final String driverClass;
    /** true when the target is a file path instead of host/port/database */
    public final boolean fileBased;
    public final String controls;
    public final String limits;

    SourceKind(String label, int tier, int defaultPort, String urlTemplate, String example, String probe, String initializrId,
               String driverClass, boolean fileBased, String controls, String limits) {
        this.label = label;
        this.tier = tier;
        this.defaultPort = defaultPort;
        this.urlTemplate = urlTemplate;
        this.example = example;
        this.probe = probe;
        this.initializrId = initializrId;
        this.driverClass = driverClass;
        this.fileBased = fileBased;
        this.controls = controls;
        this.limits = limits;
    }

    public static SourceKind of(String name) {
        for (SourceKind k : values()) if (k.name().equalsIgnoreCase(name)) return k;
        throw new SourceException("UNKNOWN_ENGINE", "Unknown database engine '" + name + "'.",
            "Choose one of " + Arrays.toString(values()) + ".", "");
    }

    public static List<SourceKind> all() {
        return List.of(values());
    }
}
