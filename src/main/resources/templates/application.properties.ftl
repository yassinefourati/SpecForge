# [=generatedNote]
spring.application.name=[=api.artifactId]
<#if api.basePath?has_content>
# the spec's base path ([=api.basePath]) is applied by the controllers' @RequestMapping
</#if>

<#if source.kind == "DATABASE">
# ---- database: [=source.engineLabel] (credentials come only from the environment) ----
spring.datasource.url=${DB_URL:[=source.jdbcUrl]}
spring.datasource.username=${DB_USERNAME}
spring.datasource.password=${DB_PASSWORD}
# the database is the source of truth: Hibernate checks the mapping and never changes the schema
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
<#if source.engine == "ORACLE">
# names that need quotes are quoted in the mappings; SQL keywords are quoted automatically
spring.jpa.properties.hibernate.auto_quote_keyword=true
<#else>
# names are used exactly as the database spells them
spring.jpa.properties.hibernate.globally_quoted_identifiers=true
</#if>
<#if source.engine == "SQLITE">
spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect
# SQLite checks foreign keys only when asked to, per connection
spring.datasource.hikari.connection-init-sql=PRAGMA foreign_keys = ON
</#if>
spring.data.web.pageable.max-page-size=100
<#elseif options.proxy>
# ---- upstream API (proxy mode: every service call is forwarded there, no database) ----
app.upstream.base-url=${UPSTREAM_BASE_URL:[=api.upstreamUrl]}
app.upstream.connect-timeout=5s
app.upstream.read-timeout=30s
<#if security.proxyForward>
# callers' credentials passed through to the upstream API, as location:name (header | query | cookie), comma separated
app.upstream.forward-credentials=[=security.forwardList()]
<#if security.excludedList()?has_content>
# not forwarded by default because they can impersonate users or replay one-time codes; add them above only if you need them:
# [=security.excludedList()]
</#if>
<#else>
# the app's own credential for the upstream API; callers' credentials are never passed through
# type: NONE | BASIC | BEARER | API_KEY (API_KEY uses in = header | query | cookie and name)
app.upstream.credential.type=[=(security.outboundType?has_content)?then(security.outboundType, "NONE")]
app.upstream.credential.in=[=security.outboundIn]
app.upstream.credential.name=[=security.outboundName]
app.upstream.credential.value=${UPSTREAM_TOKEN:}
app.upstream.credential.username=${UPSTREAM_USERNAME:}
app.upstream.credential.password=${UPSTREAM_PASSWORD:}
</#if>
<#elseif options.database == "POSTGRES">
spring.datasource.url=jdbc:postgresql://localhost:5432/[=api.artifactId?replace("-", "_")]
spring.datasource.username=postgres
spring.datasource.password=postgres
<#else>
spring.datasource.url=jdbc:h2:mem:[=api.artifactId?replace("-", "_")];DB_CLOSE_DELAY=-1
spring.datasource.username=sa
spring.datasource.password=
spring.h2.console.enabled=true
</#if>
<#if !options.proxy && source.kind != "DATABASE">

spring.jpa.hibernate.ddl-auto=update
# keeps the Hibernate session open while entities are serialised, so lazy collections can be written as JSON
spring.jpa.open-in-view=true
# quote every identifier so spec names such as "order" or "user" never collide with SQL keywords
spring.jpa.properties.hibernate.globally_quoted_identifiers=true
</#if>
<#if security.present && (security.usesBasic || security.usesJwt || security.usesApiKey)>

# ---- security ----
<#if security.usesJwt>
<#if security.issuerUri?has_content>
spring.security.oauth2.resourceserver.jwt.issuer-uri=[=security.issuerUri]
<#else>
# TODO: point this at your authorization server's JWK set
spring.security.oauth2.resourceserver.jwt.jwk-set-uri=[=security.jwkSetUri]
</#if>
<#list security.schemes as s>
<#if s.type == "OAUTH2" && s.tokenUrl?has_content>
# scheme [=s.name]: token URL [=s.tokenUrl]<#if s.authorizationUrl?has_content>, authorization URL [=s.authorizationUrl]</#if>
</#if>
</#list>
</#if>
<#if security.usesBasic>
# development credentials for HTTP basic auth - CHANGE THESE
app.security.user.name=user
app.security.user.password=changeme
</#if>
<#if security.usesApiKey>
# comma-separated list of accepted API keys - CHANGE THESE
app.security.api-keys=dev-key-change-me
</#if>
</#if>
