package io.specforge.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * What the database-flow templates render: the effective model of one table, i.e. the suggestions computed from
 * the snapshot with the user's plan decisions applied (PLAN-5). Attached to {@code Model.ClassDef#db}.
 */
public final class DbModel {

    private DbModel() {}

    public static class DbEntity {
        public String schema = "";
        public String table = "";
        public String qualifiedName = "";
        public String className = "";
        public String varName = "";
        /** URL path segment, e.g. "user-roles" */
        public String path = "";
        public String comment = "";
        public boolean readOnly;
        public String readOnlyReason = "";
        public DbId id = new DbId();
        /** Columns mapped as plain attributes (FK columns appear as relations instead; key columns live in the id). */
        public List<DbField> fields = new ArrayList<>();
        public List<DbRelation> relations = new ArrayList<>();
        public List<DbManyToMany> manyToMany = new ArrayList<>();
        /** Unique keys by column name, emitted on @Table (MAP-8). */
        public List<List<String>> uniqueColumns = new ArrayList<>();
        /** create, read, list, update, patch, delete exposed by the entity's own controller (OPS-1, OPS-2, OPS-4) */
        public Map<String, Boolean> ops = new LinkedHashMap<>();
        /** operations the service offers: the controller's plus those use-case controllers call (OPS-3) */
        public Map<String, Boolean> serviceOps = new LinkedHashMap<>();
        /** false when the user removed the entity's controller; entity, repository and service stay (OPS-5) */
        public boolean controller = true;
        /** the user's controller class name, "" = the naming pattern (OPS-5) */
        public String controllerNameOverride = "";
        /** opt-in inverse one-to-many collections pointing back from other entities (MAP-4) */
        public List<DbInverse> inverses = new ArrayList<>();
        /** "find children by parent" queries the repository needs for use cases (OPS-3) */
        public List<DbRelation> childQueries = new ArrayList<>();
        /** DTO property names that may be used to filter and sort the list operation */
        public List<String> filterable = new ArrayList<>();
        public List<String> sortable = new ArrayList<>();
        /** DTO properties the free-text parameter q searches, case-insensitively (CONTRACT-3) */
        public List<String> searchable = new ArrayList<>();
        /** DTO properties offered as ranges, &lt;name&gt;From / &lt;name&gt;To (CONTRACT-3) */
        public List<String> rangeable = new ArrayList<>();
        /** REQUEST_RESPONSE style (STYLE-9): {@link #requestName} is written, {@link #dtoName} is read */
        public boolean split;
        // filled in by the generator from the naming patterns and layer packages
        /** the record returned by the API (XxxDto, or XxxResponse when split) */
        public String dtoName = "";
        /** the record the API accepts on create / update; equals {@link #dtoName} unless split */
        public String requestName = "";
        public String mapperName = "";
        public String serviceName = "";
        public String controllerName = "";
        public String repositoryName = "";
        /** ARCH-3: the module the table belongs to (modular architecture), "" otherwise */
        public String module = "";
        /** package of this table's class in each per-entity layer (entities, models, …), from the layout (ARCH-1) */
        public Map<String, String> packages = new LinkedHashMap<>();
        public TreeSet<String> entityImports = new TreeSet<>();
        public TreeSet<String> dtoImports = new TreeSet<>();
        public List<String> warnings = new ArrayList<>();

        public boolean op(String name) {
            return Boolean.TRUE.equals(ops.get(name));
        }

        public boolean serviceOp(String name) {
            return Boolean.TRUE.equals(serviceOps.get(name)) || op(name);
        }

        /** Whether the entity's own controller is generated: not removed and at least one operation left. */
        public boolean hasController() {
            return controller && ops.values().stream().anyMatch(Boolean.TRUE::equals);
        }

        /** The DTO's record components in declaration order: key, plain fields, relation ids, many-to-many id sets. */
        public List<DbField> dtoComponents() {
            List<DbField> out = new ArrayList<>(dtoFields());
            for (DbRelation r : relations) if (r.dtoField != null) out.add(r.dtoField);
            for (DbManyToMany m : manyToMany) {
                DbField f = new DbField();
                f.name = m.dtoName;
                f.cap = m.dtoCap;
                f.javaType = "Set<" + m.targetIdType + ">";
                out.add(f);
            }
            return out;
        }

