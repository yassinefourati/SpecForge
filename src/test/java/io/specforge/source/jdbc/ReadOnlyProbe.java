package io.specforge.source.jdbc;

import io.specforge.source.ConnectionSettings;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Tries a write through SpecForge's own read-only connection (RO-1); for tests outside this package. */
public final class ReadOnlyProbe {

    private ReadOnlyProbe() {}

    /** The error the engine raised for the write, or null when the write went through (and was rolled back). */
    public static SQLException write(ConnectionSettings cs, String sql) throws SQLException {
        try (Connection c = new JdbcConnector(cs).openForTest(); Statement s = c.createStatement()) {
            try {
                s.executeUpdate(sql);
                c.rollback();
                return null;
            } catch (SQLException expected) {
                return expected;
            }
        }
    }
}
