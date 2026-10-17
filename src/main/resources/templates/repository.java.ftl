package [=pkg];

<#list imports as i>
import [=i];
</#list>
import [=entity.fqn];
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link [=entity.className]}.
 *
 * [=generatedNote]
 */
@Repository
public interface [=entity.repositoryName] extends JpaRepository<[=entity.className], [=entity.idField.type.java]> {
<#list entity.finders as f>

    Optional<[=entity.className]> [=f.method]([=f.javaType] [=f.field]);
</#list>
}
