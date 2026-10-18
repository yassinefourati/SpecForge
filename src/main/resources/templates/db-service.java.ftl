package [=pkg];
<#assign e = entity.db>
<#function conv t><#return {"String":"asString","Integer":"asInteger","Long":"asLong","Short":"asShort","BigDecimal":"asBigDecimal","Double":"asDouble","Float":"asFloat","Boolean":"asBoolean","UUID":"asUuid","LocalDate":"asLocalDate","LocalTime":"asLocalTime","OffsetTime":"asOffsetTime","LocalDateTime":"asLocalDateTime","OffsetDateTime":"asOffsetDateTime","byte[]":"asBytes"}[t]!"asString"></#function>
<#-- DTO property → entity attribute path, for filtering and sorting -->
<#assign paths = {}>
<#assign types = {}>
<#if e.id.composite()>
<#list e.id.keyFields as f><#assign paths = paths + {f.name: "id." + f.name}><#assign types = types + {f.name: f.javaType}></#list>
<#else>
<#assign paths = paths + {e.id.field.name: e.id.field.name}><#assign types = types + {e.id.field.name: e.id.field.javaType}>
</#if>
<#list e.fields as f><#assign paths = paths + {f.name: f.name}><#assign types = types + {f.name: f.javaType}></#list>
<#list e.relations as r><#if r.dtoField??><#assign paths = paths + {r.dtoField.name: r.name + "." + r.targetIdName}><#assign types = types + {r.dtoField.name: r.dtoField.javaType}></#if></#list>
<#-- list parameters besides the equality filters (CONTRACT-3), and the key order of a lenient sort -->
<#assign params = e.filterable>
<#if e.searchable?has_content><#assign params = params + ["q"]></#if>
<#list e.rangeable as p><#assign params = params + [p + "From", p + "To"]></#list>
<#assign keyPaths = []>
<#if e.id.composite()><#list e.id.keyFields as f><#assign keyPaths = keyPaths + ["id." + f.name]></#list><#else><#assign keyPaths = [e.id.field.name]></#if>
<#function getPath p t><#local segs = paths[p]?split(".")><#local out = "root"><#list segs as seg><#if seg?is_last><#local out = out + ".<" + t + ">get(\"" + seg + "\")"><#else><#local out = out + ".get(\"" + seg + "\")"></#if></#list><#return out></#function>
<#assign writes = e.serviceOp("create") || e.serviceOp("update") || e.serviceOp("patch") || e.serviceOp("delete")>
<#assign validatePatch = options.validation && e.serviceOp("patch")>

<#list e.dtoImports as i>
import [=i];
</#list>
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
<#if e.searchable?has_content>
import java.util.Locale;
</#if>
<#if validatePatch>
import java.util.Set;
import [=ns].validation.ConstraintViolation;
import [=ns].validation.ConstraintViolationException;
import [=ns].validation.Validator;
</#if>
import [=ns].persistence.EntityManager;
import [=ns].persistence.criteria.Predicate;
import [=entityPackage].[=e.className];
import [=exceptionPackage].ResourceNotFoundException;
import [=modelPackage].[=e.dtoName];
import [=modelPackage].[=e.requestName];
import [=sharedModelPackage].PageResponse;
<#if e.filterable?has_content || e.rangeable?has_content>
import [=sharedServicePackage].ValueConversions;
</#if>
import [=repositoryPackage].[=e.repositoryName];
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Operations on [=e.className] (table [=e.qualifiedName]).
 *
 * [=generatedNote]
 */
@Service
@Transactional
public class [=e.serviceName] {

    /** DTO properties that can sort the list, and the entity attribute behind each. */
    private static final Map<String, String> SORTABLE = Map.ofEntries(
<#list e.sortable as s>
            Map.entry("[=s]", "[=paths[s]]")<#sep>,</#sep>
</#list>
    );

    private final [=e.repositoryName] repository;
    private final [=e.mapperName] mapper;
    private final EntityManager entityManager;
<#if validatePatch>
    private final Validator validator;
</#if>

