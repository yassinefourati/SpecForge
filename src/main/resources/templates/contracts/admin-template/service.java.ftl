package [=pkg];
<#assign x = module.of(entity.db)>
<#assign X = x.className>
<#function paramDecl p><#return p.javaType + " " + p.name></#function>
<#function attr p><#local segs = p.path?split(".")><#local out = "root"><#list segs as seg><#if seg?is_last><#local out = out + ".<" + p.javaType + ">get(\"" + seg + "\")"><#else><#local out = out + ".get(\"" + seg + "\")"></#if></#list><#return out></#function>
<#assign listArgs = []>
<#if x.searchPaths?has_content><#assign listArgs = listArgs + ["String q"]></#if>
<#list x.params as p><#assign listArgs = listArgs + [paramDecl(p)]></#list>
<#assign listNames = []>
<#if x.searchPaths?has_content><#assign listNames = listNames + ["q"]></#if>
<#list x.params as p><#assign listNames = listNames + [p.name]></#list>

<#list x.imports as i>
import [=i];
</#list>
import [=pkg].[=X]Dtos.[=X]Request;
import [=pkg].[=X]Dtos.[=X]Response;
import com.admintemplate.common.PageResponse;
import com.admintemplate.common.Paging;
import com.admintemplate.common.error.ApiException;
<#if module.permission?has_content>
import com.admintemplate.user.CurrentUserService;
</#if>
<#list x.relations as r>
<#if r.targetClass != X>
import [=pkg].[=r.targetClass];
</#if>
</#list>
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
<#if x.manyToMany?has_content>
import java.util.LinkedHashSet;
import java.util.stream.Collectors;
</#if>
<#if x.idName != "id">
import org.springframework.data.domain.PageRequest;
</#if>
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [=x.label]: list, read, create, update and delete.
 *
 * [=generatedNote]
 */
@Service("[=module.name][=X]Service")
public class [=X]Service {

    /** API property → entity attribute; a sort on anything else falls back to the default order (common.Paging). */
    private static final Map<String, String> SORTS = Map.ofEntries(
<#list x.sorts as k, v>
            Map.entry("[=k]", "[=v]")<#sep>,</#sep>
</#list>
    );
    private static final Sort DEFAULT_SORT = Sort.by("[=x.defaultSort]");

    private final [=X]Repository repository;
    private final EntityManager entityManager;
<#if module.permission?has_content>
    private final CurrentUserService currentUser;
</#if>

    public [=X]Service([=X]Repository repository, EntityManager entityManager<#if module.permission?has_content>, CurrentUserService currentUser</#if>) {
        this.repository = repository;
        this.entityManager = entityManager;
<#if module.permission?has_content>
        this.currentUser = currentUser;
</#if>
    }

