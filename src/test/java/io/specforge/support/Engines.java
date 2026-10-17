package io.specforge.support;

import io.specforge.source.ConnectionSettings;
import io.specforge.source.SourceKind;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.db2.Db2Container;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One throwaway server per engine and test run (D7, same images as querywise), with a fresh database, schema or user
 * per fixture so tests never share state. SQLite and H2 get a fresh file. Passwords are random per run
 * ({@link Postgres#PASSWORD}) and never written anywhere.
 * <p>
 * Which engines run: {@code -Dspecforge.engines=all} or a comma list (postgresql,mysql,...). The default set starts
 * fast; SQL Server, Oracle and Db2 images are large and slow to start, so CI runs them nightly (§21).
 */
public final class Engines {

    public static final Set<SourceKind> DEFAULT = Set.of(SourceKind.POSTGRESQL, SourceKind.MYSQL, SourceKind.MARIADB,
        SourceKind.SQLITE, SourceKind.H2);

    private static final Map<SourceKind, JdbcDatabaseContainer<?>> CONTAINERS = new EnumMap<>(SourceKind.class);
    private static int fixtures;

    private Engines() {}

    /** The engines selected for this run. */
    public static List<SourceKind> selected() {
        String prop = System.getProperty("specforge.engines", "").trim().toLowerCase(Locale.ROOT);
        Set<SourceKind> out = new TreeSet<>();
        if (prop.isEmpty()) out.addAll(DEFAULT);
        else if (prop.equals("all")) out.addAll(SourceKind.all());
        else for (String s : prop.split(",")) if (!s.isBlank()) out.add(SourceKind.of(s.trim()));
        return new ArrayList<>(out);
    }

    /** A fresh database (or schema / user, per engine) with the fixture loaded; settings point at it. */
    public static synchronized ConnectionSettings database(SourceKind kind, Path fixture) throws Exception {
        String name = "fixture_" + (++fixtures);
        List<String> statements = statements(Files.readString(fixture));
        ConnectionSettings cs = new ConnectionSettings();
        cs.kind = kind.name();
        switch (kind) {
            case POSTGRESQL -> {
                return Postgres.database(List.of(fixture));
            }
            case SQLITE, H2 -> {
                Path dir = Files.createTempDirectory("specforge-" + kind.name().toLowerCase(Locale.ROOT));
                cs.path = dir.resolve(kind == SourceKind.SQLITE ? "shop.sqlite" : "shop").toString();
                run(cs.jdbcUrl(), kind == SourceKind.H2 ? "sa" : "", "", statements);
                cs.user = kind == SourceKind.H2 ? "sa" : "";
                cs.password = "";
                return cs;
            }
            default -> { }
        }
        JdbcDatabaseContainer<?> c = container(kind);
        cs.host = c.getHost();
        cs.port = c.getMappedPort(port(kind));
        cs.password = Postgres.PASSWORD;
        switch (kind) {
            case MYSQL, MARIADB -> {
                run(c.getJdbcUrl(), "root", Postgres.PASSWORD, List.of("CREATE DATABASE " + name));
                cs.database = name;
                cs.user = "root";
            }
            case SQLSERVER -> {
                run(c.getJdbcUrl(), "sa", Postgres.PASSWORD, List.of("CREATE DATABASE " + name));
                cs.database = name;
                cs.user = "sa";
                cs.sslMode = "require"; // the container's certificate is self-signed
            }
            case ORACLE -> {
                String user = name.toUpperCase(Locale.ROOT);
                run(c.getJdbcUrl(), "system", Postgres.PASSWORD, List.of(
                    "CREATE USER " + user + " IDENTIFIED BY \"" + Postgres.PASSWORD + "\" QUOTA UNLIMITED ON USERS",
                    "GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE VIEW TO " + user));
                cs.database = ((OracleContainer) c).getDatabaseName();
                cs.user = user;
            }
            case DB2 -> {
                String schema = name.toUpperCase(Locale.ROOT);
                run(c.getJdbcUrl(), c.getUsername(), Postgres.PASSWORD, List.of("CREATE SCHEMA " + schema));
                cs.database = c.getDatabaseName();
                cs.user = c.getUsername();
                cs.schemas = List.of(schema);
                List<String> withSchema = new ArrayList<>();
                withSchema.add("SET SCHEMA " + schema);
                withSchema.addAll(statements);
                statements = withSchema;
            }
            default -> throw new IllegalArgumentException(kind.name());
        }
        // SpecForge's own URL builder, so the tests also cover it
        String url = cs.jdbcUrl();
        run(url, cs.user, cs.password, statements);
        return cs;
    }

    private static JdbcDatabaseContainer<?> container(SourceKind kind) {
        return CONTAINERS.computeIfAbsent(kind, k -> {
            JdbcDatabaseContainer<?> c = switch (k) {
                case MYSQL -> new MySQLContainer("mysql:8.4").withUsername("root").withPassword(Postgres.PASSWORD);
                case MARIADB -> new MariaDBContainer("mariadb:11.4").withUsername("root").withPassword(Postgres.PASSWORD);
                case SQLSERVER -> new MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense()
                    .withPassword(Postgres.PASSWORD);
                case ORACLE -> new OracleContainer("gvenzl/oracle-free:23-slim").withPassword(Postgres.PASSWORD);
                case DB2 -> new Db2Container(DockerImageName.parse("icr.io/db2_community/db2:11.5.9.0")
                    .asCompatibleSubstituteFor("ibmcom/db2")).acceptLicense().withPassword(Postgres.PASSWORD)
                    .withStartupTimeout(java.time.Duration.ofMinutes(15));
                default -> throw new IllegalArgumentException(k.name());
            };
            c.start();
            return c;
        });
    }

    private static int port(SourceKind kind) {
        return switch (kind) {
            case MYSQL, MARIADB -> 3306;
            case SQLSERVER -> 1433;
            case ORACLE -> 1521;
            case DB2 -> 50000;
            default -> 5432;
        };
    }

    private static void run(String url, String user, String password, List<String> statements) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, user, password); Statement s = conn.createStatement()) {
            for (String sql : statements) s.execute(sql);
        }
    }

    /** Statements end with ";" at the end of a line; "--" comment lines are dropped. */
    static List<String> statements(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (String line : script.split("\\R")) {
            if (line.strip().startsWith("--")) continue;
            String stripped = line.stripTrailing();
            if (stripped.endsWith(";")) {
                sb.append(stripped, 0, stripped.length() - 1);
                if (!sb.toString().isBlank()) out.add(sb.toString().strip());
                sb.setLength(0);
            } else {
                sb.append(line).append('\n');
            }
        }
        if (!sb.toString().isBlank()) out.add(sb.toString().strip());
        return out;
    }
}
