package [=pkg];
<#assign e = entity.db>

<#list e.dtoImports as i>
import [=i];
</#list>
import java.util.Map;
import [=sharedModelPackage].PageQuery;
import [=sharedModelPackage].PageResult;

/**
 * What the application offers for [=e.className] (table [=e.qualifiedName]): the inbound port. Adapters (the web
 * controller, a message listener, …) call it; [=e.requestName] and [=e.dtoName] are its input and output.
 *
 * [=generatedNote]
 */
public interface [=e.className]UseCases {

    /** One page, filtered by the given properties ([=e.filterable?join(", ")]<#if e.searchable?has_content>, q</#if><#if e.rangeable?has_content>, ranges</#if>). */
    PageResult<[=e.dtoName]> list(Map<String, String> filters, PageQuery page);

    [=e.dtoName] read([=e.id.javaType] id);

    [=e.dtoName] create([=e.requestName] request);

    [=e.dtoName] update([=e.id.javaType] id, [=e.requestName] request);

    /** JSON Merge Patch: present properties are applied, null clears, absent ones are kept. */
    [=e.dtoName] patch([=e.id.javaType] id, Map<String, Object> changes);

    void delete([=e.id.javaType] id);
}
