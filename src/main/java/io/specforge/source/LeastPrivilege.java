package io.specforge.source;

/**
 * CONN-5: per engine, the SQL a DBA runs once to create a login that can read the catalog and nothing else, adapted
 * from querywise's reader_role.sql (which grants SELECT on data; SpecForge needs only structure). SpecForge shows
 * it and never runs it. {@code {database}}, {@code {schema}} and {@code {user}} are filled in by the UI from the form.
 */
public final class LeastPrivilege {

    private LeastPrivilege() {}

    public static String sql(SourceKind kind) {
        return switch (kind) {
            case POSTGRESQL -> """
                -- PostgreSQL: the catalog is readable by every login; USAGE lets it see the schema's objects.
                -- No SELECT is granted, so table data stays unreadable.
                CREATE ROLE {user} LOGIN PASSWORD 'choose-a-password';
                GRANT CONNECT ON DATABASE {database} TO {user};
                GRANT USAGE ON SCHEMA {schema} TO {user};""";
            case MYSQL, MARIADB -> """
                -- MySQL / MariaDB: information_schema lists only objects the login holds a privilege on.
                -- REFERENCES makes the tables visible without allowing SELECT, INSERT, UPDATE or DELETE.
                CREATE USER '{user}'@'%' IDENTIFIED BY 'choose-a-password';
                GRANT REFERENCES ON {database}.* TO '{user}'@'%';""";
            case SQLSERVER -> """
                -- SQL Server cannot make a session read-only: this login is the real protection (RO-2).
                -- VIEW DEFINITION shows the schema's structure without granting access to its data.
                CREATE LOGIN {user} WITH PASSWORD = 'Choose-a-Password1';
                USE {database};
                CREATE USER {user} FOR LOGIN {user};
                GRANT VIEW DEFINITION ON SCHEMA::{schema} TO {user};""";
            case ORACLE -> """
                -- Oracle has no structure-only privilege: ALL_* views list the objects a user can access.
                -- READ (12c+) allows queries but never writes or locks; SpecForge itself only reads the dictionary.
                CREATE USER {user} IDENTIFIED BY "choose-a-password";
                GRANT CREATE SESSION TO {user};
                -- repeat per table of {schema}, or script it from ALL_TABLES:
                GRANT READ ON {schema}.some_table TO {user};""";
            case DB2 -> """
                -- Db2: the SYSCAT views are readable by PUBLIC unless the database was created RESTRICTIVE.
                -- CONNECT alone grants no access to table data. Db2 cannot make a session read-only (RO-2).
                -- Create the operating-system or LDAP user {user} first, then:
                GRANT CONNECT ON DATABASE TO USER {user};""";
            case SQLITE -> """
                -- SQLite has no users. SpecForge opens the file read-only; for more protection,
                -- point it at a copy of the file or make the file read-only for your OS account.""";
            case H2 -> """
                -- H2: SpecForge opens the database with ACCESS_MODE_DATA=r. For a server database,
                -- a user without table rights still sees INFORMATION_SCHEMA:
                CREATE USER {user} PASSWORD 'choose-a-password';""";
        };
    }
}
