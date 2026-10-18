package [=pkg];
<#assign e = entity.db>
<#if e.id.composite()>
<#assign idPath><#list e.id.keyFields as f>/{[=f.name]}</#list></#assign>
<#assign idParams><#list e.id.keyFields as f>@PathVariable("[=f.name]") [=f.javaType] [=f.name]<#sep>, </#sep></#list></#assign>
<#assign idExpr>new [=e.id.javaType](<#list e.id.keyFields as f>[=f.name]<#sep>, </#sep></#list>)</#assign>
<#assign locationArgs><#list e.id.keyFields as f>created.[=f.name]()<#sep>, </#sep></#list></#assign>
<#else>
<#assign idPath = "/{id}">
<#assign idParams = "@PathVariable(\"id\") " + e.id.javaType + " id">
<#assign idExpr = "id">
<#assign locationArgs = "created." + e.id.field.name + "()">
</#if>

<#list e.dtoImports as i>
import [=i];
</#list>
<#if e.op("create")>
import java.net.URI;
</#if>
import java.util.Map;
<#if options.validation && (e.op("create") || e.op("update"))>
import [=ns].validation.Valid;
</#if>
<#if e.id.composite()>
import [=entityPackage].[=e.className];
</#if>
import [=modelPackage].[=e.dtoName];
import [=modelPackage].[=e.requestName];
import [=sharedModelPackage].PageResponse;
import [=servicePackage].[=e.serviceName];
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
<#if e.op("create")>
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
</#if>

/**
 * REST endpoints for [=e.className] (table [=e.qualifiedName]).
 *
 * [=generatedNote]
 */
@RestController
@RequestMapping("/[=e.path]")
public class [=e.controllerName] {

    private final [=e.serviceName] service;

    public [=e.controllerName]([=e.serviceName] service) {
        this.service = service;
    }
<#if e.op("list")>

    /**
     * One page of rows. Paging: {@code page}, {@code size}, {@code sort=property,asc|desc}.
     * Filters: <#if e.filterable?has_content>{@code [=e.filterable?join("}, {@code ")]}<#else>none</#if> (equality).
<#if e.searchable?has_content>
     * Text search: {@code q} in [=e.searchable?join(", ")] (case-insensitive, contains).
</#if>
<#if e.rangeable?has_content>
     * Ranges (inclusive): <#list e.rangeable as p>{@code [=p]From} / {@code [=p]To}<#sep>, </#sep></#list>.
</#if>
     */
    @GetMapping
    public ResponseEntity<PageResponse<[=e.dtoName]>> list(@RequestParam Map<String, String> params,
            @PageableDefault(size = 20) Pageable pageable) {
        Map<String, String> filters = new java.util.LinkedHashMap<>(params);
        filters.keySet().removeAll(java.util.Set.of("page", "size", "sort"));
        return ResponseEntity.ok(service.list(filters, pageable));
    }
</#if>
<#if e.op("read")>

    @GetMapping("[=idPath]")
    public ResponseEntity<[=e.dtoName]> read([=idParams]) {
        return ResponseEntity.ok(service.read([=idExpr]));
    }
</#if>
<#if e.op("create")>

    @PostMapping
    public ResponseEntity<[=e.dtoName]> create(<#if options.validation>@Valid </#if>@RequestBody [=e.requestName] dto) {
        [=e.dtoName] created = service.create(dto);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("[=idPath]").buildAndExpand([=locationArgs]).toUri();
        return ResponseEntity.created(location).body(created);
    }
</#if>
<#if e.op("update")>

    @PutMapping("[=idPath]")
    public ResponseEntity<[=e.dtoName]> update([=idParams], <#if options.validation>@Valid </#if>@RequestBody [=e.requestName] dto) {
        return ResponseEntity.ok(service.update([=idExpr], dto));
    }
</#if>
<#if e.op("patch")>

    /** JSON Merge Patch (RFC 7396): present properties are applied, null clears, absent ones are kept. */
    @PatchMapping(value = "[=idPath]", consumes = {"application/merge-patch+json", "application/json"})
    public ResponseEntity<[=e.dtoName]> patch([=idParams], @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(service.patch([=idExpr], body));
    }
</#if>
<#if e.op("delete")>

    @DeleteMapping("[=idPath]")
    public ResponseEntity<Void> delete([=idParams]) {
        service.delete([=idExpr]);
        return ResponseEntity.noContent().build();
    }
</#if>
}
