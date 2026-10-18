package [=pkg];
<#assign x = module.of(entity.db)>
<#assign dynamicInsert = false>
<#list x.columns as c><#if c.dbDefault && c.writable><#assign dynamicInsert = true></#if></#list>

<#list x.imports as i>
import [=i];
</#list>
<#if x.baseEntity>
import com.admintemplate.common.BaseEntity;
<#if x.idColumn != "id">
import jakarta.persistence.AttributeOverride;
</#if>
<#else>
import jakarta.persistence.Id;
<#if x.idStrategy != "ASSIGNED">
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
</#if>
</#if>
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
<#if x.uniques?has_content>
import jakarta.persistence.UniqueConstraint;
</#if>
<#if x.relations?has_content || x.manyToMany?has_content>
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
</#if>
<#if x.relations?has_content>
import jakarta.persistence.ManyToOne;
</#if>
<#if x.manyToMany?has_content>
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import java.util.LinkedHashSet;
import java.util.Set;
</#if>
<#if x.columns?filter(c -> c.jdbcTypeCode?has_content)?has_content>
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
</#if>
<#if dynamicInsert>
import org.hibernate.annotations.DynamicInsert;
</#if>

/**
 * [=x.label] (table [=x.table?replace("`", "")]<#if !module.createTables>, from [=entity.db.qualifiedName]</#if>).
<#if entity.db.comment?has_content>
 * [=doc(entity.db.comment)]
</#if>
 *
 * [=generatedNote]
 */
@Entity(name = "[=x.entityName]")
<#if dynamicInsert>
// columns left null are omitted from INSERT, so the database defaults apply
@DynamicInsert
</#if>
@Table(name = "[=x.table?j_string]"<#if x.uniques?has_content>, uniqueConstraints = {
<#list x.uniques as u>
        @UniqueConstraint(columnNames = {<#list u as c>"[=c?j_string]"<#sep>, </#sep></#list>})<#sep>,</#sep>
</#list>
})<#else>)</#if>
<#if x.baseEntity && x.idColumn != "id">
@AttributeOverride(name = "id", column = @Column(name = "[=x.idColumn?j_string]"))
</#if>
public class [=x.className]<#if x.baseEntity> extends BaseEntity</#if> {
<#if !x.baseEntity>

    @Id
<#if x.idStrategy == "IDENTITY">
    @GeneratedValue(strategy = GenerationType.IDENTITY)
<#elseif x.idStrategy == "UUID">
    @GeneratedValue(strategy = GenerationType.UUID)
</#if>
    @Column(name = "[=x.idColumn?j_string]")
    private [=x.idJavaType] [=x.idName];
</#if>
<#list x.columns as c>

<#if c.jdbcTypeCode?has_content>
    @JdbcTypeCode([=c.jdbcTypeCode])
</#if>
    @Column(name = "[=c.column?j_string]"<#if !c.nullable>, nullable = false</#if><#if c.length??>, length = [=c.length?c]</#if><#if c.precision??>, precision = [=c.precision?c], scale = [=c.scale?c]</#if><#if !c.writable>, insertable = false, updatable = false</#if>)
    private [=c.javaType] [=c.name];
</#list>
<#list x.relations as r>

    @ManyToOne(fetch = FetchType.LAZY<#if !r.nullable>, optional = false</#if>)
    @JoinColumn(name = "[=r.column?j_string]")
    private [=r.targetClass] [=r.name];
</#list>
<#list x.manyToMany as j>

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "[=j.joinTable?j_string]",
            joinColumns = @JoinColumn(name = "[=j.joinColumn?j_string]"),
            inverseJoinColumns = @JoinColumn(name = "[=j.inverseJoinColumn?j_string]"))
    private Set<[=j.targetClass]> [=j.name] = new LinkedHashSet<>();
</#list>
<#if !x.baseEntity>

    public [=x.idJavaType] get[=x.idCap]() {
        return [=x.idName];
    }
<#if x.idStrategy == "ASSIGNED">

    public void set[=x.idCap]([=x.idJavaType] [=x.idName]) {
        this.[=x.idName] = [=x.idName];
    }
</#if>
</#if>
<#list x.columns as c>

    public [=c.javaType] get[=c.cap]() {
        return [=c.name];
    }

    public void set[=c.cap]([=c.javaType] [=c.name]) {
        this.[=c.name] = [=c.name];
    }
</#list>
<#list x.relations as r>

    public [=r.targetClass] get[=r.cap]() {
        return [=r.name];
    }

    public void set[=r.cap]([=r.targetClass] [=r.name]) {
        this.[=r.name] = [=r.name];
    }
</#list>
<#list x.manyToMany as j>

    public Set<[=j.targetClass]> get[=j.cap]() {
        return [=j.name];
    }
</#list>
<#if !x.baseEntity>

    /** Equal when both have the same key (and the key is set), like BaseEntity. */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof [=x.className] that) || org.hibernate.Hibernate.getClass(this) != org.hibernate.Hibernate.getClass(other)) {
            return false;
        }
        return [=x.idName] != null && [=x.idName].equals(that.[=x.idName]);
    }

    @Override
    public int hashCode() {
        return org.hibernate.Hibernate.getClass(this).hashCode();
    }
</#if>
}
