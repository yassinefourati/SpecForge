package [=pkg];
<#assign e = entity.db>
<#assign idPath = "/{id}">
<#assign idParams = "@PathVariable(\"id\") " + e.id.javaType + " id">

<#list e.dtoImports as i>
import [=i];
</#list>
<#if e.op("create")>
import java.net.URI;
</#if>
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
<#if options.validation && (e.op("create") || e.op("update"))>
import [=ns].validation.Valid;
</#if>
import [=modelPackage].[=e.className]UseCases;
import [=modelPackage].[=e.dtoName];
import [=modelPackage].[=e.requestName];
import [=sharedModelPackage].PageQuery;
import [=sharedModelPackage].PageResult;
import [=sharedModelPackage].PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
 * The web adapter of [=e.className] (table [=e.qualifiedName]): HTTP in, the inbound port out.
 *
 * [=generatedNote]
 */
@RestController
@RequestMapping("/[=e.path]")
public class [=e.controllerName] {

    private final [=e.className]UseCases useCases;

    public [=e.controllerName]([=e.className]UseCases useCases) {
        this.useCases = useCases;
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
        List<PageQuery.Order> orders = new ArrayList<>();
        for (Sort.Order o : pageable.getSort()) {
            orders.add(new PageQuery.Order(o.getProperty(), o.isAscending()));
        }
        PageResult<[=e.dtoName]> page = useCases.list(filters, new PageQuery(pageable.getPageNumber(), pageable.getPageSize(), orders));
        return ResponseEntity.ok(new PageResponse<>(page.content(), page.page(), page.size(), page.totalElements(), page.totalPages()));
    }
</#if>
<#if e.op("read")>

    @GetMapping("[=idPath]")
    public ResponseEntity<[=e.dtoName]> read([=idParams]) {
        return ResponseEntity.ok(useCases.read(id));
    }
</#if>
<#if e.op("create")>

    @PostMapping
    public ResponseEntity<[=e.dtoName]> create(<#if options.validation>@Valid </#if>@RequestBody [=e.requestName] request) {
        [=e.dtoName] created = useCases.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("[=idPath]").buildAndExpand(created.[=e.id.field.name]()).toUri();
        return ResponseEntity.created(location).body(created);
    }
</#if>
<#if e.op("update")>

    @PutMapping("[=idPath]")
    public ResponseEntity<[=e.dtoName]> update([=idParams], <#if options.validation>@Valid </#if>@RequestBody [=e.requestName] request) {
        return ResponseEntity.ok(useCases.update(id, request));
    }
</#if>
<#if e.op("patch")>

    /** JSON Merge Patch (RFC 7396): present properties are applied, null clears, absent ones are kept. */
    @PatchMapping(value = "[=idPath]", consumes = {"application/merge-patch+json", "application/json"})
    public ResponseEntity<[=e.dtoName]> patch([=idParams], @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(useCases.patch(id, body));
    }
</#if>
<#if e.op("delete")>

    @DeleteMapping("[=idPath]")
    public ResponseEntity<Void> delete([=idParams]) {
        useCases.delete(id);
        return ResponseEntity.noContent().build();
    }
</#if>
}
