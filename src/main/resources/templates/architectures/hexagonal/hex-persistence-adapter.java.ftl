package [=pkg];
<#assign e = entity.db>
<#assign J = e.className + "JpaEntity">
<#function jpa cls><#return cls + "JpaEntity"></#function>
<#function conv t><#return {"String":"asString","Integer":"asInteger","Long":"asLong","Short":"asShort","BigDecimal":"asBigDecimal","Double":"asDouble","Float":"asFloat","Boolean":"asBoolean","UUID":"asUuid","LocalDate":"asLocalDate","LocalTime":"asLocalTime","OffsetTime":"asOffsetTime","LocalDateTime":"asLocalDateTime","OffsetDateTime":"asOffsetDateTime","byte[]":"asBytes"}[t]!"asString"></#function>
<#-- API property → JPA attribute path, for filtering and sorting -->
<#assign paths = {e.id.field.name: e.id.field.name}>
<#assign types = {e.id.field.name: e.id.field.javaType}>
<#list e.fields as f><#assign paths = paths + {f.name: f.name}><#assign types = types + {f.name: f.javaType}></#list>
<#list e.relations as r><#if r.dtoField??><#assign paths = paths + {r.dtoField.name: r.name + "." + r.targetIdName}><#assign types = types + {r.dtoField.name: r.dtoField.javaType}></#if></#list>
<#assign params = e.filterable>
<#if e.searchable?has_content><#assign params = params + ["q"]></#if>
<#list e.rangeable as p><#assign params = params + [p + "From", p + "To"]></#list>
<#function getPath p t><#local segs = paths[p]?split(".")><#local out = "root"><#list segs as seg><#if seg?is_last><#local out = out + ".<" + t + ">get(\"" + seg + "\")"><#else><#local out = out + ".get(\"" + seg + "\")"></#if></#list><#return out></#function>

<#list e.dtoImports as i>
import [=i];
</#list>
import java.util.ArrayList;
import java.util.List;
<#if e.searchable?has_content>
import java.util.Locale;
</#if>
import java.util.Map;
import java.util.Optional;
<#if e.manyToMany?has_content>
import java.util.LinkedHashSet;
import java.util.stream.Collectors;
</#if>
import [=ns].persistence.EntityManager;
import [=ns].persistence.criteria.Predicate;
import [=pkgOf(e.className, "domain")].[=e.className];
import [=pkgOf(e.className, "portOut")].[=e.className]RepositoryPort;
<#list e.relations as r>
import [=pkgOf(r.targetClass, "entities")].[=jpa(r.targetClass)];
</#list>
<#list e.manyToMany as m>
import [=pkgOf(m.targetClass, "entities")].[=jpa(m.targetClass)];
</#list>
import [=sharedModelPackage].PageQuery;
import [=sharedModelPackage].PageResult;
<#if e.filterable?has_content || e.rangeable?has_content>
import [=sharedServicePackage].ValueConversions;
</#if>
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Implements [=e.className]RepositoryPort with JPA: filters and sorting as specifications, related rows attached by
 * reference (no extra query), the database's defaults read back after an insert.
 *
 * [=generatedNote]
 */
@Component
public class [=e.className]PersistenceAdapter implements [=e.className]RepositoryPort {

    /** API properties that can sort the list, and the JPA attribute behind each. */
    private static final Map<String, String> SORTABLE = Map.ofEntries(
<#list e.sortable as s>
            Map.entry("[=s]", "[=paths[s]]")<#sep>,</#sep>
</#list>
    );

    private final [=e.className]SpringDataRepository repository;
    private final EntityManager entityManager;

