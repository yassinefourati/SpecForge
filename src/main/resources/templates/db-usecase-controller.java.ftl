package [=pkg];
<#assign u = useCase>
<#-- entities whose own operations this controller serves (OPS-3, OPS-4), each called through its service -->
<#assign direct = {}>
<#list u.operations as op><#if op.kind == "ENTITY"><#assign direct = direct + {op.entity.className: op.entity}></#if></#list>
<#assign spanning = u.has("WITH_CHILDREN") || u.has("CHILDREN") || u.has("AGGREGATE_CREATE") || u.has("AGGREGATE_UPDATE")>
<#macro idParams e><#if e.id.composite()><#list e.id.keyFields as f>@PathVariable("[=f.name]") [=f.javaType] [=f.name]<#sep>, </#sep></#list><#else>@PathVariable("id") [=e.id.javaType] id</#if></#macro>
<#macro idExpr e><#if e.id.composite()>new [=e.className].Key(<#list e.id.keyFields as f>[=f.name]<#sep>, </#sep></#list>)<#else>id</#if></#macro>
<#macro location e><#if e.id.composite()>"<#list e.id.keyFields as f>/{[=f.name]}</#list>").buildAndExpand(<#list e.id.keyFields as f>created.[=f.name]()<#sep>, </#sep></#list>)<#else>"/{id}").buildAndExpand(created.[=e.id.field.name]())</#if></#macro>

<#list u.imports as i>
import [=i];
</#list>
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
<#if options.validation>
import [=ns].validation.Valid;
</#if>
<#list direct?values as e>
import [=pkgOf(e.className, "entities")].[=e.className];
import [=pkgOf(e.className, "models")].[=e.dtoName];
import [=pkgOf(e.className, "models")].[=e.requestName];
import [=pkgOf(e.className, "services")].[=e.serviceName];
</#list>
<#list u.operations as op><#if op.kind != "ENTITY">
import [=pkgOf(op.child.className, "models")].[=op.child.dtoName];
<#if op.responseRecord?has_content>
import [=modelPackage].[=op.responseRecord];
</#if>
<#if op.requestRecord?has_content>
import [=modelPackage].[=op.requestRecord];
</#if>
</#if></#list>
import [=sharedModelPackage].PageResponse;
<#if spanning>
import [=servicePackage].[=u.serviceName];
</#if>
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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Use case "[=u.name]": endpoints composed from <#list direct?keys + u.operations?filter(o -> o.kind != "ENTITY")?map(o -> o.entity.className + "/" + o.child.className) as n>[=n]<#sep>, </#sep></#list>.
 *
 * [=generatedNote]
 */
@RestController
@RequestMapping("/[=u.path]")
public class [=u.controllerName] {
<#if spanning>

    private final [=u.serviceName] service;
</#if>
<#list direct?values as e>
    private final [=e.serviceName] [=e.varName]Service;
</#list>

    public [=u.controllerName](<#if spanning>[=u.serviceName] service<#if direct?has_content>, </#if></#if><#list direct?values as e>[=e.serviceName] [=e.varName]Service<#sep>, </#sep></#list>) {
<#if spanning>
        this.service = service;
</#if>
<#list direct?values as e>
        this.[=e.varName]Service = [=e.varName]Service;
</#list>
    }
<#list u.operations as op>
<#assign e = op.entity>
<#if op.kind == "ENTITY">
<#assign svc = e.varName + "Service">
<#if op.operation == "list">

    /** [=e.className] rows, one page. Filters: <#if e.filterable?has_content>{@code [=e.filterable?join("}, {@code ")]}<#else>none</#if> (equality). */
    @GetMapping("[=op.path]")
    public ResponseEntity<PageResponse<[=e.dtoName]>> [=op.method](@RequestParam Map<String, String> params,
            @PageableDefault(size = 20) Pageable pageable) {
        Map<String, String> filters = new LinkedHashMap<>(params);
        filters.keySet().removeAll(Set.of("page", "size", "sort"));
        return ResponseEntity.ok([=svc].list(filters, pageable));
    }
<#elseif op.operation == "read">

    @GetMapping("[=op.path]")
    public ResponseEntity<[=e.dtoName]> [=op.method](<@idParams e/>) {
        return ResponseEntity.ok([=svc].read(<@idExpr e/>));
    }
<#elseif op.operation == "create">

    @PostMapping("[=op.path]")
    public ResponseEntity<[=e.dtoName]> [=op.method](<#if options.validation>@Valid </#if>@RequestBody [=e.requestName] dto) {
        [=e.dtoName] created = [=svc].create(dto);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path(<@location e/>.toUri();
        return ResponseEntity.created(location).body(created);
    }
<#elseif op.operation == "update">

    @PutMapping("[=op.path]")
    public ResponseEntity<[=e.dtoName]> [=op.method](<@idParams e/>, <#if options.validation>@Valid </#if>@RequestBody [=e.requestName] dto) {
        return ResponseEntity.ok([=svc].update(<@idExpr e/>, dto));
    }
<#elseif op.operation == "patch">

    /** JSON Merge Patch (RFC 7396). */
    @PatchMapping(value = "[=op.path]", consumes = {"application/merge-patch+json", "application/json"})
    public ResponseEntity<[=e.dtoName]> [=op.method](<@idParams e/>, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok([=svc].patch(<@idExpr e/>, body));
    }
<#elseif op.operation == "delete">

    @DeleteMapping("[=op.path]")
    public ResponseEntity<Void> [=op.method](<@idParams e/>) {
        [=svc].delete(<@idExpr e/>);
        return ResponseEntity.noContent().build();
    }
</#if>
<#elseif op.kind == "WITH_CHILDREN">

    /** One [=e.className] with its [=op.childrenProperty]. */
    @GetMapping("[=op.path]")
    public ResponseEntity<[=op.responseRecord]> [=op.method](@PathVariable("id") [=e.id.javaType] id) {
        return ResponseEntity.ok(service.[=op.method](id));
    }
<#elseif op.kind == "CHILDREN">

    /** The [=op.childrenProperty] of one [=e.className], one page. */
    @GetMapping("[=op.path]")
    public ResponseEntity<PageResponse<[=op.child.dtoName]>> [=op.method](@PathVariable("id") [=e.id.javaType] id,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(service.[=op.method](id, pageable));
    }
<#elseif op.kind == "AGGREGATE_CREATE">

    /** Creates one [=e.className] and its [=op.childrenProperty] in one transaction: all of them, or none. */
    @PostMapping("[=op.path]")
    public ResponseEntity<[=op.responseRecord]> [=op.method](<#if options.validation>@Valid </#if>@RequestBody [=op.requestRecord] request) {
        [=op.responseRecord] created = service.[=op.method](request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(created.[=op.parentProperty]().[=e.id.field.name]()).toUri();
        return ResponseEntity.created(location).body(created);
    }
<#elseif op.kind == "AGGREGATE_UPDATE">

    /** Updates one [=e.className] and replaces its [=op.childrenProperty], in one transaction. */
    @PutMapping("[=op.path]")
    public ResponseEntity<[=op.responseRecord]> [=op.method](@PathVariable("id") [=e.id.javaType] id,
            <#if options.validation>@Valid </#if>@RequestBody [=op.requestRecord] request) {
        return ResponseEntity.ok(service.[=op.method](id, request));
    }
</#if>
</#list>
}
