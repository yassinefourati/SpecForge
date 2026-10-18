package [=pkg];
<#assign e = entity.db>
<#assign comps = []>
<#list e.dtoFields() as f><#assign comps = comps + [f]></#list>
<#list e.relations as r><#if r.dtoField??><#assign comps = comps + [r.dtoField]></#if></#list>
<#-- split (STYLE-9): validation belongs to the request record; the response only describes data -->
<#assign validate = options.validation && !e.split>
<#assign annos = {}>
<#if validate>
<#list comps as f><#list f.validations as v><#assign annos = annos + {v?keep_before("(")?remove_beginning("@"): true}></#list></#list>
</#if>

<#list e.dtoImports as i>
import [=i];
</#list>
<#list annos?keys?sort as a>
import [=ns].validation.constraints.[=a];
</#list>

/**
<#if e.split>
 * What the API returns for [=e.className] (table [=e.qualifiedName]); clients send [=e.requestName].
<#else>
 * API representation of [=e.className] (table [=e.qualifiedName]).
</#if>
<#if e.relations?has_content || e.manyToMany?has_content>
 * Related rows are referenced by id.
</#if>
 *
 * [=generatedNote]
 */
public record [=e.dtoName](
<#list comps as f>
        <#if validate><#list f.validations as v>[=v] </#list></#if>[=f.javaType] [=f.name]<#if f?has_next || e.manyToMany?has_content>,</#if>
</#list>
<#list e.manyToMany as m>
        Set<[=m.targetIdType]> [=m.dtoName]<#sep>,</#sep>
</#list>
) {
}