    public [=e.className]PersistenceAdapter([=e.className]SpringDataRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Override
    public PageResult<[=e.className]> findAll(Map<String, String> filters, PageQuery page) {
        Specification<[=J]> spec = (root, query, cb) -> {
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
        Page<[=J]> rows = repository.findAll(spec, PageRequest.of(page.page(), page.size(), sort(page)));
        return new PageResult<>(rows.getContent().stream().map([=e.className]PersistenceAdapter::toDomain).toList(),
                rows.getNumber(), rows.getSize(), rows.getTotalElements(), rows.getTotalPages());
    }

<#if lists.lenientSort()>
    /** The order, mapped to JPA attributes; a property other than [=e.sortable?join(", ")] is ignored, the key comes last. */
    static Sort sort(PageQuery page) {
        List<Sort.Order> orders = new ArrayList<>();
        for (PageQuery.Order o : page.orders()) {
            String path = SORTABLE.get(o.property());
            if (path != null) {
                orders.add(o.ascending() ? Sort.Order.asc(path) : Sort.Order.desc(path));
            }
        }
        if (orders.stream().noneMatch(o -> o.getProperty().equals("[=e.id.field.name]"))) {
            orders.add(Sort.Order.asc("[=e.id.field.name]"));
        }
        return Sort.by(orders);
    }
<#else>
    /** The order, checked against [=e.sortable?join(", ")] and mapped to JPA attributes. */
    static Sort sort(PageQuery page) {
        List<Sort.Order> orders = new ArrayList<>();
        for (PageQuery.Order o : page.orders()) {
            String path = SORTABLE.get(o.property());
            if (path == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot sort by '" + o.property() + "'. Sortable: " + SORTABLE.keySet());
            }
            orders.add(o.ascending() ? Sort.Order.asc(path) : Sort.Order.desc(path));
        }
        return Sort.by(orders);
    }
</#if>
<#if e.searchable?has_content>

    /** The text with LIKE's wildcards escaped, so "50%" finds "50%" and not "50 anything". */
    private static String likeLiteral(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
</#if>

    @Override
    public Optional<[=e.className]> findById([=e.id.javaType] id) {
        return repository.findById(id).map([=e.className]PersistenceAdapter::toDomain);
    }

    @Override
    public boolean existsById([=e.id.javaType] id) {
        return repository.existsById(id);
    }

    @Override
    public [=e.className] create([=e.className] item) {
        [=J] row = new [=J]();
<#if !e.id.generated()>
        row.set[=e.id.field.cap](item.get[=e.id.field.cap]());
</#if>
        copy(item, row);
        [=J] saved = repository.saveAndFlush(row);
        // read back what the database filled in (defaults, generated columns)
        entityManager.refresh(saved);
        return toDomain(saved);
    }

    @Override
    public [=e.className] update([=e.className] item) {
        [=J] row = repository.findById(item.get[=e.id.field.cap]()).orElseThrow();
        copy(item, row);
        return toDomain(repository.saveAndFlush(row));
    }

    @Override
    public void deleteById([=e.id.javaType] id) {
        repository.deleteById(id);
        repository.flush();
    }

    /** The domain model's writable values onto the row; related rows by reference. */
    private void copy([=e.className] item, [=J] row) {
<#list e.fields as f><#if f.writable>
<#if !f.nullable && f.dbDefault>
        if (item.get[=f.cap]() != null) {
            // left out: the database default applies on insert, the current value stays on update
            row.set[=f.cap](item.get[=f.cap]());
        }
<#else>
        row.set[=f.cap](item.get[=f.cap]());
</#if>
</#if></#list>
<#list e.relations as r><#if r.dtoField??>
        row.set[=r.cap](item.get[=r.dtoField.cap]() == null ? null : entityManager.getReference([=jpa(r.targetClass)].class, item.get[=r.dtoField.cap]()));
</#if></#list>
<#list e.manyToMany as m>
        row.get[=m.cap]().clear();
        if (item.get[=m.dtoCap]() != null) {
            for ([=m.targetIdType] id : item.get[=m.dtoCap]()) {
                row.get[=m.cap]().add(entityManager.getReference([=jpa(m.targetClass)].class, id));
            }
        }
</#list>
    }

    private static [=e.className] toDomain([=J] row) {
        [=e.className] item = new [=e.className]();
        item.set[=e.id.field.cap](row.get[=e.id.field.cap]());
<#list e.fields as f>
        item.set[=f.cap](row.get[=f.cap]());
</#list>
<#list e.relations as r><#if r.dtoField??>
        item.set[=r.dtoField.cap](row.get[=r.cap]() == null ? null : row.get[=r.cap]().get[=r.targetIdCap]());
</#if></#list>
<#list e.manyToMany as m>
        item.set[=m.dtoCap](row.get[=m.cap]().stream().map([=jpa(m.targetClass)]::get[=m.targetIdCap]).collect(Collectors.toCollection(LinkedHashSet::new)));
</#list>
        return item;
    }
}
