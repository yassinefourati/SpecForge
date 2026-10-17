package io.specforge.suggest;

import io.specforge.snapshot.Snapshot;

import java.sql.Types;
import java.util.Locale;
import java.util.Map;

/**
 * Column → Java type (MAP-6): the engine's own type name first (PostgreSQL reports uuid, jsonb, inet and enums all as
 * JDBC OTHER), then the JDBC type, with precision/scale ranges for numbers. java.time only, never java.util.Date.
 * A column no rule matches is reported unsupported: it is left out with a warning, never mapped to a guessed type.
 */
public final class TypeRules {

    /** The suggestion for one column. {@code javaType} is a simple name; {@code importName} its import, if any. */
    public record Mapping(String javaType, String importName, String jdbcTypeCode, String unsupportedReason) {
        public boolean supported() {
            return unsupportedReason == null;
        }

        static Mapping of(String javaType) {
            return new Mapping(javaType, null, "", null);
        }

        static Mapping of(String javaType, String importName) {
            return new Mapping(javaType, importName, "", null);
        }

        static Mapping unsupported(String reason) {
            return new Mapping(null, null, "", reason);
        }
    }

    private static final Map<String, Mapping> POSTGRES = Map.ofEntries(
        Map.entry("int2", Mapping.of("Short")), Map.entry("smallserial", Mapping.of("Short")),
        Map.entry("int4", Mapping.of("Integer")), Map.entry("serial", Mapping.of("Integer")),
        Map.entry("int8", Mapping.of("Long")), Map.entry("bigserial", Mapping.of("Long")),
        Map.entry("float4", Mapping.of("Float")), Map.entry("float8", Mapping.of("Double")),
        Map.entry("bool", Mapping.of("Boolean")),
        Map.entry("varchar", Mapping.of("String")), Map.entry("bpchar", new Mapping("String", null, "SqlTypes.CHAR", null)), Map.entry("char", new Mapping("String", null, "SqlTypes.CHAR", null)),
        Map.entry("text", Mapping.of("String")), Map.entry("citext", Mapping.of("String")), Map.entry("name", Mapping.of("String")),
        Map.entry("uuid", Mapping.of("UUID", "java.util.UUID")),
        Map.entry("date", Mapping.of("LocalDate", "java.time.LocalDate")),
        Map.entry("time", Mapping.of("LocalTime", "java.time.LocalTime")),
        Map.entry("timetz", Mapping.of("OffsetTime", "java.time.OffsetTime")),
        Map.entry("timestamp", Mapping.of("LocalDateTime", "java.time.LocalDateTime")),
        Map.entry("timestamptz", Mapping.of("OffsetDateTime", "java.time.OffsetDateTime")),
        Map.entry("json", new Mapping("String", null, "SqlTypes.JSON", null)),
        Map.entry("jsonb", new Mapping("String", null, "SqlTypes.JSON", null)),
        Map.entry("bytea", Mapping.of("byte[]")),
        Map.entry("money", Mapping.unsupported("PostgreSQL money is locale-formatted; map it as numeric(19,2) in the database or exclude it")),
        Map.entry("interval", Mapping.unsupported("interval columns are not mapped yet")),
        Map.entry("inet", Mapping.unsupported("network address types are not mapped yet")),
        Map.entry("cidr", Mapping.unsupported("network address types are not mapped yet")),
        Map.entry("macaddr", Mapping.unsupported("network address types are not mapped yet")),
        Map.entry("xml", Mapping.unsupported("xml columns are not mapped yet")),
        Map.entry("tsvector", Mapping.unsupported("full-text search vectors are not mapped")));

    private TypeRules() {}

    private static final Mapping JSON = new Mapping("String", null, "SqlTypes.JSON", null);
    private static final Mapping UUID = Mapping.of("UUID", "java.util.UUID");
    private static final Mapping OFFSET_DATE_TIME = Mapping.of("OffsetDateTime", "java.time.OffsetDateTime");
    private static final Mapping BIG_DECIMAL = Mapping.of("BigDecimal", "java.math.BigDecimal");

