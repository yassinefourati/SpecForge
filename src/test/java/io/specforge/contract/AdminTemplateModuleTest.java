package io.specforge.contract;

import io.specforge.config.GeneratorConfig;
import io.specforge.mapper.DatabaseMapper;
import io.specforge.model.Model.ApiModel;
import io.specforge.model.Model.Msg;
import io.specforge.plan.GenerationPlan;
import io.specforge.snapshot.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The admin-template@1 contract's own rules (CONTRACT-2, CONTRACT-4). */
class AdminTemplateModuleTest {

    @TempDir
    Path project;

    @Test
    void portableDefaultsBecomePostgresDefaults() {
        assertThat(AdminTemplateModule.pgDefault("'NEW'::character varying", "String")).isEqualTo("'NEW'");
        assertThat(AdminTemplateModule.pgDefault("('it''s')", "String")).isEqualTo("'it''s'");
        assertThat(AdminTemplateModule.pgDefault("CURRENT_TIMESTAMP", "OffsetDateTime")).isEqualTo("now()");
        assertThat(AdminTemplateModule.pgDefault("getdate()", "LocalDateTime")).isEqualTo("localtimestamp");
        assertThat(AdminTemplateModule.pgDefault("SYSDATE", "LocalDate")).isEqualTo("current_date");
        assertThat(AdminTemplateModule.pgDefault("((0))", "Integer")).isEqualTo("0");
        assertThat(AdminTemplateModule.pgDefault("b'1'", "Boolean")).isEmpty();
        assertThat(AdminTemplateModule.pgDefault("1", "Boolean")).isEqualTo("true");
        assertThat(AdminTemplateModule.pgDefault("'0'", "Boolean")).isEqualTo("false");
        assertThat(AdminTemplateModule.pgDefault("nextval('x_seq'::regclass)", "Long")).as("sequences are the key's business").isEmpty();
        assertThat(AdminTemplateModule.pgDefault("upper(name)", "String")).isEmpty();
        assertThat(AdminTemplateModule.pgDefault(null, "String")).isEmpty();
    }

    @Test
    void reservedAndUnusualNamesAreQuoted() {
        assertThat(AdminTemplateModule.ddlName("order")).isEqualTo("\"order\"");
        assertThat(AdminTemplateModule.annotationName("order")).isEqualTo("`order`");
        assertThat(AdminTemplateModule.ddlName("shop_order")).isEqualTo("shop_order");
        assertThat(AdminTemplateModule.ddlName("Mixed")).isEqualTo("\"Mixed\"");
        assertThat(AdminTemplateModule.plain("`a`")).isEqualTo("a");
        assertThat(AdminTemplateModule.moduleName("Shop DB 2")).isEqualTo("shopdb2");
        assertThat(AdminTemplateModule.moduleName("42")).isEqualTo("data42");
        assertThat(AdminTemplateModule.label("OrderItem")).isEqualTo("Order items");
    }

    @Test
    void nextFlywayVersionIgnoresTheModulesOwnMigration() throws Exception {
        Path dir = Files.createDirectories(project.resolve("backend/src/main/resources/db/migration"));
        for (String f : List.of("V1__schema.sql", "V11__notification_delivery.sql", "V12__shop.sql", "notes.txt")) Files.writeString(dir.resolve(f), "");
        AdminTemplateModule m = new AdminTemplateModule();
        m.name = "shop";
        List<Msg> msgs = new ArrayList<>();
        assertThat(AdminTemplateModule.nextMigration(project.toString(), m, msgs)).isEqualTo(12);
        assertThat(msgs).isEmpty();
        assertThat(AdminTemplateModule.nextMigration("", m, msgs)).isEqualTo(1);
        assertThat(msgs).singleElement().satisfies(x -> assertThat(x.text).contains("project folder"));
    }

