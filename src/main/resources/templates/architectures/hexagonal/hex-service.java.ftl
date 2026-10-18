package [=pkg];
<#assign e = entity.db>
<#function conv t><#return {"String":"asString","Integer":"asInteger","Long":"asLong","Short":"asShort","BigDecimal":"asBigDecimal","Double":"asDouble","Float":"asFloat","Boolean":"asBoolean","UUID":"asUuid","LocalDate":"asLocalDate","LocalTime":"asLocalTime","OffsetTime":"asOffsetTime","LocalDateTime":"asLocalDateTime","OffsetDateTime":"asOffsetDateTime","byte[]":"asBytes"}[t]!"asString"></#function>
<#assign key = e.id.field>
<#assign validatePatch = options.validation>

<#list e.dtoImports as i>
import [=i];
</#list>
import java.util.Map;
<#if e.manyToMany?has_content>
import java.util.LinkedHashSet;
</#if>
<#if validatePatch>
import java.util.Set;
import [=ns].validation.ConstraintViolation;
import [=ns].validation.ConstraintViolationException;
import [=ns].validation.Validator;
</#if>
import [=pkgOf(e.className, "domain")].[=e.className];
import [=modelPackage].[=e.className]UseCases;
import [=modelPackage].[=e.dtoName];
import [=modelPackage].[=e.requestName];
import [=pkgOf(e.className, "portOut")].[=e.className]RepositoryPort;
import [=exceptionPackage].ResourceNotFoundException;
import [=sharedModelPackage].PageQuery;
import [=sharedModelPackage].PageResult;
import [=sharedServicePackage].ValueConversions;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The application service of [=e.className]: implements the inbound port over the outbound one, and maps between
 * the port's records and the domain model.
 *
 * [=generatedNote]
 */
@Service
@Transactional
public class [=e.className]Service implements [=e.className]UseCases {

    private final [=e.className]RepositoryPort repository;
<#if validatePatch>
    private final Validator validator;
</#if>

    public [=e.className]Service([=e.className]RepositoryPort repository<#if validatePatch>, Validator validator</#if>) {
        this.repository = repository;
<#if validatePatch>
        this.validator = validator;
</#if>
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<[=e.dtoName]> list(Map<String, String> filters, PageQuery page) {
        return repository.findAll(filters, page).map([=e.className]Service::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public [=e.dtoName] read([=e.id.javaType] id) {
        return toResponse(find(id));
    }

    @Override
    public [=e.dtoName] create([=e.requestName] request) {
        [=e.className] item = new [=e.className]();
<#if !e.id.generated()>
        if (request.[=key.name]() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "[=key.name] is required");
        }
        if (repository.existsById(request.[=key.name]())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "[=e.className] " + request.[=key.name]() + " already exists");
        }
        item.set[=key.cap](request.[=key.name]());
</#if>
        apply(request, item);
        return toResponse(repository.create(item));
    }

    @Override
    public [=e.dtoName] update([=e.id.javaType] id, [=e.requestName] request) {
        [=e.className] item = find(id);
        apply(request, item);
        return toResponse(repository.update(item));
    }

    @Override
    public [=e.dtoName] patch([=e.id.javaType] id, Map<String, Object> changes) {
        [=e.className] item = find(id);
<#list e.fields as f><#if f.writable>
        if (changes.containsKey("[=f.name]")) {
<#if !f.nullable>
            if (changes.get("[=f.name]") == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "[=f.name] cannot be null");
            }
</#if>
            item.set[=f.cap](ValueConversions.[=conv(f.javaType)](changes.get("[=f.name]")));
        }
</#if></#list>
<#list e.relations as r><#if r.dtoField??>
        if (changes.containsKey("[=r.dtoField.name]")) {
            item.set[=r.dtoField.cap](ValueConversions.[=conv(r.dtoField.javaType)](changes.get("[=r.dtoField.name]")));
        }
</#if></#list>
<#list e.manyToMany as m>
        if (changes.containsKey("[=m.dtoName]")) {
            LinkedHashSet<[=m.targetIdType]> ids = new LinkedHashSet<>();
            for (Object v : ValueConversions.asList(changes.get("[=m.dtoName]"))) {
                ids.add(ValueConversions.[=conv(m.targetIdType)](v));
            }
            item.set[=m.dtoCap](ids);
        }
</#list>
<#if validatePatch>
        Set<ConstraintViolation<[=e.requestName]>> violations = validator.validate(toRequest(item));
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
</#if>
        return toResponse(repository.update(item));
    }

    @Override
    public void delete([=e.id.javaType] id) {
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("[=e.className] " + id + " not found");
        }
        repository.deleteById(id);
    }

    private [=e.className] find([=e.id.javaType] id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("[=e.className] " + id + " not found"));
    }

    /** The request's values onto the domain model (the key is the creation's business, see create). */
    private static void apply([=e.requestName] request, [=e.className] item) {
<#list e.requestComponents() as f><#if f.name != key.name>
        item.set[=f.cap](request.[=f.name]());
</#if></#list>
    }

    private static [=e.dtoName] toResponse([=e.className] item) {
        return new [=e.dtoName](
<#list e.dtoComponents() as f>
                item.get[=f.cap]()<#sep>,</#sep>
</#list>
        );
    }
<#if validatePatch>

    private static [=e.requestName] toRequest([=e.className] item) {
        return new [=e.requestName](
<#list e.requestComponents() as f>
                item.get[=f.cap]()<#sep>,</#sep>
</#list>
        );
    }
</#if>
}
