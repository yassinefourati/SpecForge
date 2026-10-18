package [=pkg];
<#assign e = entity.db>
<#function conv t><#return {"String":"asString","Integer":"asInteger","Long":"asLong","Short":"asShort","BigDecimal":"asBigDecimal","Double":"asDouble","Float":"asFloat","Boolean":"asBoolean","UUID":"asUuid","LocalDate":"asLocalDate","LocalTime":"asLocalTime","OffsetTime":"asOffsetTime","LocalDateTime":"asLocalDateTime","OffsetDateTime":"asOffsetDateTime","byte[]":"asBytes"}[t]!"asString"></#function>

<#list e.dtoImports as i>
import [=i];
</#list>
import java.util.Map;
<#if e.manyToMany?has_content>
import java.util.LinkedHashSet;
import java.util.stream.Collectors;
</#if>
import [=entityPackage].[=e.className];
<#list e.relations as r>
import [=pkgOf(r.targetClass, "entities")].[=r.targetClass];
</#list>
<#list e.manyToMany as m>
import [=pkgOf(m.targetClass, "entities")].[=m.targetClass];
</#list>
import [=sharedServicePackage].ValueConversions;
import [=modelPackage].[=e.dtoName];
import [=modelPackage].[=e.requestName];
import [=ns].persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Converts between [=e.className] and [=e.dtoName]<#if e.split> / [=e.requestName]</#if>. Related rows are attached by reference (no extra query).
 *
 * [=generatedNote]
 */
@Component
public class [=e.mapperName] {

    private final EntityManager entityManager;

    public [=e.mapperName](EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public [=e.dtoName] toDto([=e.className] entity) {
        return new [=e.dtoName](
<#assign args = []>
<#if e.id.composite()>
<#list e.id.keyFields as f><#assign args = args + ["entity.getId().get" + f.cap + "()"]></#list>
<#else>
<#assign args = args + ["entity.get" + e.id.field.cap + "()"]>
</#if>
<#list e.fields as f><#assign args = args + ["entity.get" + f.cap + "()"]></#list>
<#list e.relations as r><#if r.dtoField??><#assign args = args + ["entity.get" + r.cap + "() == null ? null : entity.get" + r.cap + "().get" + r.targetIdCap + "()"]></#if></#list>
<#list e.manyToMany as m><#assign args = args + ["entity.get" + m.cap + "().stream().map(" + m.targetClass + "::get" + m.targetIdCap + ").collect(Collectors.toCollection(LinkedHashSet::new))"]></#list>
<#list args as a>
            [=a]<#sep>,</#sep>
</#list>
        );
    }
<#if e.split && options.validation && e.serviceOp("patch")>
<#assign byName = {}>
<#list e.dtoComponents() as f><#assign byName = byName + {f.name: args[f?index]}></#list>

    /** The entity's current values as a request, so a patched entity is checked against the request's rules. */
    public [=e.requestName] toRequest([=e.className] entity) {
        return new [=e.requestName](
<#list e.requestComponents() as f>
            [=byName[f.name]]<#sep>,</#sep>
</#list>
        );
    }
</#if>

    /** Copies every writable value of the DTO; the key only when {@code includeKey} (creation with an assigned key). */
    public void copyToEntity([=e.requestName] dto, [=e.className] entity, boolean includeKey) {
<#if e.id.composite()>
        if (includeKey) {
<#list e.id.keyFields as f>
            entity.getId().set[=f.cap](dto.[=f.name]());
</#list>
        }
<#elseif !e.id.generated()>
        if (includeKey) {
            entity.set[=e.id.field.cap](dto.[=e.id.field.name]());
        }
</#if>
<#list e.fields as f><#if f.writable>
<#if !f.nullable && f.dbDefault>
        if (dto.[=f.name]() != null) {
            // omitted: the database default applies on insert, the current value is kept on update
            entity.set[=f.cap](dto.[=f.name]());
        }
<#else>
        entity.set[=f.cap](dto.[=f.name]());
</#if>
</#if></#list>
<#list e.relations as r>
<#if r.dtoField??>
        entity.set[=r.cap](dto.[=r.dtoField.name]() == null ? null : entityManager.getReference([=r.targetClass].class, dto.[=r.dtoField.name]()));
<#else>
<#list e.id.keyFields as kf><#if kf.name == r.mapsId>
        if (includeKey) {
            entity.set[=r.cap](entityManager.getReference([=r.targetClass].class, dto.[=kf.name]()));
        }
</#if></#list>
</#if>
</#list>
<#list e.manyToMany as m>
        entity.get[=m.cap]().clear();
        if (dto.[=m.dtoName]() != null) {
            for ([=m.targetIdType] id : dto.[=m.dtoName]()) {
                entity.get[=m.cap]().add(entityManager.getReference([=m.targetClass].class, id));
            }
        }
</#list>
    }

    /**
     * JSON Merge Patch: a property present in the body is applied (null clears it), an absent one is left alone.
     * Key properties cannot be patched.
     */
    public void patch(Map<String, Object> body, [=e.className] entity) {
<#list e.fields as f><#if f.writable>
        if (body.containsKey("[=f.name]")) {
<#if !f.nullable>
            if (body.get("[=f.name]") == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "[=f.name] cannot be null");
            }
</#if>
            entity.set[=f.cap](ValueConversions.[=conv(f.javaType)](body.get("[=f.name]")));
        }
</#if></#list>
<#list e.relations as r><#if r.dtoField??>
        if (body.containsKey("[=r.dtoField.name]")) {
            [=r.dtoField.javaType] id = ValueConversions.[=conv(r.dtoField.javaType)](body.get("[=r.dtoField.name]"));
            entity.set[=r.cap](id == null ? null : entityManager.getReference([=r.targetClass].class, id));
        }
</#if></#list>
<#list e.manyToMany as m>
        if (body.containsKey("[=m.dtoName]")) {
            entity.get[=m.cap]().clear();
            for (Object id : ValueConversions.asList(body.get("[=m.dtoName]"))) {
                entity.get[=m.cap]().add(entityManager.getReference([=m.targetClass].class, ValueConversions.[=conv(m.targetIdType)](id)));
            }
        }
</#list>
    }
}
