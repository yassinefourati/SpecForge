package io.specforge.support;

import io.specforge.source.ConnectionSettings;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;

/**
 * One throwaway PostgreSQL per test run (D7), with the prior-art fixture loaded into a fresh database per caller.
 * The password is random per run and never written anywhere.
 */
public final class Postgres {

    public static final String PASSWORD = randomPassword();
    private static PostgreSQLContainer container;
    private static int databases;

    private Postgres() {}

    public static synchronized PostgreSQLContainer container() {
        if (container == null) {
            container = new PostgreSQLContainer("postgres:16-alpine").withUsername("specforge").withPassword(PASSWORD);
            container.start();
        }
        return container;
    }

    /** A new database with the given SQL fixtures loaded; returns connection settings pointing at it. */
    public static synchronized ConnectionSettings database(List<Path> fixtures) throws Exception {
        PostgreSQLContainer c = container();
        String db = "fixture_" + (++databases);
        try (Connection admin = DriverManager.getConnection(c.getJdbcUrl(), c.getUsername(), c.getPassword());
             Statement s = admin.createStatement()) {
            s.execute("CREATE DATABASE " + db);
        }
        ConnectionSettings cs = new ConnectionSettings();
        cs.kind = "POSTGRESQL";
        cs.host = c.getHost();
        cs.port = c.getMappedPort(5432);
        cs.database = db;
        cs.user = c.getUsername();
        cs.password = c.getPassword();
        try (Connection conn = DriverManager.getConnection(cs.jdbcUrl(), cs.user, cs.password); Statement s = conn.createStatement()) {
            for (Path f : fixtures) s.execute(Files.readString(f));
        }
        return cs;
    }

    private static String randomPassword() {
        byte[] b = new byte[12];
        new SecureRandom().nextBytes(b);
        return "Pw" + HexFormat.of().formatHex(b);
    }
}
