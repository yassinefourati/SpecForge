package [=pkg];
<#assign u = useCase>

<#list u.imports as i>
import [=i];
</#list>
import java.util.ArrayList;
import java.util.List;
<#list u.services as e>
import [=pkgOf(e.className, "models")].[=e.dtoName];
import [=pkgOf(e.className, "models")].[=e.requestName];
import [=pkgOf(e.className, "services")].[=e.serviceName];
</#list>
<#list u.mappers as e>
import [=pkgOf(e.className, "models")].[=e.dtoName];
import [=pkgOf(e.className, "services")].[=e.mapperName];
</#list>
<#list u.repositories as e>
import [=pkgOf(e.className, "repositories")].[=e.repositoryName];
</#list>
<#list u.records as r>
import [=modelPackage].[=r.name];
</#list>
<#list u.operations as op><#if op.kind != "ENTITY">
import [=pkgOf(op.child.className, "services")].[=op.child.serviceName];
import [=pkgOf(op.child.className, "models")].[=op.child.requestName];
import [=pkgOf(op.child.className, "models")].[=op.child.dtoName];
import [=pkgOf(op.entity.className, "models")].[=op.entity.dtoName];
</#if></#list>
import [=sharedModelPackage].PageResponse;
import [=exceptionPackage].ResourceNotFoundException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use case "[=u.name]": operations that span tables, each in one transaction.
 *
 * [=generatedNote]
 */
@Service
@Transactional
public class [=u.serviceName] {
<#list u.services as e>

    private final [=e.serviceName] [=e.varName]Service;
</#list>
<#list u.mappers as e>
    private final [=e.mapperName] [=e.varName]Mapper;
</#list>
<#list u.repositories as e>
    private final [=e.repositoryName] [=e.varName]Repository;
</#list>

    public [=u.serviceName](<#list u.services as e>[=e.serviceName] [=e.varName]Service<#sep>, </#sep></#list><#if u.services?has_content && (u.mappers?has_content || u.repositories?has_content)>, </#if><#list u.mappers as e>[=e.mapperName] [=e.varName]Mapper<#sep>, </#sep></#list><#if u.mappers?has_content && u.repositories?has_content>, </#if><#list u.repositories as e>[=e.repositoryName] [=e.varName]Repository<#sep>, </#sep></#list>) {
<#list u.services as e>
        this.[=e.varName]Service = [=e.varName]Service;
</#list>
<#list u.mappers as e>
        this.[=e.varName]Mapper = [=e.varName]Mapper;
</#list>
<#list u.repositories as e>
        this.[=e.varName]Repository = [=e.varName]Repository;
</#list>
    }
<#list u.operations as op>
<#if op.kind != "ENTITY">
<#assign p = op.entity>
<#assign c = op.child>
<#assign pid = p.id.field.name>
<#if op.kind == "WITH_CHILDREN">

    @Transactional(readOnly = true)
    public [=op.responseRecord] [=op.method]([=p.id.javaType] id) {
        [=p.dtoName] [=op.parentProperty] = [=p.varName]Service.read(id);
        List<[=c.dtoName]> [=op.childrenProperty] = [=c.varName]Repository.[=op.childQuery](id).stream()
                .map([=c.varName]Mapper::toDto).toList();
        return new [=op.responseRecord]([=op.parentProperty], [=op.childrenProperty]);
    }
<#elseif op.kind == "CHILDREN">

    @Transactional(readOnly = true)
    public PageResponse<[=c.dtoName]> [=op.method]([=p.id.javaType] id, Pageable pageable) {
        if (![=p.varName]Repository.existsById(id)) {
            throw new ResourceNotFoundException("[=p.className] " + id + " not found");
        }
        return PageResponse.of([=c.varName]Repository.[=op.childQuery](id, [=c.serviceName].sorted(pageable))
                .map([=c.varName]Mapper::toDto));
    }
<#else>
<#assign create = op.kind == "AGGREGATE_CREATE">

    public [=op.responseRecord] [=op.method](<#if !create>[=p.id.javaType] id, </#if>[=op.requestRecord] request) {
<#if create>
        [=p.dtoName] [=op.parentProperty] = [=p.varName]Service.create(request.[=op.parentProperty]());
<#else>
        [=p.dtoName] [=op.parentProperty] = [=p.varName]Service.update(id, request.[=op.parentProperty]());
        // the request carries the complete list: the old [=op.childrenProperty] are replaced
        [=c.varName]Repository.deleteAll([=c.varName]Repository.[=op.childQuery](id));
        [=c.varName]Repository.flush();
</#if>
        List<[=c.dtoName]> [=op.childrenProperty] = new ArrayList<>();
        for ([=op.itemRecord] item : request.[=op.childrenProperty]()) {
            [=op.childrenProperty].add([=c.varName]Service.create(new [=c.requestName](<#list c.requestComponents() as f><#if f.name == op.linkComponent>[=op.parentProperty].[=pid]()<#else>item.[=f.name]()</#if><#sep>, </#sep></#list>)));
        }
        return new [=op.responseRecord]([=op.parentProperty], [=op.childrenProperty]);
    }
</#if>
</#if>
</#list>
}
