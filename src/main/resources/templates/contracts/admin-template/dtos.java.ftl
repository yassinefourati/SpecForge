package [=pkg];
<#assign x = module.of(entity.db)>
<#assign annos = {}>
<#if options.validation>
<#list x.request as c><#list c.validations as v><#assign annos = annos + {v?keep_before("(")?remove_beginning("@"): true}></#list></#list>
</#if>

<#list x.imports as i>
import [=i];
</#list>
<#list annos?keys?sort as a>
import jakarta.validation.constraints.[=a];
</#list>

/**
 * What the API accepts and returns for [=x.label?lower_case].
 *
 * [=generatedNote]
 */
public final class [=x.className]Dtos {

    private [=x.className]Dtos() {
    }

    /** Sent to create or change a row<#if x.idStrategy != "ASSIGNED">; the key is the server's</#if>. */
    public record [=x.className]Request(
<#list x.request as c>
            <#if options.validation><#list c.validations as v>[=v] </#list></#if>[=c.javaType] [=c.name]<#sep>,</#sep>
</#list>
    ) {
    }

    /** {@code deletable}: the viewer may delete the row. */
    public record [=x.className]Response(
<#list x.response as c>
            [=c.javaType] [=c.name]<#sep>,</#sep>
</#list>
    ) {
    }
}
