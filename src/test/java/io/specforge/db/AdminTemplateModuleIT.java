package io.specforge.db;

import io.specforge.config.GeneratorConfig;
import io.specforge.contract.AdminTemplateModule;
import io.specforge.gen.GeneratedFile;
import io.specforge.mapper.DatabaseMapper;
import io.specforge.model.Model.ApiModel;
import io.specforge.model.Model.Msg;
import io.specforge.plan.GenerationPlan;
import io.specforge.snapshot.Snapshot;
import io.specforge.source.ConnectionSettings;
import io.specforge.source.SourceKind;
import io.specforge.source.jdbc.JdbcConnector;
import io.specforge.support.AdminTemplateCheckout;
import io.specforge.support.Engines;
import io.specforge.support.Generation;
import io.specforge.support.Maven;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * CONTRACT-2 … CONTRACT-5 end to end: a module generated from a PostgreSQL schema, written into a copy of an
 * admin-template checkout, integrated by following its instructions, then the project's complete build: its unit
 * tests, its integration tests (Flyway on PostgreSQL, the permission check at startup, Hibernate's schema
 * validation) and the generated ModuleIT. Runs with -Dspecforge.adminTemplate=&lt;checkout&gt; (needs Docker).
 */
class AdminTemplateModuleIT {

    @Test
    void theModuleBuildsAndPassesTheProjectsTests() throws Exception {
        Path checkout = AdminTemplateCheckout.source();
        assumeTrue(checkout != null, "set -Dspecforge.adminTemplate to an admin-template checkout");

        ConnectionSettings cs = Engines.database(SourceKind.POSTGRESQL, Path.of("src/test/resources/fixtures/engines/shop-postgresql.sql"));
        Snapshot snap = new JdbcConnector(cs).introspect(List.of("public"), null);
        boolean frontend = AdminTemplateCheckout.withFrontend();
        Path root = AdminTemplateCheckout.copy(checkout, frontend);
        GeneratorConfig cfg = Generation.config(root);
        cfg.contract.kind = "ADMIN_TEMPLATE";
        cfg.contract.projectDir = root.toString();
        cfg.contract.module = "shop";
        cfg.lists.textSearch = "Q";
        cfg.lists.rangeFilters = true;
        cfg.contract.frontend = frontend;
        ApiModel model = new DatabaseMapper().map(snap, new GenerationPlan(), cfg, "Shop", cs.jdbcUrl());
        List<GeneratedFile> files = Generation.generate(model, cfg);
        assertThat(files).allSatisfy(f -> assertThat(Path.of(f.absPath)).startsWithRaw(root.toAbsolutePath().normalize()));
        Maven.write(files);

        AdminTemplateModule module = AdminTemplateModule.build(model, cfg, new ArrayList<Msg>());
        assertThat(module.migrationVersion).as("next free Flyway version").isGreaterThan(1);
        assertThat(Files.readString(root.resolve("backend/specforge-shop.md"))).contains("NOT_AUDITED", "FormConstraintsTest");
        AdminTemplateCheckout.integrate(root, module);

        Maven.Result build = Maven.run(root.resolve("backend"), "verify");
        assertThat(build.ok()).as("admin-template with the module does not pass its build:%n%s", build.errors()).isTrue();

        if (frontend) {
            // CONTRACT-6: the project's own checks over the pages; messages.test checks every text key in en and fr
            Path web = root.resolve("frontend/apps/web");
            assertThat(AdminTemplateCheckout.pnpm(root.resolve("frontend"), "install", "--frozen-lockfile").ok()).as("pnpm install").isTrue();
            for (String[] check : List.of(new String[] {"exec", "tsc", "--noEmit"}, new String[] {"exec", "eslint", ".", "--max-warnings", "0"},
                    new String[] {"exec", "vitest", "run"})) {
                Maven.Result r = AdminTemplateCheckout.pnpm(web, check);
                assertThat(r.ok()).as("frontend %s:%n%s", String.join(" ", check), r.output()).isTrue();
            }
        }
    }
}
