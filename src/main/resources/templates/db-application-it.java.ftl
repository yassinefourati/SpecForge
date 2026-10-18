package [=pkg];

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.JpaRepository;
<#if !checks.inMemory>
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import [=checks.containerImport];
</#if>

/**
 * The application against a database created from db/migration/V1__baseline.sql<#if checks.inMemory> (H2 in memory)<#else> (a [=checks.containerImage]
 * container; skipped without Docker)</#if>: Flyway creates the schema, Hibernate validates every mapping against it, and every
 * table answers a query.
 *
 * [=generatedNote]
 */
@SpringBootTest(properties = {"DB_USERNAME=test", "DB_PASSWORD=test"<#if checks.inMemory>, "spring.datasource.url=jdbc:h2:mem:application-it;DB_CLOSE_DELAY=-1"</#if>})
<#if !checks.inMemory>
@Testcontainers(disabledWithoutDocker = true)
</#if>
class DatabaseSchemaTests {
<#if !checks.inMemory>

    @Container
    @ServiceConnection
    static [=checks.containerClass]<#if checks.genericContainer()><?></#if> database = new [=checks.containerClass]<#if checks.genericContainer()><></#if>("[=checks.containerImage]");
</#if>

    @Autowired
    List<JpaRepository<?, ?>> repositories;

    @Test
    void everyTableIsReadable() {
        assertThat(repositories).isNotEmpty();
        for (JpaRepository<?, ?> repository : repositories) {
            assertThat(repository.count()).isZero();
        }
    }
}
