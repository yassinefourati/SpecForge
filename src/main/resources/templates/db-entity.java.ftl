package [=pkg];
<#assign e = entity.db>

<#list e.entityImports as i>
import [=i];
</#list>
<#list e.relations as r>
import [=pkgOf(r.targetClass, "entities")].[=r.targetClass];
</#list>
<#list e.manyToMany as m>
import [=pkgOf(m.targetClass, "entities")].[=m.targetClass];
</#list>
<#list e.inverses as inv>
import [=pkgOf(inv.sourceClass, "entities")].[=inv.sourceClass];
</#list>
import [=ns].persistence.Column;
import [=ns].persistence.Entity;
import [=ns].persistence.Table;
<#if e.uniqueColumns?has_content>
import [=ns].persistence.UniqueConstraint;
</#if>
<#if e.id.composite()>
import [=ns].persistence.Embeddable;
import [=ns].persistence.EmbeddedId;
import java.io.Serializable;
import java.util.Objects;
<#else>
import [=ns].persistence.Id;
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
<#if e.inverses?has_content>
import [=ns].persistence.OneToMany;
<#if !(e.relations?has_content || e.manyToMany?has_content)>
import [=ns].persistence.FetchType;
</#if>
</#if>
<#list e.relations as r><#if r.mapsId?has_content>
import [=ns].persistence.MapsId;
<#break></#if></#list>
<#if e.manyToMany?has_content>
import [=ns].persistence.JoinTable;
import [=ns].persistence.ManyToMany;
</#if>
<#if e.fields?filter(f -> f.jdbcTypeCode?has_content)?has_content || e.id.keyFields?filter(f -> f.jdbcTypeCode?has_content)?has_content
    || (e.id.field?? && e.id.field.jdbcTypeCode?has_content)>
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
<#if e.comment?has_content>
 * [=doc(e.comment)]
 *
</#if>
 * Table [=e.qualifiedName] ([=source.engineLabel]).
<#if e.readOnly>
 * Read-only: [=doc(e.readOnlyReason)].
</#if>
 *
 * [=generatedNote]
 */
@Entity
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
public class [=e.className] {

<#if e.id.composite()>
    @EmbeddedId
    private Key id = new Key();
<#else>
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
</#if>
<#list e.fields as f>

<@column f/>
    private [=f.javaType] [=f.name];
</#list>
<#list e.relations as r>

<#if r.mapsId?has_content>
    @MapsId("[=r.mapsId]")
</#if>
    @ManyToOne(fetch = FetchType.LAZY<#if !r.nullable>, optional = false</#if>)
    @JoinColumn(name = "[=r.column?j_string]")
    private [=r.targetClass] [=r.name];
</#list>
<#list e.manyToMany as m>

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "[=m.joinTable?j_string]"<#if m.joinSchema?has_content>, schema = "[=m.joinSchema?j_string]"</#if>,
        joinColumns = @JoinColumn(name = "[=m.joinColumn?j_string]"),
        inverseJoinColumns = @JoinColumn(name = "[=m.inverseJoinColumn?j_string]"))
    private Set<[=m.targetClass]> [=m.name] = new LinkedHashSet<>();
</#list>
<#list e.inverses as inv>

    /** [=inv.sourceClass] rows referencing this one (read side of [=inv.sourceClass].[=inv.mappedBy]; not part of the DTO). */
    @OneToMany(mappedBy = "[=inv.mappedBy]", fetch = FetchType.LAZY)
    private List<[=inv.sourceClass]> [=inv.name] = new ArrayList<>();
</#list>
<#if e.id.composite()>

    public Key getId() {
        return id;
    }

    public void setId(Key id) {
        this.id = id;
    }
<#else>

    public [=e.id.field.javaType] get[=e.id.field.cap]() {
        return [=e.id.field.name];
    }

    public void set[=e.id.field.cap]([=e.id.field.javaType] [=e.id.field.name]) {
        this.[=e.id.field.name] = [=e.id.field.name];
    }
</#if>
<#list e.fields as f>

    public [=f.javaType] get[=f.cap]() {
        return [=f.name];
    }

    public void set[=f.cap]([=f.javaType] [=f.name]) {
        this.[=f.name] = [=f.name];
    }
</#list>
<#list e.relations as r>

    public [=r.targetClass] get[=r.cap]() {
        return [=r.name];
    }

    public void set[=r.cap]([=r.targetClass] [=r.name]) {
        this.[=r.name] = [=r.name];
    }
</#list>
<#list e.manyToMany as m>

    public Set<[=m.targetClass]> get[=m.cap]() {
        return [=m.name];
    }

    public void set[=m.cap](Set<[=m.targetClass]> [=m.name]) {
        this.[=m.name] = [=m.name];
    }
</#list>
<#list e.inverses as inv>

    public List<[=inv.sourceClass]> get[=inv.cap]() {
        return [=inv.name];
    }
</#list>
<#if e.id.composite()>

    /** Composite primary key of [=e.qualifiedName]. */
    @Embeddable
    public static class Key implements Serializable {
<#list e.id.keyFields as f>

<@column f "        "/>
        private [=f.javaType] [=f.name];
</#list>

        public Key() {
        }

        public Key(<#list e.id.keyFields as f>[=f.javaType] [=f.name]<#sep>, </#sep></#list>) {
<#list e.id.keyFields as f>
            this.[=f.name] = [=f.name];
</#list>
        }
<#list e.id.keyFields as f>

        public [=f.javaType] get[=f.cap]() {
            return [=f.name];
        }

        public void set[=f.cap]([=f.javaType] [=f.name]) {
            this.[=f.name] = [=f.name];
        }
</#list>

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key other)) {
                return false;
            }
            return <#list e.id.keyFields as f>Objects.equals([=f.name], other.[=f.name])<#sep> && </#sep></#list>;
        }

        @Override
        public int hashCode() {
            return Objects.hash(<#list e.id.keyFields as f>[=f.name]<#sep>, </#sep></#list>);
        }

        @Override
        public String toString() {
            return "(" + <#list e.id.keyFields as f>[=f.name]<#sep> + ", " + </#sep></#list> + ")";
        }
    }
</#if>
}
