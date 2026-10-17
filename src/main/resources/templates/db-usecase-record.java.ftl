package [=pkg];
<#assign r = record>
<#assign annos = {}>
<#if options.validation>
<#list r.components as f><#list f.validations as v><#assign annos = annos + {v?keep_before("(")?remove_beginning("@"): true}></#list></#list>
</#if>

<#list r.imports as i>
import [=i];
</#list>
<#list annos?keys?sort as a>
<#if a == "Valid">
import [=ns].validation.Valid;
<#else>
import [=ns].validation.constraints.[=a];
</#if>
</#list>

/**
 * [=doc(r.doc)]
 *
 * [=generatedNote]
 */
public record [=r.name](
<#list r.components as f>
        <#if options.validation><#list f.validations as v>[=v] </#list></#if>[=f.javaType] [=f.name]<#sep>,</#sep>
</#list>
) {
}
