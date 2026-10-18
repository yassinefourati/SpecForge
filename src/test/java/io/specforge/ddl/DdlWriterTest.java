package io.specforge.ddl;

import io.specforge.snapshot.Snapshot;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** MIG-2 / MIG-3 rules that need no database (the round trip per engine is DdlRoundTripIT). */
class DdlWriterTest {

    @Test
    void whatTheSnapshotCannotRecreateIsRefusedWithTheReason() {
        Snapshot s = snapshot("POSTGRESQL", "public");
        Snapshot.Table t = table(s, "public", "t", col("id", "int8", false));
        t.columns.add(col("mood", "mood_enum", true));
        Snapshot.Column total = col("total", "numeric", true);
        total.generated = true;
        t.columns.add(total);
        DdlWriter.Result r = DdlWriter.write(s, List.of("public.t"));
        assertThat(r.ok()).isFalse();
        assertThat(r.sql).isEmpty();
        assertThat(r.problems).anyMatch(p -> p.contains("mood_enum")).anyMatch(p -> p.contains("computed"));
    }

    @Test
    void serialColumnsKeepTheirDefaultAndTheirSequence() {
        Snapshot s = snapshot("POSTGRESQL", "app");
        Snapshot.Column id = col("id", "bigserial", false);
        id.defaultValue = "nextval('app.t_id_seq'::regclass)";
        table(s, "app", "t", id);
        Snapshot.Sequence seq = new Snapshot.Sequence();
        seq.schema = "app";
        seq.name = "t_id_seq";
        s.sequences.add(seq);
        DdlWriter.Result r = DdlWriter.write(s, List.of("app.t"));
        assertThat(r.problems).isEmpty();
        assertThat(r.sql).contains("create schema if not exists \"app\"", "create sequence if not exists \"app\".\"t_id_seq\"",
            "\"id\" int8 default nextval('app.t_id_seq'::regclass) not null");
    }

    @Test
    void mysqlTextDefaultsAreQuotedAndFunctionsAreNot() {
        Snapshot s = snapshot("MYSQL", "shop");
        Snapshot.Column status = col("status", "VARCHAR", false);
        status.size = 20;
        status.defaultValue = "NEW";
        Snapshot.Column at = col("created_at", "DATETIME", false);
        at.defaultValue = "CURRENT_TIMESTAMP";
        at.generated = true;
        Snapshot.Column n = col("n", "INT", true);
        n.defaultValue = "0";
        table(s, "shop", "t", col("id", "BIGINT", false), status, at, n);
        DdlWriter.Result r = DdlWriter.write(s, List.of("shop.t"));
        assertThat(r.problems).isEmpty();
        assertThat(r.sql).contains("`status` varchar(20) default 'NEW' not null", "`created_at` datetime default CURRENT_TIMESTAMP not null",
            "`n` int default 0").doesNotContain("`shop`.");
    }

    @Test
    void unsupportedEnginesAndUnselectedReferencesAreReported() {
        assertThat(DdlWriter.write(snapshot("ORACLE", "S"), List.of()).problems).singleElement().satisfies(p -> assertThat(p).contains("not supported"));
        Snapshot s = snapshot("H2", "PUBLIC");
        Snapshot.Table t = table(s, "PUBLIC", "A", col("ID", "BIGINT", false), col("B_ID", "BIGINT", true));
        Snapshot.ForeignKey fk = new Snapshot.ForeignKey();
        fk.columns.add("B_ID");
        fk.refSchema = "PUBLIC";
        fk.refTable = "B";
        fk.refColumns.add("ID");
        t.foreignKeys.add(fk);
        assertThat(DdlWriter.write(s, List.of("PUBLIC.A")).problems).anyMatch(p -> p.contains("not selected"));
    }

    private static Snapshot snapshot(String engine, String schema) {
        Snapshot s = new Snapshot();
        s.engine = engine;
        s.schemas = List.of(schema);
        return s;
    }

    private static Snapshot.Table table(Snapshot s, String schema, String name, Snapshot.Column... cols) {
        Snapshot.Table t = new Snapshot.Table();
        t.schema = schema;
        t.name = name;
        t.columns.addAll(List.of(cols));
        t.primaryKey = List.of(cols[0].name);
        s.tables.add(t);
        return t;
    }

    private static Snapshot.Column col(String name, String vendor, boolean nullable) {
        Snapshot.Column c = new Snapshot.Column();
        c.name = name;
        c.vendorType = vendor;
        c.jdbcType = Types.OTHER;
        c.nullable = nullable;
        return c;
    }
}