    public static Mapping map(String engine, Snapshot.Column c) {
        String vendor = c.vendorType == null ? "" : c.vendorType.toLowerCase(Locale.ROOT);
        // "varchar2(40)", "timestamp(6) with time zone", "int identity" → the type's words without sizes
        String base = vendor.replaceAll("\\(.*?\\)", "").replace(" identity", "").replaceAll("\\s+", " ").trim();
        Mapping m = switch (engine == null ? "" : engine) {
            case "POSTGRESQL" -> postgres(vendor, c);
            case "MYSQL", "MARIADB" -> mysql(base);
            case "SQLSERVER" -> sqlServer(base);
            case "ORACLE" -> oracle(base, c);
            case "DB2" -> db2(base);
            case "H2" -> h2(base);
            case "SQLITE" -> sqlite(base, c);
            default -> null;
        };
        return m != null ? m : byJdbcType(c);
    }

    private static Mapping postgres(String vendor, Snapshot.Column c) {
        if (vendor.equals("numeric") || vendor.equals("decimal")) return numeric(c);
        if (vendor.startsWith("_")) return Mapping.unsupported("array columns (" + c.vendorType + ") are not mapped yet");
        Mapping m = POSTGRES.get(vendor);
        if (m != null) return m;
        if (c.jdbcType == Types.OTHER || c.jdbcType == Types.STRUCT || c.jdbcType == Types.DISTINCT)
            return Mapping.unsupported("type '" + c.vendorType + "' (enum, domain or extension type) is not mapped yet");
        return null;
    }

    private static Mapping mysql(String base) {
        if (base.equals("json")) return JSON;
        if (base.endsWith(" unsigned")) {
            String t = base.substring(0, base.length() - " unsigned".length()).trim();
            return switch (t) {
                case "tinyint", "smallint" -> Mapping.of("Integer");
                case "mediumint", "int", "integer" -> Mapping.of("Long");
                case "bigint" -> Mapping.of("BigInteger", "java.math.BigInteger");
                default -> null;
            };
        }
        if (base.equals("year")) return Mapping.of("Short");
        if (base.equals("enum") || base.equals("set")) return Mapping.of("String");
        if (base.startsWith("geometry") || base.equals("point") || base.equals("linestring") || base.equals("polygon"))
            return Mapping.unsupported("spatial columns are not mapped yet");
        return null;
    }

    private static Mapping sqlServer(String base) {
        return switch (base) {
            case "uniqueidentifier" -> UUID;
            case "datetimeoffset" -> OFFSET_DATE_TIME;
            case "money", "smallmoney" -> BIG_DECIMAL;
            case "image" -> Mapping.of("byte[]");
            case "text", "ntext" -> Mapping.of("String");
            case "timestamp", "rowversion" -> Mapping.of("byte[]");
            case "xml" -> Mapping.unsupported("xml columns are not mapped yet");
            case "sql_variant", "hierarchyid", "geography", "geometry" -> Mapping.unsupported("type '" + base + "' is not mapped yet");
            default -> null;
        };
    }

    private static Mapping oracle(String base, Snapshot.Column c) {
        if (base.equals("binary_float")) return Mapping.of("Float");
        if (base.equals("binary_double")) return Mapping.of("Double");
        if (base.equals("float")) return Mapping.of("Double");
        if (base.equals("number")) return numeric(c);
        if (base.startsWith("timestamp") && base.endsWith("with time zone"))
            return base.contains("local") ? Mapping.of("LocalDateTime", "java.time.LocalDateTime") : OFFSET_DATE_TIME;
        if (base.equals("date")) return Mapping.of("LocalDateTime", "java.time.LocalDateTime"); // Oracle DATE has a time
        if (base.equals("raw")) return Mapping.of("byte[]");
        if (base.equals("json")) return JSON;
        if (base.equals("long") || base.equals("long raw") || base.equals("rowid") || base.equals("urowid") || base.equals("xmltype")
            || base.startsWith("interval") || base.startsWith("sdo_"))
            return Mapping.unsupported("Oracle type '" + c.vendorType + "' is not mapped yet");
        return null;
    }

    private static Mapping db2(String base) {
        return switch (base) {
            case "decfloat" -> BIG_DECIMAL;
            case "graphic", "vargraphic", "dbclob" -> Mapping.of("String");
            case "xml" -> Mapping.unsupported("xml columns are not mapped yet");
            default -> null;
        };
    }

    private static Mapping h2(String base) {
        return switch (base) {
            case "uuid" -> UUID;
            case "json" -> JSON;
            case "timestamp with time zone" -> OFFSET_DATE_TIME;
            case "enum" -> Mapping.of("String");
            default -> base.endsWith(" array") || base.equals("array") ? Mapping.unsupported("array columns are not mapped yet") : null;
        };
    }

