package io.specforge.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The user's database-flow decisions (PLAN-1). It stores decisions only (PLAN-5): anything the user did not decide
 * is recomputed from the snapshot by the suggestion engine. JSON contract: docs/contracts/plan.schema.json.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GenerationPlan {
    public int planVersion = 1;
    public String specforgeVersion = "";
    public String rulesetVersion = "";
    public Source source = new Source();
    public Select select = new Select();
    /** by qualified table name, e.g. "administration.users" */
    public Map<String, EntityDecision> entities = new LinkedHashMap<>();
    /** Use-case controllers the user composed from several entities (OPS-3); empty = one controller per entity. */
    public List<ControllerDecision> controllers = new ArrayList<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Source {
        public String engine = "";
        public String connectionId = "";
        public List<String> schemas = new ArrayList<>();
    }

    /**
     * Which tables become entities (SEL-1, SEL-2). Patterns are qualified names where "*" matches any run of
     * characters ("administration.*", "app.order_*"). Empty include = every table of the snapshot.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Select {
        public List<String> include = new ArrayList<>();
        public List<String> exclude = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EntityDecision {
        public String className;
        /** operation name (create, read, list, update, patch, delete) → enabled (OPS-2) */
        public Map<String, Boolean> operations = new LinkedHashMap<>();
        /** chosen key for a table without a primary key (SEL-4) */
        public List<String> keyColumns = new ArrayList<>();
        /** force read-only (SEL-4) */
        public Boolean readOnly;
        /** API path of the entity's controller, without the leading "/" (OPS-5) */
        public String path;
        /** class name of the entity's controller (OPS-5); null = the naming pattern */
        public String controllerName;
        /** false = no controller for this entity; its entity, repository and service stay (OPS-5) */
        public Boolean controller;
        /** IDENTITY | SEQUENCE | UUID | ASSIGNED (MAP-1, MAP-11) */
        public String idStrategy;
        /** with idStrategy SEQUENCE: the sequence, optionally "schema.name" */
        public String sequenceName;
        public Integer allocationSize;
        /** a pure join table generated as an entity of its own instead of a many-to-many (MAP-4) */
        public Boolean asEntity;
        /** by column name (MAP-3, MAP-11) */
        public Map<String, ColumnDecision> columns = new LinkedHashMap<>();
        /** by foreign-key column (MAP-4, MAP-11) */
        public Map<String, RelationDecision> relations = new LinkedHashMap<>();
        /**
         * Foreign keys whose target is not selected (SEL-3), by FK column: INCLUDE_TARGET is handled by selection;
         * ID (map the column as a plain value, the default) or IGNORE (leave the column out).
         */
        public Map<String, String> unselectedForeignKeys = new LinkedHashMap<>();
    }

    /** One column's mapping (MAP-3, MAP-6, MAP-11). Null fields keep the suggestion. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ColumnDecision {
        /** Java property name */
        public String name;
        /** simple Java type name, e.g. "Long", "BigDecimal", "OffsetDateTime" */
        public String javaType;
        /** false = the column is left out of the entity and the API */
        public Boolean include;
        /** offered as an equality filter on the list endpoint (OPS-1) */
        public Boolean filter;
        /** offered for sorting the list endpoint (OPS-1) */
        public Boolean sort;
        /** searched by the list endpoint's free-text parameter q (CONTRACT-3) */
        public Boolean search;
        /** offered as a range filter, &lt;property&gt;From / &lt;property&gt;To (CONTRACT-3) */
        public Boolean range;
    }

    /** One foreign key's mapping (MAP-4, MAP-11). Null fields keep the suggestion. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RelationDecision {
        /** the many-to-one property, e.g. "customer" */
        public String name;
        /** the DTO property carrying the referenced id, e.g. "customerId" */
        public String dtoName;
        /** true = also map the inverse one-to-many on the referenced entity (opt-in) */
        public Boolean inverse;
        /** name of the inverse collection, e.g. "orders" */
        public String inverseName;
    }

    /** A use-case controller (OPS-3): a name, a base path and operations taken from several entities. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ControllerDecision {
        /** base name: "Checkout" becomes CheckoutController and CheckoutService */
        public String name;
        /** base path, e.g. "checkout" */
        public String path;
        public List<UseCaseOperation> operations = new ArrayList<>();
    }

    /**
     * One operation of a use-case controller. Kinds: ENTITY (an entity's create / read / list / update / patch /
     * delete), AGGREGATE_CREATE and AGGREGATE_UPDATE (parent and children in one transaction), WITH_CHILDREN (read a
     * parent with its children), CHILDREN (list a parent's children).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UseCaseOperation {
        public String kind;
        /** the entity, or the parent for the parent / children shapes (qualified table name) */
        public String entity;
        /** for ENTITY: create | read | list | update | patch | delete */
        public String operation;
        /** the child table for the parent / children shapes (qualified table name) */
        public String child;
        /** the child's foreign-key column to the parent, when it has several */
        public String via;
        /** Java method name; null = derived */
        public String name;
        /** path below the controller's base path; null = derived */
        public String path;
        /** for ENTITY: also keep the operation in the entity's own controller (OPS-4); default false */
        public Boolean keepInEntityController;
    }

    /** ARCH-3: the module of each table (qualified name → module), for the modular architecture; absent = the suggestion */
    public Map<String, String> modules = new LinkedHashMap<>();

    public static boolean matches(String pattern, String qualifiedName) {
        StringBuilder rx = new StringBuilder();
        for (char ch : pattern.toCharArray()) {
            if (ch == '*') rx.append(".*");
            else rx.append(java.util.regex.Pattern.quote(String.valueOf(ch)));
        }
        return qualifiedName.matches(rx.toString());
    }

    public boolean selected(String qualifiedName) {
        boolean in = select.include.isEmpty();
        for (String p : select.include) if (matches(p, qualifiedName)) in = true;
        for (String p : select.exclude) if (matches(p, qualifiedName)) in = false;
        return in;
    }
}
