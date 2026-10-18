package [=pkg];
<#assign e = entity.db>

<#list e.dtoImports as i>
import [=i];
</#list>
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Spring Data access to [=e.qualifiedName], used by [=e.className]PersistenceAdapter only.
 *
 * [=generatedNote]
 */
public interface [=e.className]SpringDataRepository extends JpaRepository<[=e.className]JpaEntity, [=e.id.javaType]>,
        JpaSpecificationExecutor<[=e.className]JpaEntity> {
}
