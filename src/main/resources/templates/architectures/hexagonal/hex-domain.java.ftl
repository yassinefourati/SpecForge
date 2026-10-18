package [=pkg];
<#assign e = entity.db>

<#list e.dtoImports as i>
import [=i];
</#list>
<#if e.manyToMany?has_content>
import java.util.LinkedHashSet;
import java.util.Set;
</#if>

/**
 * [=e.className] (table [=e.qualifiedName]): the domain model. Plain Java, so the rules of the business can live
 * here without a framework; related rows are referred to by id.
 *
 * [=generatedNote]
 */
public class [=e.className] {

<#list e.dtoComponents() as f>
    private [=f.javaType] [=f.name]<#if f.javaType?starts_with("Set<")> = new LinkedHashSet<>()</#if>;
</#list>
<#list e.dtoComponents() as f>

    public [=f.javaType] get[=f.cap]() {
        return [=f.name];
    }

    public void set[=f.cap]([=f.javaType] [=f.name]) {
        this.[=f.name] = [=f.name];
    }
</#list>
}
