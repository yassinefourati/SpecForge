package [=pkg];
<#assign e = entity.db>

<#list e.dtoImports as i>
import [=i];
</#list>
import java.util.Map;
import java.util.Optional;
import [=pkgOf(e.className, "domain")].[=e.className];
import [=sharedModelPackage].PageQuery;
import [=sharedModelPackage].PageResult;

/**
 * What the application needs from storage for [=e.className]: the outbound port. The persistence adapter implements
 * it with JPA; a test can implement it in memory.
 *
 * [=generatedNote]
 */
public interface [=e.className]RepositoryPort {

    PageResult<[=e.className]> findAll(Map<String, String> filters, PageQuery page);

    Optional<[=e.className]> findById([=e.id.javaType] id);

    boolean existsById([=e.id.javaType] id);

    /** Stores a new row and returns it as the database completed it (generated key, defaults). */
    [=e.className] create([=e.className] item);

    [=e.className] update([=e.className] item);

    void deleteById([=e.id.javaType] id);
}