    @Test
    void whatTheContractCannotExpressIsLeftOutWithTheReason() {
        Snapshot s = new Snapshot();
        s.engine = "POSTGRESQL";
        s.schemas = List.of("app");
        // a composite key: left out; a composite foreign key to it stays two plain columns
        Snapshot.Table lines = table("lines", "order_id", "line_no");
        Snapshot.Table notes = table("notes", "id");
        notes.columns.add(col("line_order_id", Types.BIGINT));
        notes.columns.add(col("line_no", Types.INTEGER));
        notes.foreignKeys.add(fk(List.of("line_order_id", "line_no"), "lines", List.of("order_id", "line_no")));
        // a foreign key to a unique column other than the key is a plain column, so nothing else is left out
        Snapshot.Table codes = table("codes", "id");
        codes.columns.add(col("code", Types.BIGINT));
        Snapshot.UniqueKey uk = new Snapshot.UniqueKey();
        uk.name = "uk_code";
        uk.columns.add("code");
        codes.uniqueKeys.add(uk);
        Snapshot.Table uses = table("uses", "id");
        uses.columns.add(col("code_ref", Types.BIGINT));
        uses.foreignKeys.add(fk(List.of("code_ref"), "codes", List.of("code")));
        Snapshot.Table more = table("more", "id");
        more.columns.add(col("use_id", Types.BIGINT));
        more.foreignKeys.add(fk(List.of("use_id"), "uses", List.of("id")));
        Snapshot.Table tags = table("tags", "id");
        tags.column("id").identity = true;
        s.tables.addAll(List.of(lines, notes, codes, uses, more, tags));

        GeneratorConfig cfg = GeneratorConfig.defaults();
        cfg.contract.kind = "ADMIN_TEMPLATE";
        cfg.contract.migrationVersion = 7;
        ApiModel model = new DatabaseMapper().map(s, new GenerationPlan(), cfg, "App", "");
        AdminTemplateModule m = AdminTemplateModule.build(model, cfg, new ArrayList<>());

        assertThat(m.entities).extracting(x -> x.className).containsExactlyInAnyOrder("Note", "Code", "Use", "More", "Tag");
        assertThat(m.warnings).singleElement().satisfies(w -> assertThat(w).contains("Line ", "composite key"));
        AdminTemplateModule.Entity use = m.entities.stream().filter(x -> x.className.equals("Use")).findFirst().orElseThrow();
        assertThat(use.relations).isEmpty();
        assertThat(use.columns).extracting(c -> c.ddlName).contains("code_ref");
        assertThat(m.entities.stream().filter(x -> x.className.equals("More")).findFirst().orElseThrow().relations)
            .extracting(r -> r.target.className).containsExactly("Use");
        AdminTemplateModule.Entity tag = m.entities.stream().filter(x -> x.className.equals("Tag")).findFirst().orElseThrow();
        assertThat(tag.baseEntity).as("created tables get BaseEntity's columns").isTrue();
        assertThat(tag.entityName).as("unique in the project's persistence unit").isEqualTo("AppTag");
        assertThat(tag.ddlTable).isEqualTo("app_tags");
        assertThat(m.entities.stream().filter(x -> x.className.equals("Code")).findFirst().orElseThrow().baseEntity)
            .as("an assigned key cannot be BaseEntity's identity").isFalse();
        assertThat(m.migrationFile).isEqualTo("V7__app.sql");
        assertThat(m.permission).isEqualTo("app.manage");
    }

    private static Snapshot.ForeignKey fk(List<String> cols, String target, List<String> refs) {
        Snapshot.ForeignKey f = new Snapshot.ForeignKey();
        f.name = "fk_" + cols.get(0);
        f.columns.addAll(cols);
        f.refSchema = "app";
        f.refTable = target;
        f.refColumns.addAll(refs);
        return f;
    }

    private static Snapshot.Table table(String name, String... key) {
        Snapshot.Table t = new Snapshot.Table();
        t.schema = "app";
        t.name = name;
        for (String k : key) t.columns.add(col(k, Types.BIGINT));
        t.primaryKey = List.of(key);
        for (int i = 0; i < t.columns.size(); i++) t.columns.get(i).position = i + 1;
        return t;
    }

    private static Snapshot.Column col(String name, int type) {
        Snapshot.Column c = new Snapshot.Column();
        c.name = name;
        c.vendorType = type == Types.INTEGER ? "int4" : "int8";
        c.jdbcType = type;
        c.nullable = false;
        c.position = 99;
        return c;
    }
}
