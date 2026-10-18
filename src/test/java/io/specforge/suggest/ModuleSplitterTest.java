package io.specforge.suggest;

import io.specforge.model.DbModel.DbEntity;
import io.specforge.plan.GenerationPlan;
import io.specforge.snapshot.Snapshot;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** ARCH-3 … ARCH-5: module suggestions, the user's modules, and what a module boundary does to the mapping. */
class ModuleSplitterTest {

    /** Two islands: customers ← orders, and products ← stock; plus a lonely audit table. */
    private static Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.engine = "POSTGRESQL";
        s.schemas = List.of("app");
        Snapshot.Table customers = table("customers");
        Snapshot.Table orders = table("orders", "customer_id", "product_id");
        orders.foreignKeys.add(fk("customer_id", "customers"));
        orders.foreignKeys.add(fk("product_id", "products"));
        Snapshot.Table products = table("products");
        Snapshot.Table stock = table("stock", "product_id");
        stock.foreignKeys.add(fk("product_id", "products"));
        Snapshot.Table audit = table("audit");
        s.tables.addAll(List.of(customers, orders, products, stock, audit));
        return s;
    }

    @Test
    void tablesLinkedByForeignKeysAreSuggestedAsOneModuleNamedAfterTheMostReferenced() {
        SuggestionEngine.Result r = new SuggestionEngine().build(snapshot(), new GenerationPlan());
        // orders links both islands, so they are one group, named after products (referenced twice)
        assertThat(entity(r, "Order").module).isEqualTo("product");
        assertThat(entity(r, "Customer").module).isEqualTo("product");
        assertThat(entity(r, "Audit").module).isEqualTo("audit");
        assertThat(r.modules).extracting(m -> m.name).containsExactly("product", "audit");
    }

    @Test
    void theUsersModulesWinAndCrossModuleForeignKeysBecomeIds() {
        GenerationPlan p = new GenerationPlan();
        p.modules.put("app.customers", "sales");
        p.modules.put("app.orders", "Sales");
        p.modules.put("app.products", "catalog");
        p.modules.put("app.stock", "catalog");
        p.modules.put("app.audit", "shared");
        SuggestionEngine.Result r = new SuggestionEngine().modular(true).build(snapshot(), p);
        DbEntity order = entity(r, "Order");
        assertThat(order.module).as("names are lower-cased").isEqualTo("sales");
        assertThat(order.relations).extracting(rel -> rel.targetClass).containsExactly("Customer");
        assertThat(order.fields).extracting(f -> f.name).contains("productId");
        assertThat(order.fields.stream().filter(f -> f.name.equals("productId")).findFirst().orElseThrow().validations).contains("@NotNull");
        assertThat(entity(r, "Stock").relations).as("same module: still a relation").hasSize(1);
        assertThat(entity(r, "Audit").module).as("reserved name refused").isNotEqualTo("shared");
        assertThat(r.warnings).anyMatch(w -> w.contains("'shared'"));
    }

    @Test
    void withoutTheModularArchitectureNothingIsCut() {
        GenerationPlan p = new GenerationPlan();
        p.modules.put("app.products", "catalog");
        SuggestionEngine.Result r = new SuggestionEngine().build(snapshot(), p);
        assertThat(entity(r, "Order").relations).hasSize(2);
    }

    private static DbEntity entity(SuggestionEngine.Result r, String cls) {
        return r.entities.stream().filter(e -> e.className.equals(cls)).findFirst().orElseThrow();
    }

    private static Snapshot.Table table(String name, String... fks) {
        Snapshot.Table t = new Snapshot.Table();
        t.schema = "app";
        t.name = name;
        Snapshot.Column id = new Snapshot.Column();
        id.name = "id";
        id.vendorType = "int8";
        id.jdbcType = Types.BIGINT;
        id.nullable = false;
        id.identity = true;
        id.position = 1;
        t.columns.add(id);
        int pos = 2;
        for (String f : fks) {
            Snapshot.Column c = new Snapshot.Column();
            c.name = f;
            c.vendorType = "int8";
            c.jdbcType = Types.BIGINT;
            c.nullable = false;
            c.position = pos++;
            t.columns.add(c);
        }
        Snapshot.Column label = new Snapshot.Column();
        label.name = "label";
        label.vendorType = "varchar";
        label.jdbcType = Types.VARCHAR;
        label.size = 40;
        label.position = pos;
        t.columns.add(label);
        t.primaryKey = List.of("id");
        return t;
    }

    private static Snapshot.ForeignKey fk(String column, String target) {
        Snapshot.ForeignKey f = new Snapshot.ForeignKey();
        f.name = "fk_" + column;
        f.columns.add(column);
        f.refSchema = "app";
        f.refTable = target;
        f.refColumns.add("id");
        return f;
    }
}
