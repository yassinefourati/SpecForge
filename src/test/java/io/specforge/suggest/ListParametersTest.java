package io.specforge.suggest;

import io.specforge.config.GeneratorConfig;
import io.specforge.mapper.DatabaseMapper;
import io.specforge.model.DbModel.DbEntity;
import io.specforge.model.Model.ApiModel;
import io.specforge.plan.GenerationPlan;
import io.specforge.plan.GenerationPlan.ColumnDecision;
import io.specforge.plan.GenerationPlan.EntityDecision;
import io.specforge.snapshot.Snapshot;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** CONTRACT-3: which columns q searches and which get ranges, as suggested and as decided; parameter clashes. */
class ListParametersTest {

    private static Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.engine = "POSTGRESQL";
        s.schemas = List.of("app");
        Snapshot.Table users = new Snapshot.Table();
        users.schema = "app";
        users.name = "users";
        users.columns.addAll(List.of(
            col("id", "int8", Types.BIGINT, null), col("email", "varchar", Types.VARCHAR, 120),
            col("password_hash", "varchar", Types.VARCHAR, 100), col("api_token", "varchar", Types.VARCHAR, 64),
            col("bio", "text", Types.VARCHAR, null), col("age", "int4", Types.INTEGER, null),
            col("created_at", "timestamptz", Types.TIMESTAMP_WITH_TIMEZONE, null), col("q", "varchar", Types.VARCHAR, 10)));
        for (int i = 0; i < users.columns.size(); i++) users.columns.get(i).position = i + 1;
        users.primaryKey = List.of("id");
        users.column("id").identity = true;
        s.tables.add(users);
        return s;
    }

    private static Snapshot.Column col(String name, String vendor, int jdbc, Integer size) {
        Snapshot.Column c = new Snapshot.Column();
        c.name = name;
        c.vendorType = vendor;
        c.jdbcType = jdbc;
        c.size = size;
        c.nullable = !name.equals("id");
        return c;
    }

    private static DbEntity users(GenerationPlan p) {
        return new SuggestionEngine().build(snapshot(), p).entities.get(0);
    }

    @Test
    void suggestionsSkipSecretsAndUnboundedText() {
        DbEntity u = users(new GenerationPlan());
        assertThat(u.searchable).contains("email", "q").doesNotContain("passwordHash", "apiToken", "bio", "age");
        assertThat(u.rangeable).containsExactlyInAnyOrder("age", "createdAt");
    }

    @Test
    void decisionsOverrideSuggestions() {
        GenerationPlan p = new GenerationPlan();
        EntityDecision d = new EntityDecision();
        ColumnDecision bio = new ColumnDecision();
        bio.search = true;
        ColumnDecision email = new ColumnDecision();
        email.search = false;
        ColumnDecision age = new ColumnDecision();
        age.range = false;
        ColumnDecision emailRange = email;
        emailRange.range = true;
        d.columns.put("bio", bio);
        d.columns.put("email", email);
        d.columns.put("age", age);
        p.entities.put("app.users", d);
        DbEntity u = users(p);
        assertThat(u.searchable).contains("bio").doesNotContain("email");
        assertThat(u.rangeable).containsExactly("createdAt");
        assertThat(u.warnings).anyMatch(w -> w.contains("email") && w.contains("range"));
    }

    @Test
    void optionsOffMeansNoParameters() {
        ApiModel m = new DatabaseMapper().map(snapshot(), new GenerationPlan(), GeneratorConfig.defaults(), "App", "");
        assertThat(m.entities.get(0).db.searchable).isEmpty();
        assertThat(m.entities.get(0).db.rangeable).isEmpty();
    }

    @Test
    void aFilterNamedQDisablesTextSearchWithAWarning() {
        GeneratorConfig cfg = GeneratorConfig.defaults();
        cfg.lists.textSearch = "Q";
        cfg.lists.rangeFilters = true;
        ApiModel m = new DatabaseMapper().map(snapshot(), new GenerationPlan(), cfg, "App", "");
        DbEntity u = m.entities.get(0).db;
        assertThat(u.filterable).contains("q");
        assertThat(u.searchable).isEmpty();
        assertThat(u.rangeable).containsExactlyInAnyOrder("age", "createdAt");
        assertThat(m.messages).anyMatch(x -> x.text.contains("no text search"));
    }

    @Test
    void rolesAreValidatedAndJwtRulesUseThem() {
        GeneratorConfig cfg = GeneratorConfig.defaults();
        cfg.security.databaseAuth = "JWT";
        cfg.security.writeRole = "admin'); drop";
        assertThat(cfg.validate()).anyMatch(e -> e.contains("Role"));
        cfg.security.writeRole = "ROLE_admin";
        ApiModel m = new DatabaseMapper().map(snapshot(), new GenerationPlan(), cfg, "App", "");
        assertThat(m.security.usesJwt).isTrue();
        assertThat(m.security.usesBasic).isFalse();
        assertThat(m.security.rules).filteredOn(r -> r.method.equals("POST")).allMatch(r -> r.expression.equals("hasRole('admin')"));
        assertThat(m.security.rules).filteredOn(r -> r.method.equals("GET")).allMatch(r -> r.kind.equals("AUTHENTICATED"));
    }
}
