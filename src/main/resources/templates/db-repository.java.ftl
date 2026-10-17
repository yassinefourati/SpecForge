package [=pkg];
<#assign e = entity.db>

<#list e.dtoImports as i><#if e.id.javaType?ends_with(i?keep_after_last("."))>
import [=i];
</#if></#list>
<#list e.childQueries as q>
<#if q?is_first>
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
</#if>
</#list>
<#list e.dtoImports as i>
import [=i];
</#list>
import [=entityPackage].[=e.className];
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/**
 * Data access for [=e.className] (table [=e.qualifiedName]).
 *
 * [=generatedNote]
 */
@Repository
public interface [=e.repositoryName] extends JpaRepository<[=e.className], [=e.id.javaType]>, JpaSpecificationExecutor<[=e.className]> {
<#list e.childQueries as q>

    /** Rows whose [=q.name] is the given [=q.targetClass] (use cases). */
    List<[=e.className]> findAllBy[=q.cap]_[=q.targetIdCap]([=q.targetIdType] [=q.targetIdName]);

    Page<[=e.className]> findAllBy[=q.cap]_[=q.targetIdCap]([=q.targetIdType] [=q.targetIdName], Pageable pageable);
</#list>
}