    /**
     * SQLite stores by affinity and its driver reports a JDBC type per declared name (DECIMAL as FLOAT, every *INT*
     * as INTEGER). Hibernate validates against those reported types, so they decide; only what the driver leaves
     * open is settled here.
     */
    private static Mapping sqlite(String base, Snapshot.Column c) {
        if (base.isEmpty()) return Mapping.of("byte[]"); // no declared type: BLOB affinity
        if (base.equals("boolean") || base.equals("bool")) return Mapping.of("Boolean");
        return null;
    }

    private static Mapping coded(String javaType, String sqlType) {
        return new Mapping(javaType, null, sqlType, null);
    }

    /** JSON and large objects: stored and returned, but not offered for filtering, sorting or length checks. */
    public static boolean opaque(String jdbcTypeCode) {
        return switch (jdbcTypeCode == null ? "" : jdbcTypeCode) {
            case "SqlTypes.JSON", "SqlTypes.CLOB", "SqlTypes.NCLOB", "SqlTypes.BLOB", "SqlTypes.LONGVARCHAR",
                 "SqlTypes.LONGNVARCHAR", "SqlTypes.LONGVARBINARY" -> true;
            default -> false;
        };
    }

    private static Mapping numeric(Snapshot.Column c) {
        int precision = c.size == null ? 0 : c.size;
        int scale = c.scale == null ? 0 : c.scale;
        // unconstrained numeric reports a huge precision: keep BigDecimal
        if (scale == 0 && precision > 0 && precision <= 9) return Mapping.of("Integer");
        // 19 digits: Hibernate's own column for Long is number(19,0) / numeric(19,0)
        if (scale == 0 && precision > 0 && precision <= 19) return Mapping.of("Long");
        return Mapping.of("BigDecimal", "java.math.BigDecimal");
    }

    /**
     * By JDBC type. Character and binary columns that are not the default type of their Java type carry the
     * matching {@code SqlTypes} code, so Hibernate binds them correctly and ddl-auto=validate accepts them.
     */
    private static Mapping byJdbcType(Snapshot.Column c) {
        return switch (c.jdbcType) {
            case Types.VARCHAR -> Mapping.of("String");
            case Types.CHAR -> coded("String", "SqlTypes.CHAR");
            case Types.NCHAR -> coded("String", "SqlTypes.NCHAR");
            case Types.NVARCHAR -> coded("String", "SqlTypes.NVARCHAR");
            case Types.LONGVARCHAR -> coded("String", "SqlTypes.LONGVARCHAR");
            case Types.LONGNVARCHAR -> coded("String", "SqlTypes.LONGNVARCHAR");
            case Types.CLOB -> coded("String", "SqlTypes.CLOB");
            case Types.NCLOB -> coded("String", "SqlTypes.NCLOB");
            case Types.TINYINT, Types.SMALLINT -> Mapping.of("Short");
            case Types.INTEGER -> Mapping.of("Integer");
            case Types.BIGINT -> Mapping.of("Long");
            case Types.NUMERIC, Types.DECIMAL -> numeric(c);
            case Types.REAL -> Mapping.of("Float");
            case Types.FLOAT, Types.DOUBLE -> Mapping.of("Double");
            case Types.BOOLEAN -> Mapping.of("Boolean");
            case Types.BIT -> c.size == null || c.size <= 1 ? Mapping.of("Boolean") : Mapping.unsupported("bit strings are not mapped yet");
            case Types.DATE -> Mapping.of("LocalDate", "java.time.LocalDate");
            case Types.TIME -> Mapping.of("LocalTime", "java.time.LocalTime");
            case Types.TIME_WITH_TIMEZONE -> Mapping.of("OffsetTime", "java.time.OffsetTime");
            case Types.TIMESTAMP -> Mapping.of("LocalDateTime", "java.time.LocalDateTime");
            case Types.TIMESTAMP_WITH_TIMEZONE -> Mapping.of("OffsetDateTime", "java.time.OffsetDateTime");
            case Types.VARBINARY -> Mapping.of("byte[]");
            case Types.BINARY -> coded("byte[]", "SqlTypes.BINARY");
            case Types.LONGVARBINARY -> coded("byte[]", "SqlTypes.LONGVARBINARY");
            case Types.BLOB -> coded("byte[]", "SqlTypes.BLOB");
            default -> Mapping.unsupported("type '" + c.vendorType + "' (JDBC " + c.jdbcType + ") is not mapped yet");
        };
    }
}
