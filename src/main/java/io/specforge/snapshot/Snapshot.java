package io.specforge.snapshot;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything introspected from a source at one moment (INT-1), engine-neutral. Never contains secrets or row data.
 * The JSON form is the contract in docs/contracts/snapshot.schema.json; bump {@link #schemaVersion} on change.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Snapshot {
    public int schemaVersion = 1;
    /** Fingerprint of the connection settings and schemas (SEC-6); also the snapshot's id. */
    public String id = "";
    public String engine = "";
    public String serverVersion = "";
    public String capturedAt = "";
    /** Display name of the source (the saved connection's name, else the database name). */
    public String title = "";
    /** JDBC URL without credentials or query parameters; the default of DB_URL in generated configuration. */
    public String jdbcUrl = "";
    public List<String> schemas = new ArrayList<>();
    public List<Table> tables = new ArrayList<>();
    public List<Sequence> sequences = new ArrayList<>();
    public List<String> warnings = new ArrayList<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Table {
        public String schema = "";
        public String name = "";
        public String comment = "";
        /** null when the engine gives no estimate */
        public Long rowEstimate;
        public List<Column> columns = new ArrayList<>();
        /** Primary key columns in key order; empty when the table has none (SEL-4). */
        public List<String> primaryKey = new ArrayList<>();
        public List<ForeignKey> foreignKeys = new ArrayList<>();
        public List<UniqueKey> uniqueKeys = new ArrayList<>();

        @JsonIgnore
        public String qualifiedName() {
            return schema.isEmpty() ? name : schema + "." + name;
        }

        @JsonIgnore
        public Column column(String name) {
            for (Column c : columns) if (c.name.equals(name)) return c;
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Column {
        public String name = "";
        /** The engine's own type name, e.g. "jsonb", "timestamptz", "bigserial" (MAP-6 matches on it first). */
        public String vendorType = "";
        /** java.sql.Types code */
        public int jdbcType;
        /** length for character types, precision for numeric types */
        public Integer size;
        public Integer scale;
        public boolean nullable = true;
        public String defaultValue;
        /** identity or auto-increment, as reported by the driver */
        public boolean autoIncrement;
        /** SQL-standard identity column (GENERATED … AS IDENTITY) */
        public boolean identity;
        /** computed / generated column (read-only) */
        public boolean generated;
        public String comment = "";
        public int position;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ForeignKey {
        public String name = "";
        public List<String> columns = new ArrayList<>();
        public String refSchema = "";
        public String refTable = "";
        public List<String> refColumns = new ArrayList<>();
        /** CASCADE | SET NULL | SET DEFAULT | RESTRICT | NO ACTION */
        public String onUpdate = "NO ACTION";
        public String onDelete = "NO ACTION";

        @JsonIgnore
        public String refQualifiedName() {
            return refSchema.isEmpty() ? refTable : refSchema + "." + refTable;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UniqueKey {
        public String name = "";
        public List<String> columns = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Sequence {
        public String schema = "";
        public String name = "";
        public long start = 1;
        public long increment = 1;
        /** owning table and column when the engine records it (PostgreSQL OWNED BY) */
        public String ownedBySchema = "";
        public String ownedByTable = "";
        public String ownedByColumn = "";
    }

    @JsonIgnore
    public Table table(String qualifiedName) {
        for (Table t : tables) if (t.qualifiedName().equals(qualifiedName)) return t;
        return null;
    }
}
