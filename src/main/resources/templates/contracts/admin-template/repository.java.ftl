package [=pkg];
<#assign x = module.of(entity.db)>

<#list x.imports as i>
import [=i];
</#list>
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/**
 * [=x.label]; lists are filtered with {@link [=x.className]Service#matching}.
 *
 * [=generatedNote]
 */
@Repository("[=module.name][=x.className]Repository")
public interface [=x.className]Repository extends JpaRepository<[=x.className], [=x.idJavaType]>, JpaSpecificationExecutor<[=x.className]> {
}