        /**
         * The components of the record accepted on create / update, in DTO order. Unless split, the same as
         * {@link #dtoComponents()}. Split: only what the mapper copies into the entity, i.e. an assigned key (never a
         * generated one), writable fields, relation ids and many-to-many id sets.
         */
        public List<DbField> requestComponents() {
            if (!split) return dtoComponents();
            List<DbField> out = new ArrayList<>();
            for (DbField f : dtoComponents()) {
                boolean key = id.composite() ? id.keyFields.contains(f) : f == id.field;
                if (key ? !id.generated() : f.writable || !fields.contains(f)) out.add(f);
            }
            return out;
        }

        /** Every value that crosses the API boundary, in DTO order: key fields, plain fields, relation ids. */
        public List<DbField> dtoFields() {
            List<DbField> out = new ArrayList<>();
            if (id.composite()) out.addAll(id.keyFields);
            else if (id.field != null) out.add(id.field);
            out.addAll(fields);
            return out;
        }
    }

    public static class DbId {
        /** SINGLE | COMPOSITE */
        public String kind = "SINGLE";
        /** IDENTITY | SEQUENCE | UUID | ASSIGNED (MAP-7) */
        public String strategy = "ASSIGNED";
        public DbField field;
        /** composite keys: the key's fields, in key order */
        public List<DbField> keyFields = new ArrayList<>();
        public String sequenceSchema = "";
        public String sequenceName = "";
        public long allocationSize = 1;
        /** Java type used for the repository and path variables, e.g. "Long" or "UserRole.Key" */
        public String javaType = "Long";

        public boolean composite() {
            return "COMPOSITE".equals(kind);
        }

        public boolean generated() {
            return !"ASSIGNED".equals(strategy);
        }
    }

    public static class DbField {
        public String column = "";
        public String name = "";
        public String cap = "";
        /** simple Java type name; its import is in the entity/DTO import sets */
        public String javaType = "String";
        /** e.g. "SqlTypes.JSON" for @JdbcTypeCode, "" when none */
        public String jdbcTypeCode = "";
        public boolean nullable = true;
        public Integer length;
        public Integer precision;
        public Integer scale;
        /** false for generated / computed columns */
        public boolean writable = true;
        /** the database fills it when omitted (default value, identity) */
        public boolean dbDefault;
        /** the column's default as the source engine spells it (e.g. "'NEW'::character varying"), null when none */
        public String defaultValue;
        /** ready-made annotations for the DTO, e.g. "@NotNull", "@Size(max = 100)" */
        public List<String> validations = new ArrayList<>();
        /** set when this field is the id of a relation, e.g. "user" for userId (DTO only) */
        public String relation = "";
    }

    public static class DbRelation {
        public String name = "";
        public String cap = "";
        public String targetClass = "";
        /** the target entity's id field, e.g. "userId" (getter getUserId) */
        public String targetIdName = "";
        public String targetIdCap = "";
        /** Java type of the target's key, e.g. "Long" */
        public String targetIdType = "Long";
        public String column = "";
        /** referenced column when it is not the target's primary key, else "" */
        public String referencedColumn = "";
        public boolean nullable = true;
        /** key field this relation maps into (@MapsId), "" when none (MAP-9) */
        public String mapsId = "";
        /** the DTO field carrying the target's id, e.g. userId */
        public DbField dtoField;
        /** the user asked for the inverse one-to-many on the target (MAP-4) */
        public boolean inverse;
        public String inverseName = "";
    }

    public static class DbManyToMany {
        public String name = "";
        public String cap = "";
        public String targetClass = "";
        public String joinSchema = "";
        public String joinTable = "";
        public String joinColumn = "";
        public String inverseJoinColumn = "";
        public String targetIdType = "Long";
        public String targetIdCap = "";
        /** e.g. permissionIds */
        public String dtoName = "";
        public String dtoCap = "";
    }

