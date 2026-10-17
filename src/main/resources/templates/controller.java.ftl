package [=pkg];

<#list imports as i>
import [=i];
</#list>
import [=resource.serviceFqn];
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
<#if options.validation>
import org.springframework.validation.annotation.Validated;
import [=ns].validation.Valid;
import [=ns].validation.constraints.DecimalMax;
import [=ns].validation.constraints.DecimalMin;
import [=ns].validation.constraints.Email;
import [=ns].validation.constraints.Max;
import [=ns].validation.constraints.Min;
import [=ns].validation.constraints.Pattern;
import [=ns].validation.constraints.Size;
</#if>
<#assign methodSecurity = security.present && secCfg.accessRules != "MATCHERS">
<#if methodSecurity>
import org.springframework.security.access.prepost.PreAuthorize;
</#if>

<#macro paramAnnotation p><#compress>
<#if p.in == "path">
@PathVariable("[=p.name?j_string]")
<#elseif p.in == "query" || p.in == "form">
@RequestParam(name = "[=p.name?j_string]"<#if !p.required || p.defaultValue?has_content>, required = false</#if><#if p.defaultValue?has_content>, defaultValue = "[=p.defaultValue?j_string]"</#if>)
<#elseif p.in == "header">
@RequestHeader(name = "[=p.name?j_string]"<#if !p.required || p.defaultValue?has_content>, required = false</#if><#if p.defaultValue?has_content>, defaultValue = "[=p.defaultValue?j_string]"</#if>)
<#elseif p.in == "cookie">
@CookieValue(name = "[=p.name?j_string]"<#if !p.required || p.defaultValue?has_content>, required = false</#if><#if p.defaultValue?has_content>, defaultValue = "[=p.defaultValue?j_string]"</#if>)
<#elseif p.in == "part">
@RequestPart(name = "[=p.name?j_string]"<#if !p.required>, required = false</#if>)
<#elseif p.in == "body">
@RequestBody<#if !p.required>(required = false)</#if>
</#if>
</#compress></#macro>
/**
<#if resource.description?has_content>
 * [=doc(resource.description)]
 *
</#if>
 * REST endpoints of the "[=resource.name]" API group.
 *
 * [=generatedNote]
 */
@RestController
<#if resource.basePath?has_content>
@RequestMapping("[=resource.basePath]")
</#if>
<#if options.validation>
@Validated
</#if>
public class [=resource.controllerName] {

    private final [=resource.serviceName] [=resource.serviceVar];

    public [=resource.controllerName]([=resource.serviceName] [=resource.serviceVar]) {
        this.[=resource.serviceVar] = [=resource.serviceVar];
    }
<#list resource.operations as op>

    /**
<#if op.summary?has_content>
     * [=doc(op.summary)]
     *
</#if>
<#if op.description?has_content>
     * [=doc(op.description)]
     *
</#if>
     * <p>[=op.httpMethod] [=op.fullPath]
<#list op.responses as r>
     * <p>Response [=r.code]: [=doc(r.description)]
</#list>
<#if op.params?has_content>
     *
</#if>
<#list op.params as p>
     * @param [=p.javaName] [=p.in]<#if p.required> (required)</#if><#if p.description?has_content> - [=doc(p.description)]</#if><#if p.allowedValues?has_content> (one of: [=doc(p.allowedValues)])</#if>
</#list>
     */
<#if op.deprecated>
    @Deprecated
</#if>
<#if methodSecurity && !op.publicAccess>
    @PreAuthorize("[=op.access?j_string]")
</#if>
    @[=op.mapping](value = "[=op.path]"<#if op.requestMethod?has_content>, method = RequestMethod.[=op.requestMethod]</#if><#if op.consumes?size == 1>, consumes = "[=op.consumes[0]]"<#elseif op.consumes?size gt 1>, consumes = {<#list op.consumes as c>"[=c]"<#sep>, </#list>}</#if><#if op.produces?has_content>, produces = "[=op.produces[0]]"</#if>)
    public ResponseEntity<<#if op.returnsVoid>Void<#else>[=op.returnType.java]</#if>> [=op.method](<#list op.params as p>
            <#list p.validations as v>[=v] </#list><@paramAnnotation p=p/> [=p.type.java] [=p.javaName]<#if p?has_next>,</#if></#list>) {
<#if op.returnsVoid>
        [=resource.serviceVar].[=op.method]([=op.callArgs()]);
        return ResponseEntity.status([=op.successStatus]).build();
<#else>
        return ResponseEntity.status([=op.successStatus]).body([=resource.serviceVar].[=op.method]([=op.callArgs()]));
</#if>
    }
</#list>
}