    @Transactional(readOnly = true)
    public PageResponse<[=X]Response> list(<#list listArgs as a>[=a], </#list>int page, int size, String sort) {
<#if x.idName == "id">
        Pageable pageable = Paging.of(page, size, sort, SORTS, DEFAULT_SORT);
<#else>
        // common.Paging breaks ties on "id"; this table's key is [=x.idName]
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, Paging.MAX_SIZE), sort(sort));
</#if>
        boolean canManage = canManage();
        return PageResponse.of(repository.findAll(matching(<#list listNames as n>[=n]<#sep>, </#sep></#list>), pageable), e -> toResponse(e, canManage));
    }

    @Transactional(readOnly = true)
    public [=X]Response get([=x.idJavaType] id) {
        return toResponse(find(id), canManage());
    }

    @Transactional
    public [=X]Response create([=X]Request request) {
        [=X] entity = new [=X]();
<#if x.idStrategy == "ASSIGNED">
        if (request.[=x.idName]() == null) {
            throw ApiException.invalid("[=x.idName]", "error.validation");
        }
        if (repository.existsById(request.[=x.idName]())) {
            throw ApiException.conflict("error.integrity");
        }
        entity.set[=x.idCap](request.[=x.idName]());
</#if>
        apply(entity, request);
        return toResponse(repository.saveAndFlush(entity), canManage());
    }

    @Transactional
    public [=X]Response update([=x.idJavaType] id, [=X]Request request) {
        [=X] entity = find(id);
        apply(entity, request);
        return toResponse(repository.saveAndFlush(entity), canManage());
    }

    @Transactional
    public void delete([=x.idJavaType] id) {
        repository.delete(find(id));
        repository.flush();
    }

<#if x.idName != "id">
    /** The order of the list for its {@code sort} parameter ("property,asc|desc"); unknown properties give the default. */
    public static Sort sort(String sort) {
        if (sort == null || sort.isBlank()) {
            return DEFAULT_SORT;
        }
        String[] parts = sort.split(",");
        String property = SORTS.get(parts[0].trim());
        if (property == null) {
            return DEFAULT_SORT;
        }
        Sort primary = Sort.by(parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim()) ? Sort.Direction.DESC : Sort.Direction.ASC, property);
        return "[=x.idName]".equals(property) ? primary : primary.and(DEFAULT_SORT);
    }

</#if>
    /** The rows of the list for its filters<#if x.searchPaths?has_content>; q searches [=x.searchPaths?join(", ")]</#if>. */
    public static Specification<[=X]> matching(<#list listArgs as a>[=a]<#sep>, </#sep></#list>) {
        List<Specification<[=X]>> specs = new ArrayList<>();
<#if x.searchPaths?has_content>
        if (Paging.hasText(q)) {
            String pattern = Paging.likePattern(q);
            specs.add((root, query, cb) -> cb.or(
<#list x.searchPaths as sp>
                    cb.like(cb.lower(root.get("[=sp]")), pattern, '\\')<#sep>,</#sep>
</#list>
            ));
        }
</#if>
<#list x.params as p>
<#if p.kind == "EQ">
        if (<#if p.javaType == "String">Paging.hasText([=p.name])<#else>[=p.name] != null</#if>) {
            specs.add((root, query, cb) -> cb.equal([=attr(p)], [=p.name]));
        }
<#elseif p.kind == "FROM">
        if ([=p.name] != null) {
            specs.add((root, query, cb) -> cb.greaterThanOrEqualTo([=attr(p)], [=p.name]));
        }
<#else>
        if ([=p.name] != null) {
            specs.add((root, query, cb) -> cb.lessThanOrEqualTo([=attr(p)], [=p.name]));
        }
</#if>
</#list>
        return Specification.allOf(specs);
    }

    private [=X] find([=x.idJavaType] id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("error.notFound"));
    }

    private void apply([=X] entity, [=X]Request request) {
<#list x.columns as c><#if c.writable>
<#if c.dbDefault && !c.nullable>
        if (request.[=c.name]() != null) {
            // left out: the database default applies on insert, the current value stays on update
            entity.set[=c.cap](request.[=c.name]());
        }
<#else>
        entity.set[=c.cap](request.[=c.name]());
</#if>
</#if></#list>
<#list x.relations as r>
        entity.set[=r.cap](request.[=r.idName]() == null ? null : entityManager.getReference([=r.targetClass].class, request.[=r.idName]()));
</#list>
<#list x.manyToMany as j>
        entity.get[=j.cap]().clear();
        if (request.[=j.idName]() != null) {
            for ([=j.idType] id : request.[=j.idName]()) {
                entity.get[=j.cap]().add(entityManager.getReference([=j.targetClass].class, id));
            }
        }
</#list>
    }

    private boolean canManage() {
<#if module.permission?has_content>
        return currentUser.can("[=module.permission?j_string]");
<#else>
        return true;
</#if>
    }

    private static [=X]Response toResponse([=X] e, boolean canManage) {
        return new [=X]Response(
<#list x.response as c>
<#assign expr = c.read>
<#list x.manyToMany as j><#if j.idName == c.name><#assign expr = "e.get" + j.cap + "().stream().map(" + j.targetClass + "::get" + j.target.idCap + ").collect(Collectors.toCollection(LinkedHashSet::new))"></#if></#list>
                [=expr]<#sep>,</#sep>
</#list>
        );
    }
}