    /** An inverse one-to-many, e.g. Customer.orders mapped by Order.customer (MAP-4, opt-in). */
    public static class DbInverse {
        public String name = "";
        public String cap = "";
        /** the entity holding the many-to-one */
        public String sourceClass = "";
        /** the many-to-one property on the source, e.g. "customer" */
        public String mappedBy = "";
    }

    /** Spring Data derived-query name of "children of a parent", e.g. findAllByOrder_Id. */
    public static String childQueryName(DbRelation link) {
        return "findAllBy" + link.cap + "_" + link.targetIdCap;
    }

    /** A module of the modular architecture (ARCH-3). */
    public static class DbModule {
        public String name = "";
        public String displayName = "";
        public List<String> tables = new ArrayList<>();
    }

    /** A use-case controller and its transactional service (OPS-3). */
    public static class DbUseCase {
        public String name = "";
        /** ARCH-3: the module it belongs to (its tables' module), "" when not modular */
        public String module = "";
        public String path = "";
        public String controllerName = "";
        public String serviceName = "";
        public List<DbUseCaseOp> operations = new ArrayList<>();
        /** request / response records this use case needs, generated into the models package */
        public List<DbRecord> records = new ArrayList<>();
        /** entities whose services, mappers and repositories the use case's service calls */
        public List<DbEntity> services = new ArrayList<>();
        public List<DbEntity> mappers = new ArrayList<>();
        public List<DbEntity> repositories = new ArrayList<>();
        /** imports of key and value types used in signatures */
        public TreeSet<String> imports = new TreeSet<>();

        public boolean has(String kind) {
            return operations.stream().anyMatch(o -> o.kind.equals(kind));
        }
    }

    public static class DbUseCaseOp {
        /** ENTITY | AGGREGATE_CREATE | AGGREGATE_UPDATE | WITH_CHILDREN | CHILDREN */
        public String kind = "ENTITY";
        /** for ENTITY: create | read | list | update | patch | delete */
        public String operation = "";
        public String method = "";
        /** path below the controller's base path, starting with "/" or "" */
        public String path = "";
        public DbEntity entity;
        /** the child entity of the parent / children shapes */
        public DbEntity child;
        /** the child's many-to-one to the parent */
        public DbRelation link;
        /** the child DTO component the parent's id fills in (aggregates) */
        public String linkComponent = "";
        public String requestRecord = "";
        public String responseRecord = "";
        public String itemRecord = "";
        /** property names in request / response records, e.g. "order" and "orderItems" */
        public String parentProperty = "";
        public String childrenProperty = "";
        /** the repository method finding the children by parent id, e.g. findAllByOrder_OrderId */
        public String childQuery = "";

        public String httpMethod() {
            return switch (kind) {
                case "AGGREGATE_CREATE" -> "POST";
                case "AGGREGATE_UPDATE" -> "PUT";
                case "WITH_CHILDREN", "CHILDREN" -> "GET";
                default -> switch (operation) {
                    case "create" -> "POST";
                    case "update" -> "PUT";
                    case "patch" -> "PATCH";
                    case "delete" -> "DELETE";
                    default -> "GET";
                };
            };
        }

        /** whether the path ends with the entity's key segments */
        public boolean itemPath() {
            return switch (kind) {
                case "ENTITY" -> !operation.equals("list") && !operation.equals("create");
                case "AGGREGATE_CREATE" -> false;
                default -> true;
            };
        }
    }

    /** A generated Java record (request / response of a use case). */
    public static class DbRecord {
        public String name = "";
        public String doc = "";
        public List<DbField> components = new ArrayList<>();
        public TreeSet<String> imports = new TreeSet<>();
    }

    /** Facts about where an ApiModel came from, available to every template (STU-12). */
    public static class SourceFacts {
        /** SPEC | DATABASE */
        public String kind = "SPEC";
        public String engine = "";
        public String engineLabel = "";
        public List<String> schemas = new ArrayList<>();
        /** JDBC URL without credentials, used as the default of DB_URL in generated configuration */
        public String jdbcUrl = "";
        public String initializrId = "";
        public String connectionName = "";
    }
}