    public [=e.serviceName]([=e.repositoryName] repository, [=e.mapperName] mapper, EntityManager entityManager<#if validatePatch>,
            Validator validator</#if>) {
        this.repository = repository;
        this.mapper = mapper;
        this.entityManager = entityManager;
<#if validatePatch>
        this.validator = validator;
</#if>
    }
<#if e.serviceOp("list")>

<#if params?size == e.filterable?size>
    /** One page, filtered by equality on the given properties ([=e.filterable?join(", ")]). */
<#else>
    /**
     * One page, filtered by equality on [=e.filterable?has_content?then(e.filterable?join(", "), "no property")]<#if e.searchable?has_content>; q searches
     * [=e.searchable?join(", ")] (case-insensitive, contains)</#if><#if e.rangeable?has_content>; [=e.rangeable?map(p -> p + "From / " + p + "To")?join(", ")] are
     * inclusive bounds</#if>.
     */
</#if>
    @Transactional(readOnly = true)
    public PageResponse<[=e.dtoName]> list(Map<String, String> filters, Pageable pageable) {
        Specification<[=e.className]> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            for (Map.Entry<String, String> f : filters.entrySet()) {
                switch (f.getKey()) {
<#list e.filterable as p>
<#assign path = paths[p]?split(".")>
                    case "[=p]" -> predicates.add(cb.equal(root<#list path as seg>.get("[=seg]")</#list>, ValueConversions.[=conv(types[p])](f.getValue())));
</#list>
<#if e.searchable?has_content>
                    case "q" -> {
                        if (!f.getValue().isBlank()) {
                            String pattern = "%" + likeLiteral(f.getValue().trim().toLowerCase(Locale.ROOT)) + "%";
                            predicates.add(cb.or(
<#list e.searchable as p>
                                    cb.like(cb.lower([=getPath(p, "String")]), pattern, '\\')<#sep>,</#sep>
</#list>
                            ));
                        }
                    }
</#if>
<#list e.rangeable as p>
                    case "[=p]From" -> predicates.add(cb.greaterThanOrEqualTo([=getPath(p, types[p])], ValueConversions.[=conv(types[p])](f.getValue())));
                    case "[=p]To" -> predicates.add(cb.lessThanOrEqualTo([=getPath(p, types[p])], ValueConversions.[=conv(types[p])](f.getValue())));
</#list>
                    default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Unknown filter '" + f.getKey() + "'. Filterable: [=params?join(", ")]");
                }
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return PageResponse.of(repository.findAll(spec, sorted(pageable)).map(mapper::toDto));
    }
<#if e.searchable?has_content>

    /** The text with LIKE's wildcards escaped, so "50%" finds "50%" and not "50 anything". */
    private static String likeLiteral(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
</#if>
</#if>

<#if lists.lenientSort()>
    /**
     * The page request with sort properties mapped to entity attributes; a property other than
     * [=e.sortable?join(", ")] is ignored. The key comes last, so the order is the key by default and ties never
     * make pages overlap or skip rows.
     */
    <#if config.architecture != "LAYERED">public </#if>static Pageable sorted(Pageable pageable) {
        List<Sort.Order> orders = new ArrayList<>();
        for (Sort.Order o : pageable.getSort()) {
            String path = SORTABLE.get(o.getProperty());
            if (path != null) {
                orders.add(o.withProperty(path));
            }
        }
<#list keyPaths as k>
        if (orders.stream().noneMatch(o -> o.getProperty().equals("[=k]"))) {
            orders.add(Sort.Order.asc("[=k]"));
        }
</#list>
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(orders));
    }
<#else>
    /** The page request with sort properties checked against [=e.sortable?join(", ")] and mapped to entity attributes. */
    <#if config.architecture != "LAYERED">public </#if>static Pageable sorted(Pageable pageable) {
        List<Sort.Order> orders = new ArrayList<>();
        for (Sort.Order o : pageable.getSort()) {
            String path = SORTABLE.get(o.getProperty());
            if (path == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot sort by '" + o.getProperty() + "'. Sortable: " + SORTABLE.keySet());
            }
            orders.add(o.withProperty(path));
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(orders));
    }
</#if>
<#if e.serviceOp("read")>

    @Transactional(readOnly = true)
    public [=e.dtoName] read([=e.id.javaType] id) {
        return mapper.toDto(find(id));
    }
</#if>
<#if e.serviceOp("create")>

    public [=e.dtoName] create([=e.requestName] dto) {
<#if e.id.composite()>
        [=e.id.javaType] id = new [=e.id.javaType](<#list e.id.keyFields as f>dto.[=f.name]()<#sep>, </#sep></#list>);
        if (<#list e.id.keyFields as f>id.get[=f.cap]() == null<#sep> || </#sep></#list>) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Every key property is required: [=e.id.keyFields?map(f -> f.name)?join(", ")]");
        }
        if (repository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "[=e.className] " + id + " already exists");
        }
<#elseif !e.id.generated()>
        if (dto.[=e.id.field.name]() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "[=e.id.field.name] is required");
        }
        if (repository.existsById(dto.[=e.id.field.name]())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "[=e.className] " + dto.[=e.id.field.name]() + " already exists");
        }
</#if>
        [=e.className] entity = new [=e.className]();
        mapper.copyToEntity(dto, entity, true);
        [=e.className] saved = repository.saveAndFlush(entity);
        // read back what the database filled in (defaults, generated columns)
        entityManager.refresh(saved);
        return mapper.toDto(saved);
    }
</#if>
<#if e.serviceOp("update")>

    public [=e.dtoName] update([=e.id.javaType] id, [=e.requestName] dto) {
        [=e.className] entity = find(id);
        mapper.copyToEntity(dto, entity, false);
        return mapper.toDto(repository.saveAndFlush(entity));
    }
</#if>
<#if e.serviceOp("patch")>

    public [=e.dtoName] patch([=e.id.javaType] id, Map<String, Object> body) {
        [=e.className] entity = find(id);
        mapper.patch(body, entity);
<#if validatePatch>
        Set<ConstraintViolation<[=e.requestName]>> violations = validator.validate(mapper.<#if e.split>toRequest<#else>toDto</#if>(entity));
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
</#if>
        return mapper.toDto(repository.saveAndFlush(entity));
    }
</#if>
<#if e.serviceOp("delete")>

    public void delete([=e.id.javaType] id) {
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("[=e.className] " + id + " not found");
        }
        repository.deleteById(id);
        repository.flush();
    }
</#if>

    private [=e.className] find([=e.id.javaType] id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("[=e.className] " + id + " not found"));
    }
}
