package io.specforge.source.jdbc;

import io.specforge.snapshot.Snapshot;
import io.specforge.source.ConnectionSettings;
import io.specforge.source.SourceException;
import io.specforge.support.Postgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** INT-1, INT-3, RO-1, SEC-5 against a real PostgreSQL with the prior-art fixture. */
class PostgresIntrospectionIT {

    static ConnectionSettings cs;
    static Snapshot snap;

    @BeforeAll
    static void load() throws Exception {
        cs = Postgres.database(List.of(Path.of("src/test/resources/fixtures/prior-art/schema.sql")));
        snap = new JdbcConnector(cs).introspect(List.of("administration"), null);
    }

    @Test
    void testConnectionReportsVersionAndSchemas() {
        JdbcConnector.ConnectionInfo info = new JdbcConnector(cs).test();
        assertThat(info.serverVersion).startsWith("PostgreSQL 16");
        assertThat(info.schemas).contains("administration", "public").doesNotContain("pg_catalog", "information_schema");
    }

    @Test
    void readsTablesKeysAndTypes() {
        assertThat(snap.tables).extracting(t -> t.name).containsExactlyInAnyOrder(
            "users", "roles", "permissions", "role_permissions", "user_roles", "audit_logs", "notifications",
            "error_logs", "support_tickets", "configurations");
        Snapshot.Table userRoles = snap.table("administration.user_roles");
        assertThat(userRoles.primaryKey).containsExactly("user_id", "role_id");
        assertThat(userRoles.foreignKeys).extracting(fk -> fk.refTable).containsExactlyInAnyOrder("users", "roles");
        assertThat(snap.table("administration.error_logs").column("details").vendorType).isEqualTo("jsonb");
        assertThat(snap.table("administration.audit_logs").column("timestamp").vendorType).isEqualTo("timestamptz");
        assertThat(snap.table("administration.users").comment).isEqualTo("Application users");
    }

    @Test
    void readsUniqueKeysIdentityAndSequences() {
        assertThat(snap.table("administration.users").uniqueKeys).extracting(u -> u.columns)
            .containsExactlyInAnyOrder(List.of("email"), List.of("username"));
        assertThat(snap.table("administration.users").column("user_id").identity).isTrue();
        assertThat(snap.table("administration.audit_logs").column("audit_id").identity).isFalse();
        assertThat(snap.table("administration.audit_logs").column("audit_id").defaultValue).contains("nextval");
        Snapshot.Sequence errors = snap.sequences.stream().filter(s -> s.name.equals("error_logs_seq")).findFirst().orElseThrow();
        assertThat(errors.increment).isEqualTo(1);
        assertThat(errors.ownedByTable).isEqualTo("error_logs");
        assertThat(errors.ownedByColumn).isEqualTo("error_id");
    }

    @Test
    void connectionIsReadOnly() throws Exception {
        JdbcConnector connector = new JdbcConnector(cs);
        try (var c = connector.openForTest(); var s = c.createStatement()) {
            assertThatThrownBy(() -> s.execute("CREATE TABLE administration.intruder (id int)"))
                .hasMessageContaining("read-only");
        }
    }

    @Test
    void wrongPasswordIsReportedWithoutLeakingIt() {
        ConnectionSettings bad = io.specforge.util.Json.MAPPER.convertValue(cs, ConnectionSettings.class);
        bad.password = "Wr0ngPassw0rdXyz";
        assertThatThrownBy(() -> new JdbcConnector(bad).test())
            .isInstanceOfSatisfying(SourceException.class, e -> {
                assertThat(e.code).isEqualTo("AUTH_FAILED");
                assertThat(e.getMessage()).doesNotContain("Wr0ngPassw0rdXyz").doesNotContain(Postgres.PASSWORD);
            });
    }
}
