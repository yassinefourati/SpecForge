package io.specforge.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import io.specforge.plan.GenerationPlan;
import io.specforge.snapshot.Snapshot;
import io.specforge.util.Json;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/** The JSON contracts in docs/contracts list exactly the fields the classes serialize (no silent drift). */
class ContractsTest {

    @Test
    void snapshotSchemaMatchesSnapshot() throws Exception {
        JsonNode schema = read("snapshot.schema.json");
        assertThat(schema.at("/properties/schemaVersion/const").asInt()).isEqualTo(new Snapshot().schemaVersion);
        assertSameFields(schema, Snapshot.class);
        assertSameFields(schema.at("/$defs/table"), Snapshot.Table.class);
        assertSameFields(schema.at("/$defs/column"), Snapshot.Column.class);
        assertSameFields(schema.at("/$defs/foreignKey"), Snapshot.ForeignKey.class);
        assertSameFields(schema.at("/$defs/uniqueKey"), Snapshot.UniqueKey.class);
        assertSameFields(schema.at("/$defs/sequence"), Snapshot.Sequence.class);
    }

    @Test
    void planSchemaMatchesPlan() throws Exception {
        JsonNode schema = read("plan.schema.json");
        assertThat(schema.at("/properties/planVersion/const").asInt()).isEqualTo(new GenerationPlan().planVersion);
        assertSameFields(schema, GenerationPlan.class);
        assertSameFields(schema.at("/properties/source"), GenerationPlan.Source.class);
        assertSameFields(schema.at("/properties/select"), GenerationPlan.Select.class);
        assertSameFields(schema.at("/$defs/entityDecision"), GenerationPlan.EntityDecision.class);
        assertSameFields(schema.at("/$defs/columnDecision"), GenerationPlan.ColumnDecision.class);
        assertSameFields(schema.at("/$defs/relationDecision"), GenerationPlan.RelationDecision.class);
        assertSameFields(schema.at("/$defs/controllerDecision"), GenerationPlan.ControllerDecision.class);
        assertSameFields(schema.at("/$defs/useCaseOperation"), GenerationPlan.UseCaseOperation.class);
    }

    private static JsonNode read(String name) throws Exception {
        return Json.MAPPER.readTree(Path.of("docs/contracts", name).toFile());
    }

    private static void assertSameFields(JsonNode schema, Class<?> type) {
        Set<String> documented = new TreeSet<>();
        for (Iterator<String> it = schema.path("properties").fieldNames(); it.hasNext(); ) documented.add(it.next());
        Set<String> serialized = new TreeSet<>();
        for (Field f : type.getFields()) if (!Modifier.isStatic(f.getModifiers())) serialized.add(f.getName());
        assertThat(documented).as(type.getSimpleName()).isEqualTo(serialized);
    }
}
