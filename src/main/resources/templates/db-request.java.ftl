package [=pkg];
<#assign e = entity.db>
<#assign comps = e.requestComponents()>
<#assign annos = {}>
<#if options.validation>
<#list comps as f><#list f.validations as v><#assign annos = annos + {v?keep_before("(")?remove_beginning("@"): true}></#list></#list>
</#if>

<#list e.dtoImports as i>
import [=i];
</#list>
<#list annos?keys?sort as a>
import [=ns].validation.constraints.[=a];
</#list>

/**
 * What a client sends to create or update [=e.className] (table [=e.qualifiedName]). Generated and read-only
 * columns are not part of it; [=e.dtoName] is what the API returns.
<#if e.relations?has_content || e.manyToMany?has_content>
 * Related rows are referenced by id.
</#if>
 *
 * [=generatedNote]
 */
public record [=e.requestName](
<#list comps as f>
        <#if options.validation><#list f.validations as v>[=v] </#list></#if>[=f.javaType] [=f.name]<#sep>,</#sep>
</#list>
) {
}
