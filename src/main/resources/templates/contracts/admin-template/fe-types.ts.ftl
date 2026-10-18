<#assign x = module.of(entity.db)>
// [=generatedNote]

/** [=x.singular], as the API returns it (/api/[=x.path]). */
export interface [=x.className] {
<#list x.tsResponse as p>
  [=p.name]: <#if p.name == x.idName>[=x.idTs]<#else>[=p.type]</#if>
</#list>
}

/** What the API accepts to create or change a [=x.singularLower]. */
export interface [=x.className]Input {
<#list x.tsRequest as p>
  [=p.name]: [=p.type]
</#list>
}
