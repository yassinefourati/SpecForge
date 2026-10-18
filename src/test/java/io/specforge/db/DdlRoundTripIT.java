package io.specforge.db;

import io.specforge.config.GeneratorConfig;
import io.specforge.ddl.DdlWriter;
import io.specforge.mapper.DatabaseMapper;
import io.specforge.model.Model.ApiModel;
import io.specforge.plan.GenerationPlan;
import io.specforge.snapshot.Snapshot;
import io.specforge.source.ConnectionSettings;
import io.specforge.source.SourceKind;
import io.specforge.source.jdbc.JdbcConnector;
import io.specforge.suggest.SuggestionEngine;
import io.specforge.support.Engines;
import io.specforge.support.Generation;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-2: a schema written by DdlWriter is the same schema. Fixture → snapshot → DDL → a fresh database → snapshot →
 * the generated Java sources are identical to the first ones (every mapping fact: names, types, keys, sequences,
 * nullability, unique keys, relations, defaults that matter).
 */
class DdlRoundTripIT {

    @TestFactory
    Stream<DynamicTest> schemaSurvivesTheRoundTrip() {
        return Engines.selected().stream().filter(DdlWriter.SUPPORTED::contains)
            .map(kind -> DynamicTest.dynamicTest(kind.name(), () -> roundTrip(kind)));
    }

    private static void roundTrip(SourceKind kind) throws Exception {
        String fixture = "src/test/resources/fixtures/engines/shop-" + kind.name().toLowerCase(Locale.ROOT) + ".sql";
        ConnectionSettings first = Engines.database(kind, Path.of(fixture));
        Snapshot a = new JdbcConnector(first).introspect(first.schemas, null);

        List<String> tables = selected(a);
        DdlWriter.Result ddl = DdlWriter.write(a, tables);
        assertThat(ddl.problems).as("DDL problems").isEmpty();
        Path script = Files.createTempFile("specforge-ddl", ".sql");
        Files.writeString(script, ddl.sql);

        ConnectionSettings second = Engines.database(kind, script);
        Snapshot b = new JdbcConnector(second).introspect(second.schemas, null);

        // MySQL and MariaDB: the schema is the database, a different one per fixture
        Map<String, String> before = java(a, first, a.schemas);
        Map<String, String> after = java(b, second, b.schemas);
        assertThat(after.keySet()).isEqualTo(before.keySet());
        for (String file : before.keySet()) assertThat(after.get(file)).as(file + "\n--- DDL ---\n" + ddl.sql).isEqualTo(before.get(file));
    }

    /** The tables the default plan generates, plus the join tables it maps as many-to-many. */
    static List<String> selected(Snapshot s) {
        SuggestionEngine.Result r = new SuggestionEngine().build(s, new GenerationPlan());
        List<String> tables = new ArrayList<>();
        r.entities.forEach(e -> tables.add(e.qualifiedName));
        tables.addAll(r.joinTables);
        return tables;
    }

    private static Map<String, String> java(Snapshot s, ConnectionSettings cs, List<String> schemas) throws Exception {
        GeneratorConfig cfg = Generation.config(Files.createTempDirectory("specforge-rt"));
        cfg.basePackage = "com.example.shop";
        ApiModel model = new DatabaseMapper().map(s, new GenerationPlan(), cfg, "Shop", "jdbc:same");
        Map<String, String> out = new TreeMap<>();
        Generation.byPath(Generation.generate(model, cfg)).forEach((k, v) -> {
            if (!k.endsWith(".java")) return;
            String text = v;
            for (String schema : schemas) if (schema.startsWith("fixture_")) text = text.replace(schema + ".", "<db>.");
            out.put(k, text);
        });
        return out;
    }
}
