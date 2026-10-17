package [=pkg];

<#-- Imports are de-duplicated, sorted and pruned automatically after rendering, so list freely. -->
<#list imports as i>
import [=i];
</#list>
import [=ns].persistence.CascadeType;
import [=ns].persistence.Column;
import [=ns].persistence.ElementCollection;
import [=ns].persistence.Entity;
import [=ns].persistence.EnumType;
import [=ns].persistence.Enumerated;
import [=ns].persistence.GeneratedValue;
import [=ns].persistence.GenerationType;
import [=ns].persistence.Id;
import [=ns].persistence.JoinColumn;
import [=ns].persistence.Lob;
import [=ns].persistence.ManyToMany;
import [=ns].persistence.ManyToOne;
import [=ns].persistence.Table;
import [=ns].persistence.Transient;
<#if jakarta>
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
<#else>
import org.hibernate.annotations.GenericGenerator;
</#if>
import com.fasterxml.jackson.annotation.JsonProperty;
<#if options.validation>
import [=ns].validation.Valid;
import [=ns].validation.constraints.DecimalMax;
import [=ns].validation.constraints.DecimalMin;
import [=ns].validation.constraints.Email;
import [=ns].validation.constraints.Max;
import [=ns].validation.constraints.Min;
import [=ns].validation.constraints.NotNull;
import [=ns].validation.constraints.Pattern;
import [=ns].validation.constraints.Size;
</#if>
<#if options.lombok>
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
</#if>

/**
<#if entity.description?has_content>
 * [=doc(entity.description)]
 *
</#if>
 * [=generatedNote]
 */
@Entity
@Table(name = "[=entity.table]")
<#if options.lombok>
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
</#if>
public class [=entity.className] {
<#list entity.fields as f>

<#if f.description?has_content>
    /** [=doc(f.description)] */
</#if>
<#if f.id>
    @Id
<#if entity.idGeneration == "IDENTITY">
    @GeneratedValue(strategy = GenerationType.IDENTITY)
<#elseif entity.idGeneration == "UUID">
<#if jakarta>
    @GeneratedValue(strategy = GenerationType.UUID)
<#else>
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "uuid2")
</#if>
</#if>
    @Column(name = "[=f.column]")
<#elseif f.persist == "BASIC">
<#if f.lob>
    @Lob
</#if>
<#if f.enumType>
    @Enumerated(EnumType.STRING)
</#if>
    @Column(name = "[=f.column]"<#if f.length??>, length = [=f.length]</#if><#if f.required && !f.readOnly>, nullable = false</#if>)
<#elseif f.persist == "ELEMENT_COLLECTION">
    @ElementCollection
<#if f.enumElement>
    @Enumerated(EnumType.STRING)
</#if>
<#elseif f.persist == "MANY_TO_ONE">
    @ManyToOne(cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinColumn(name = "[=f.column]_id")
<#elseif f.persist == "MANY_TO_MANY">
    @ManyToMany(cascade = {CascadeType.PERSIST, CascadeType.MERGE})
<#elseif f.persist == "JSON">
<#if jakarta>
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "[=f.column]")
<#else>
    // TODO: nested object / map; Hibernate 5 has no built-in JSON column support. Map it with an AttributeConverter.
    @Transient
</#if>
</#if>
<#if f.jsonNameDiffers>
    @JsonProperty("[=f.jsonName?j_string]")
</#if>
<#list f.validations as v>
    [=v]
</#list>
    private [=f.type.java] [=f.name];
</#list>
<#if !options.lombok>
<#list entity.fields as f>

    public [=f.type.java] get[=f.cap]() {
        return [=f.name];
    }

    public void set[=f.cap]([=f.type.java] [=f.name]) {
        this.[=f.name] = [=f.name];
    }
</#list>
</#if>
}
