package io.specforge.suggest;

import io.specforge.snapshot.Snapshot;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Types;

import static org.assertj.core.api.Assertions.assertThat;

/** MAP-6 and MAP-10 rules without a database. */
class SuggestionRulesTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        "users, User",
        "roles, Role",
        "user_roles, UserRole",
        "categories, Category",
        "addresses, Address",
        "statuses, Status",
        "people, Person",
        "notifications, Notification",
        "order_items, OrderItem",
        "data, Data",
        "COUNTRIES, Country",
        "ORDER_ITEMS, OrderItem",
        "PEOPLE, Person",
    })
    void classNamesAreSingular(String table, String className) {
        assertThat(SuggestionEngine.suggestedClass(table)).isEqualTo(className);
    }

    @ParameterizedTest(name = "{0} → /{1}")
    @CsvSource({
        "User, users",
        "UserRole, user-roles",
        "Person, people",
        "Status, statuses",
        "Category, categories",
    })
    void pathsArePluralizedOnce(String className, String path) {
        assertThat(SuggestionEngine.pathOf(className)).isEqualTo(path);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "roles, roles",
        "permission, permissions",
        "notifications, notifications",
    })
    void pluralOfAPluralIsUnchanged(String word, String plural) {
        assertThat(Inflection.plural(word)).isEqualTo(plural);
    }

    @ParameterizedTest(name = "{0}({2},{3}) → {4}")
    @CsvSource({
        "jsonb,       1111, ,   ,  String",
        "uuid,        1111, ,   ,  UUID",
        "timestamptz, 93,   ,   ,  OffsetDateTime",
        "timestamp,   93,   ,   ,  LocalDateTime",
        "numeric,     2,    1,  0, Integer",
        "numeric,     2,    12, 0, Long",
        "numeric,     2,    12, 2, BigDecimal",
        "bigserial,   -5,   ,   ,  Long",
        "bool,        -7,   ,   ,  Boolean",
    })
    void postgresTypesByVendorNameFirst(String vendor, int jdbc, Integer size, Integer scale, String java) {
        Snapshot.Column c = new Snapshot.Column();
        c.vendorType = vendor;
        c.jdbcType = jdbc;
        c.size = size;
        c.scale = scale;
        TypeRules.Mapping m = TypeRules.map("POSTGRESQL", c);
        assertThat(m.supported()).isTrue();
        assertThat(m.javaType()).isEqualTo(java);
    }

    @ParameterizedTest(name = "{0} is left out")
    @CsvSource({"inet", "_int4", "money", "my_enum"})
    void unknownPostgresTypesAreNeverGuessed(String vendor) {
        Snapshot.Column c = new Snapshot.Column();
        c.vendorType = vendor;
        c.jdbcType = Types.OTHER;
        assertThat(TypeRules.map("POSTGRESQL", c).supported()).isFalse();
    }

    @ParameterizedTest(name = "{1} on {0} → {2}")
    @CsvSource(delimiter = '|', value = {
        "POSTGRESQL | nextval('products_seq'::regclass)             | products_seq",
        "POSTGRESQL | nextval('shop.\"Order_Seq\"'::regclass)       | shop.Order_Seq",
        "SQLSERVER  | (NEXT VALUE FOR [dbo].[products_seq])          | dbo.products_seq",
        "H2         | NEXT VALUE FOR \"PUBLIC\".\"PRODUCTS_SEQ\"      | PUBLIC.PRODUCTS_SEQ",
        "ORACLE     | \"SHOP\".\"PRODUCTS_SEQ\".\"NEXTVAL\"           | SHOP.PRODUCTS_SEQ",
        "ORACLE     | products_seq.nextval                            | products_seq",
        "MARIADB    | nextval(`shop`.`products_seq`)                  | shop.products_seq",
    })
    void sequenceDefaultsOfEveryEngine(String engine, String def, String expected) {
        String[] ref = SuggestionEngine.sequenceReference(def);
        assertThat(ref).as(def).isNotNull();
        assertThat((ref[0] == null ? "" : ref[0] + ".") + ref[1]).isEqualTo(expected);
    }

    @ParameterizedTest(name = "no sequence in {0}")
    @CsvSource(delimiter = '|', value = {"CURRENT_TIMESTAMP", "'NEW'", "(getdate())", "gen_random_uuid()"})
    void otherDefaultsAreNotSequences(String def) {
        assertThat(SuggestionEngine.sequenceReference(def)).isNull();
    }

    @ParameterizedTest(name = "{0} {1}({3},{4}) → {5}")
    @CsvSource({
        "ORACLE,    NUMBER,                       2,    19, 0,    Long",
        "ORACLE,    NUMBER,                       2,    9,  0,    Integer",
        "ORACLE,    NUMBER,                       2,    0,  -127, BigDecimal",
        "ORACLE,    DATE,                         93,   ,   ,     LocalDateTime",
        "ORACLE,    TIMESTAMP(6) WITH TIME ZONE,  -101, ,   ,     OffsetDateTime",
        "ORACLE,    BINARY_DOUBLE,                101,  ,   ,     Double",
        "SQLSERVER, uniqueidentifier,             1,    36, ,     UUID",
        "SQLSERVER, datetimeoffset,               -155, ,   ,     OffsetDateTime",
        "MYSQL,     INT UNSIGNED,                 4,    10, 0,    Long",
        "MYSQL,     BIGINT UNSIGNED,              -5,   20, 0,    BigInteger",
        "MYSQL,     JSON,                         -1,   ,   ,     String",
        "MARIADB,   TINYINT,                      -7,   1,  ,     Boolean",
        "H2,        UUID,                         1111, ,   ,     UUID",
        "SQLITE,    DECIMAL,                      6,    ,   ,     Double",
        "SQLITE,    INTEGER,                      4,    ,   ,     Integer",
        "DB2,       DECFLOAT,                     1111, ,   ,     BigDecimal",
    })
    void typesOfEveryEngine(String engine, String vendor, int jdbc, Integer size, Integer scale, String java) {
        Snapshot.Column c = new Snapshot.Column();
        c.vendorType = vendor;
        c.jdbcType = jdbc;
        c.size = size;
        c.scale = scale;
        TypeRules.Mapping m = TypeRules.map(engine, c);
        assertThat(m.supported()).as(m.unsupportedReason()).isTrue();
        assertThat(m.javaType()).isEqualTo(java);
    }

    @ParameterizedTest(name = "{0} carries {1}")
    @CsvSource({"1, SqlTypes.CHAR", "-15, SqlTypes.NCHAR", "-9, SqlTypes.NVARCHAR", "2005, SqlTypes.CLOB", "2004, SqlTypes.BLOB"})
    void columnsThatAreNotTheDefaultTypeCarryTheirSqlType(int jdbc, String code) {
        Snapshot.Column c = new Snapshot.Column();
        c.vendorType = "x";
        c.jdbcType = jdbc;
        assertThat(TypeRules.map("H2", c).jdbcTypeCode()).isEqualTo(code);
    }
}
