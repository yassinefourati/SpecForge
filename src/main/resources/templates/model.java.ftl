package [=pkg];

<#list imports as i>
import [=i];
</#list>
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
<#if dto.description?has_content>
 * [=doc(dto.description)]
 *
</#if>
 * [=generatedNote]
 */
<#if options.lombok>
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
</#if>
public class [=dto.className] {
<#list dto.fields as f>

<#if f.description?has_content>
    /** [=doc(f.description)] */
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
<#list dto.fields as f>

    public [=f.type.java] get[=f.cap]() {
        return [=f.name];
    }

    public void set[=f.cap]([=f.type.java] [=f.name]) {
        this.[=f.name] = [=f.name];
    }
</#list>
</#if>
}
