package [=pkg];
<#assign x = module.of(entity.db)>
<#assign X = x.className>
<#assign guard = module.permission?has_content>

<#list x.imports as i>
import [=i];
</#list>
import [=pkg].[=X]Dtos.[=X]Request;
import [=pkg].[=X]Dtos.[=X]Response;
import com.admintemplate.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
<#if x.create || x.update>
import jakarta.validation.Valid;
</#if>
<#if x.params?filter(p -> p.iso?has_content)?has_content>
import org.springframework.format.annotation.DateTimeFormat;
</#if>
import org.springframework.http.HttpStatus;
<#if guard>
import org.springframework.security.access.prepost.PreAuthorize;
</#if>
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * [=x.label]: every signed-in user reads<#if guard>; changes need the permission "[=module.permission]"</#if>.
 *
 * [=generatedNote]
 */
// bean names carry the module: the project may have a [=X]Controller of its own
@RestController("[=module.name][=X]Controller")
@RequestMapping("/api/[=x.path]")
@Tag(name = "[=x.label?j_string]")
public class [=X]Controller {

    private final [=X]Service service;

    public [=X]Controller([=X]Service service) {
        this.service = service;
    }
<#if x.list>

    @GetMapping
    @Operation(summary = "One page of [=x.label?lower_case?j_string]<#if x.searchPaths?has_content>; q searches [=x.searchPaths?join(", ")]</#if>")
    public PageResponse<[=X]Response> list(<#if x.searchPaths?has_content>@RequestParam(required = false) String q,
            </#if><#list x.params as p>@RequestParam(required = false) <#if p.iso?has_content>@DateTimeFormat(iso = DateTimeFormat.ISO.[=p.iso]) </#if>[=p.javaType] [=p.name],
            </#list>@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return service.list(<#if x.searchPaths?has_content>q, </#if><#list x.params as p>[=p.name], </#list>page, size, sort);
    }
</#if>
<#if x.read>

    @GetMapping("/{id}")
    public [=X]Response get(@PathVariable [=x.idJavaType] id) {
        return service.get(id);
    }
</#if>
<#if x.create>

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
<#if guard>
    @PreAuthorize("hasAuthority('[=module.permission?j_string]')")
</#if>
    public [=X]Response create(@Valid @RequestBody [=X]Request request) {
        return service.create(request);
    }
</#if>
<#if x.update>

    @PutMapping("/{id}")
<#if guard>
    @PreAuthorize("hasAuthority('[=module.permission?j_string]')")
</#if>
    public [=X]Response update(@PathVariable [=x.idJavaType] id, @Valid @RequestBody [=X]Request request) {
        return service.update(id, request);
    }
</#if>
<#if x.delete>

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
<#if guard>
    @PreAuthorize("hasAuthority('[=module.permission?j_string]')")
</#if>
    public void delete(@PathVariable [=x.idJavaType] id) {
        service.delete(id);
    }
</#if>
}
