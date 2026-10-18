package [=pkg];
<#assign e = entity.db>
<#assign J = e.className + "JpaEntity">
<#function jpa cls><#return cls + "JpaEntity"></#function>

<#list e.entityImports as i>
import [=i];
</#list>
<#list e.relations as r>
import [=pkgOf(r.targetClass, "entities")].[=jpa(r.targetClass)];
</#list>
<#list e.manyToMany as m>
import [=pkgOf(m.targetClass, "entities")].[=jpa(m.targetClass)];
</#list>
import [=ns].persistence.Column;
import [=ns].persistence.Entity;
import [=ns].persistence.Id;
import [=ns].persistence.Table;
<#if e.uniqueColumns?has_content>
import [=ns].persistence.UniqueConstraint;
</#if>
<#if e.id.strategy != "ASSIGNED">
import [=ns].persistence.GeneratedValue;
import [=ns].persistence.GenerationType;
</#if>
<#if e.id.strategy == "SEQUENCE">
import [=ns].persistence.SequenceGenerator;
</#if>
<#if e.relations?has_content || e.manyToMany?has_content>
import [=ns].persistence.FetchType;
import [=ns].persistence.JoinColumn;
</#if>
<#if e.relations?has_content>
import [=ns].persistence.ManyToOne;
</#if>
<#if e.manyToMany?has_content>
import [=ns].persistence.JoinTable;
import [=ns].persistence.ManyToMany;
</#if>
<#if e.fields?filter(f -> f.jdbcTypeCode?has_content)?has_content || e.id.field.jdbcTypeCode?has_content>
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
</#if>
<#if e.readOnly>
import org.hibernate.annotations.Immutable;
</#if>
<#assign dynamicInsert = false>
<#list e.fields as f><#if f.dbDefault && f.writable><#assign dynamicInsert = true></#if></#list>
<#if dynamicInsert>
import org.hibernate.annotations.DynamicInsert;
</#if>
<#macro column f ind="    ">
<#if f.jdbcTypeCode?has_content>
[=ind]@JdbcTypeCode([=f.jdbcTypeCode])
</#if>
[=ind]@Column(name = "[=f.column?j_string]"<#if !f.nullable>, nullable = false</#if><#if f.length??>, length = [=f.length?c]</#if><#if f.precision??>, precision = [=f.precision?c], scale = [=f.scale?c]</#if><#if !f.writable>, insertable = false, updatable = false</#if>)
</#macro>

/**
 * Table [=e.qualifiedName] ([=source.engineLabel]) as JPA sees it: a detail of the persistence adapter, never seen
 * by the domain or the web adapter.
 *
 * [=generatedNote]
 */
@Entity(name = "[=e.className]")
<#if e.readOnly>
@Immutable
</#if>
<#if dynamicInsert>
// columns left null are omitted from INSERT, so the database defaults apply
@DynamicInsert
</#if>
@Table(name = "[=e.table?j_string]"<#if e.schema?has_content>, schema = "[=e.schema?j_string]"</#if><#if e.uniqueColumns?has_content>, uniqueConstraints = {
<#list e.uniqueColumns as u>
        @UniqueConstraint(columnNames = {<#list u as c>"[=c?j_string]"<#sep>, </#sep></#list>})<#sep>,</#sep>
</#list>
})<#else>)</#if>
public class [=J] {

    @Id
<#if e.id.strategy == "IDENTITY">
    @GeneratedValue(strategy = GenerationType.IDENTITY)
<#elseif e.id.strategy == "SEQUENCE">
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "[=e.id.sequenceName?j_string]")
    @SequenceGenerator(name = "[=e.id.sequenceName?j_string]", sequenceName = "[=e.id.sequenceName?j_string]"<#if e.id.sequenceSchema?has_content>, schema = "[=e.id.sequenceSchema?j_string]"</#if>, allocationSize = [=e.id.allocationSize?c])
<#elseif e.id.strategy == "UUID">
    @GeneratedValue(strategy = GenerationType.UUID)
</#if>
<@column e.id.field/>
    private [=e.id.field.javaType] [=e.id.field.name];
<#list e.fields as f>

<@column f/>
    private [=f.javaType] [=f.name];
</#list>
<#list e.relations as r>

    @ManyToOne(fetch = FetchType.LAZY<#if !r.nullable>, optional = false</#if>)
    @JoinColumn(name = "[=r.column?j_string]")
    private [=jpa(r.targetClass)] [=r.name];
</#list>
<#list e.manyToMany as m>

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "[=m.joinTable?j_string]"<#if m.joinSchema?has_content>, schema = "[=m.joinSchema?j_string]"</#if>,
        joinColumns = @JoinColumn(name = "[=m.joinColumn?j_string]"),
        inverseJoinColumns = @JoinColumn(name = "[=m.inverseJoinColumn?j_string]"))
    private Set<[=jpa(m.targetClass)]> [=m.name] = new LinkedHashSet<>();
</#list>

    public [=e.id.field.javaType] get[=e.id.field.cap]() {
        return [=e.id.field.name];
    }

    public void set[=e.id.field.cap]([=e.id.field.javaType] [=e.id.field.name]) {
        this.[=e.id.field.name] = [=e.id.field.name];
    }
<#list e.fields as f>

    public [=f.javaType] get[=f.cap]() {
        return [=f.name];
    }

    public void set[=f.cap]([=f.javaType] [=f.name]) {
        this.[=f.name] = [=f.name];
    }
</#list>
<#list e.relations as r>

    public [=jpa(r.targetClass)] get[=r.cap]() {
        return [=r.name];
    }

    public void set[=r.cap]([=jpa(r.targetClass)] [=r.name]) {
        this.[=r.name] = [=r.name];
    }
</#list>
<#list e.manyToMany as m>

    public Set<[=jpa(m.targetClass)]> get[=m.cap]() {
        return [=m.name];
    }
</#list>
}
