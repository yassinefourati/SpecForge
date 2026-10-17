package io.specforge.source;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** CONN-2: URLs per engine, SSL in each engine's own syntax, and read-only switches kept out of generated URLs. */
class ConnectionSettingsTest {

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource(delimiter = '|', value = {
        "POSTGRESQL |             | jdbc:postgresql://db:5432/shop",
        "POSTGRESQL | require     | jdbc:postgresql://db:5432/shop?sslmode=require",
        "MYSQL      | verify-full | jdbc:mysql://db:3306/shop?sslMode=VERIFY_IDENTITY",
        "MARIADB    | require     | jdbc:mariadb://db:3306/shop?sslMode=trust",
        "SQLSERVER  |             | jdbc:sqlserver://db:1433;databaseName=shop;encrypt=true",
        "SQLSERVER  | require     | jdbc:sqlserver://db:1433;databaseName=shop;encrypt=true;trustServerCertificate=true",
        "SQLSERVER  | disable     | jdbc:sqlserver://db:1433;databaseName=shop;encrypt=false",
        "ORACLE     |             | jdbc:oracle:thin:@//db:1521/shop",
        "DB2        | require     | jdbc:db2://db:50000/shop:sslConnection=true;",
    })
    void serverUrls(String kind, String ssl, String url) {
        ConnectionSettings cs = new ConnectionSettings();
        cs.kind = kind;
        cs.host = "db";
        cs.database = "shop";
        cs.sslMode = ssl == null ? "" : ssl;
        assertThat(cs.jdbcUrl()).isEqualTo(url);
        assertThat(cs.connectUrl()).isEqualTo(url);
    }

    @Test
    void h2IsOpenedReadOnlyButGeneratedWithoutTheSwitches() {
        ConnectionSettings cs = new ConnectionSettings();
        cs.kind = "H2";
        cs.path = "C:\\data\\shop.mv.db";
        assertThat(cs.jdbcUrl()).isEqualTo("jdbc:h2:file:C:/data/shop");
        assertThat(cs.connectUrl()).isEqualTo("jdbc:h2:file:C:/data/shop;IFEXISTS=TRUE;ACCESS_MODE_DATA=r");
    }

    @Test
    void sqliteUsesThePath() {
        ConnectionSettings cs = new ConnectionSettings();
        cs.kind = "SQLITE";
        cs.path = "data/shop.sqlite";
        assertThat(cs.jdbcUrl()).isEqualTo("jdbc:sqlite:data/shop.sqlite");
    }
}
