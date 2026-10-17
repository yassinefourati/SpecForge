package io.specforge.suggest;

import io.specforge.model.DbModel.DbEntity;
import io.specforge.plan.GenerationPlan;
import io.specforge.plan.GenerationPlan.ColumnDecision;
import io.specforge.plan.GenerationPlan.EntityDecision;
import io.specforge.plan.GenerationPlan.UseCaseOperation;
import io.specforge.snapshot.Snapshot;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** MAP-11 and OPS-3 … OPS-5 rules without a database: decisions win, impossible ones warn and keep the suggestion. */
class MappingDecisionsTest {

    /** orders(id identity, customer_id → customers, code, note NOT NULL), customers(id), tags(id uuid), order_tags(order_id, tag_id). */
    private static Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.engine = "POSTGRESQL";
        s.schemas = List.of("shop");
        Snapshot.Table customers = table("customers", col("id", "int8", Types.BIGINT, false));
        customers.primaryKey = List.of("id");
        customers.column("id").identity = true;
        Snapshot.Table orders = table("orders", col("id", "int8", Types.BIGINT, false), col("customer_id", "int8", Types.BIGINT, false),
            col("code", "varchar", Types.VARCHAR, true), col("note", "varchar", Types.VARCHAR, false), col("total", "numeric", Types.NUMERIC, true));
        orders.primaryKey = List.of("id");
        orders.column("id").identity = true;
        orders.foreignKeys.add(fk("customer_id", "customers"));
        Snapshot.Table tags = table("tags", col("id", "uuid", Types.OTHER, false));
        tags.primaryKey = List.of("id");
        Snapshot.Table orderTags = table("order_tags", col("order_id", "int8", Types.BIGINT, false), col("tag_id", "uuid", Types.OTHER, false));
        orderTags.primaryKey = List.of("order_id", "tag_id");
        orderTags.foreignKeys.add(fk("order_id", "orders"));
        orderTags.foreignKeys.add(fk("tag_id", "tags"));
        s.tables.addAll(List.of(customers, orders, tags, orderTags));
        Snapshot.Sequence seq = new Snapshot.Sequence();
        seq.schema = "shop";
        seq.name = "orders_seq";
        seq.increment = 50;
        s.sequences.add(seq);
        return s;
    }

    private static Snapshot.Table table(String name, Snapshot.Column... cols) {
        Snapshot.Table t = new Snapshot.Table();
        t.schema = "shop";
        t.name = name;
        int pos = 1;
        for (Snapshot.Column c : cols) {
            c.position = pos++;
            t.columns.add(c);
        }
        return t;
    }

    private static Snapshot.Column col(String name, String vendor, int jdbc, boolean nullable) {
        Snapshot.Column c = new Snapshot.Column();
        c.name = name;
        c.vendorType = vendor;
        c.jdbcType = jdbc;
        c.nullable = nullable;
        if (vendor.equals("numeric")) {
            c.size = 10;
            c.scale = 2;
        }
        return c;
    }

    private static Snapshot.ForeignKey fk(String column, String target) {
        Snapshot.ForeignKey f = new Snapshot.ForeignKey();
        f.name = "fk_" + column;
        f.columns.add(column);
        f.refSchema = "shop";
        f.refTable = target;
        f.refColumns.add("id");
        return f;
    }

    private static DbEntity entity(SuggestionEngine.Result r, String className) {
        return r.entities.stream().filter(e -> e.className.equals(className)).findFirst()
            .orElseThrow(() -> new AssertionError("no " + className + " in " + r.entities.stream().map(e -> e.className).toList()));
    }

    @Test
    void sequenceKeyChosenByTheUser() {
        GenerationPlan p = new GenerationPlan();
        EntityDecision d = new EntityDecision();
        d.idStrategy = "SEQUENCE";
        d.sequenceName = "shop.orders_seq";
        p.entities.put("shop.orders", d);
        DbEntity orders = entity(new SuggestionEngine().build(snapshot(), p), "Order");
        assertThat(orders.id.strategy).isEqualTo("SEQUENCE");
        assertThat(orders.id.sequenceName).isEqualTo("orders_seq");
        assertThat(orders.id.allocationSize).as("from INCREMENT BY").isEqualTo(50);
    }

    @Test
    void impossibleKeyStrategyKeepsTheSuggestion() {
        GenerationPlan p = new GenerationPlan();
        EntityDecision d = new EntityDecision();
        d.idStrategy = "UUID";
        p.entities.put("shop.orders", d);
        SuggestionEngine.Result r = new SuggestionEngine().build(snapshot(), p);
        assertThat(entity(r, "Order").id.strategy).isEqualTo("IDENTITY");
        assertThat(r.warnings).anyMatch(w -> w.contains("UUID"));
    }

    @Test
    void columnNamesTypesAndInclusion() {
        GenerationPlan p = new GenerationPlan();
        EntityDecision d = new EntityDecision();
        ColumnDecision code = new ColumnDecision();
        code.name = "reference";
        code.sort = false;
        d.columns.put("code", code);
        ColumnDecision total = new ColumnDecision();
        total.javaType = "Double";
        d.columns.put("total", total);
        ColumnDecision note = new ColumnDecision();
        note.include = false;
        d.columns.put("note", note);
        p.entities.put("shop.orders", d);
        DbEntity orders = entity(new SuggestionEngine().build(snapshot(), p), "Order");
        assertThat(orders.fields).extracting(f -> f.name).containsExactly("reference", "total");
        assertThat(orders.fields.get(1).javaType).isEqualTo("Double");
        assertThat(orders.fields.get(1).precision).as("decimal precision belongs to BigDecimal").isNull();
        assertThat(orders.sortable).doesNotContain("reference");
        assertThat(orders.readOnly).as("a required column left out makes inserts impossible").isTrue();
        assertThat(orders.readOnlyReason).contains("note");
    }

    @Test
    void joinTableAsItsOwnEntity() {
        assertThat(new SuggestionEngine().build(snapshot(), new GenerationPlan()).joinTables).containsExactly("shop.order_tags");
        GenerationPlan p = new GenerationPlan();
        EntityDecision d = new EntityDecision();
        d.asEntity = true;
        p.entities.put("shop.order_tags", d);
        SuggestionEngine.Result r = new SuggestionEngine().build(snapshot(), p);
        assertThat(r.joinTables).isEmpty();
        assertThat(entity(r, "OrderTag").id.composite()).isTrue();
    }

    @Test
    void inverseCollectionIsOptIn() {
        assertThat(entity(new SuggestionEngine().build(snapshot(), new GenerationPlan()), "Customer").inverses).isEmpty();
        GenerationPlan p = new GenerationPlan();
        EntityDecision d = new EntityDecision();
        GenerationPlan.RelationDecision rel = new GenerationPlan.RelationDecision();
        rel.inverse = true;
        d.relations.put("customer_id", rel);
        p.entities.put("shop.orders", d);
        DbEntity customer = entity(new SuggestionEngine().build(snapshot(), p), "Customer");
        assertThat(customer.inverses).extracting(i -> i.name + " mappedBy " + i.mappedBy).containsExactly("orders mappedBy customer");
    }

    @Test
    void movedOperationsLeaveTheEntityControllerButNotItsService() {
        GenerationPlan p = new GenerationPlan();
        GenerationPlan.ControllerDecision c = new GenerationPlan.ControllerDecision();
        c.name = "Sales";
        UseCaseOperation move = new UseCaseOperation();
        move.kind = "ENTITY";
        move.entity = "shop.customers";
        move.operation = "create";
        UseCaseOperation keep = new UseCaseOperation();
        keep.kind = "ENTITY";
        keep.entity = "shop.customers";
        keep.operation = "read";
        keep.path = "/people/{id}";
        keep.keepInEntityController = true;
        c.operations.addAll(List.of(move, keep));
        p.controllers.add(c);
        SuggestionEngine.Result r = new SuggestionEngine().build(snapshot(), p);
        DbEntity customer = entity(r, "Customer");
        assertThat(customer.op("create")).isFalse();
        assertThat(customer.serviceOp("create")).isTrue();
        assertThat(customer.op("read")).isTrue();
        assertThat(r.useCases).hasSize(1);
        assertThat(r.useCases.get(0).path).isEqualTo("sales");
        assertThat(r.useCases.get(0).operations).extracting(o -> o.method + " " + o.httpMethod() + " " + o.path)
            .containsExactly("createCustomer POST /customers", "readCustomer GET /people/{id}");
    }

    @Test
    void useCaseDecisionsThatCannotWorkAreReported() {
        GenerationPlan p = new GenerationPlan();
        GenerationPlan.ControllerDecision c = new GenerationPlan.ControllerDecision();
        c.name = "Sales";
        UseCaseOperation noLink = new UseCaseOperation();
        noLink.kind = "CHILDREN";
        noLink.entity = "shop.orders";
        noLink.child = "shop.customers";
        UseCaseOperation badPath = new UseCaseOperation();
        badPath.kind = "WITH_CHILDREN";
        badPath.entity = "shop.customers";
        badPath.child = "shop.orders";
        badPath.path = "/customers/{customerId}";
        UseCaseOperation clash = new UseCaseOperation();
        clash.kind = "ENTITY";
        clash.entity = "shop.customers";
        clash.operation = "list";
        clash.keepInEntityController = true;
        c.path = "/";
        c.operations.addAll(List.of(noLink, badPath, clash));
        p.controllers.add(c);
        SuggestionEngine.Result r = new SuggestionEngine().build(snapshot(), p);
        assertThat(r.warnings).anyMatch(w -> w.contains("Customer has no foreign key to Order"));
        assertThat(r.warnings).anyMatch(w -> w.contains("must name {id}"));
        // at the root, both remaining operations collide with Customer's own controller
        assertThat(r.warnings).anyMatch(w -> w.contains("GET /customers is already served by the Customer controller"));
        assertThat(r.warnings).anyMatch(w -> w.contains("GET /customers/{} is already served by the Customer controller"));
        assertThat(r.useCases).isEmpty();
    }

    @Test
    void controllerRemovedAndRenamed() {
        GenerationPlan p = new GenerationPlan();
        EntityDecision gone = new EntityDecision();
        gone.controller = false;
        p.entities.put("shop.tags", gone);
        EntityDecision renamed = new EntityDecision();
        renamed.controllerName = "PurchaseController";
        renamed.path = "/purchases/";
        p.entities.put("shop.orders", renamed);
        SuggestionEngine.Result r = new SuggestionEngine().build(snapshot(), p);
        assertThat(entity(r, "Tag").hasController()).isFalse();
        assertThat(entity(r, "Order").controllerNameOverride).isEqualTo("PurchaseController");
        assertThat(entity(r, "Order").path).isEqualTo("purchases");
    }
}
