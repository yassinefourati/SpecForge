SpecForge templates
===================

Edit any file in this folder to change what SpecForge generates. Point SpecForge at the folder with
Settings -> Templates -> "Template folder" (or --templates=DIR, or keep it at ~/.specforge/templates, which
is picked up automatically). Files here override the ones bundled in the JAR, file by file; anything you
do not copy keeps using the bundled version.

Syntax (FreeMarker 2.3, https://freemarker.apache.org/docs/)
------------------------------------------------------------
Directives use angle brackets:        <#if options.lombok> ... </#if>   <#list entity.fields as f> ... </#list>
Interpolation uses SQUARE brackets:   [=entity.className]   [=f.name?j_string]   [=doc(f.description)]
The ${...} syntax is NOT interpolation here, so Spring / Maven placeholders such as ${app.value} pass through.
Generated Java is post-processed: imports are de-duplicated, sorted and unused ones removed, so list them freely.

manifest.json
-------------
One entry per generated file type:
  id         unique name
  layer      entities | models | enums | repositories | services | controllers | security | exceptions | project
             (decides package, folder and whether the layer is enabled in the UI)
  scope      ENTITY (once per entity) | DTO | ENUM | RESOURCE (once per controller/service group) | GLOBAL (once)
             | USECASE (once per use-case controller, database flow) | USECASE_RECORD (once per use-case record)
  kind       JAVA (default; <sourceRoot>/<package>/<fileName>) | RESOURCE (<resourceRoot>/<fileName>) | ROOT (<project>/<fileName>)
  template   the .ftl file
  fileName   a template string, e.g. [=entity.className].java
  when       optional boolean expression, e.g. security.present && security.usesApiKey; for per-item scopes it is
             evaluated per item, so it may use entity / item / useCase / record (e.g. entity.db.hasController())
  flow       SPEC | DATABASE | ANY: which source the entry renders for. Missing: per-item scopes render for SPEC,
             GLOBAL entries for ANY. The db-*.ftl entries are DATABASE entries.
To add a file type (for example a DTO mapper per entity), add an entry and a template; copy manifest.json here first.

Variables available in every template
-------------------------------------
  api        title, version, description, basePath, applicationClass, artifactId, projectVersion,
             upstreamUrl (proxy mode), resources, entities, dtos, enums, security
  config     the full settings object;   options = config.options (lombok, validation, namespace, serviceMode,
             proxy (true in PROXY service mode), upstreamUrl, ...)
  secCfg     security settings (accessRules, scopeAuthorityPrefix, upstreamAuth, databaseAuth (BASIC | JWT),
             databaseJwt(), jwtRoles (SCOPES | KEYCLOAK), keycloakRoles(), readRole, writeRole)
  Architectures   templates/architectures/<name>/manifest.json lays an architecture over these templates: its
             "remove" list drops entries by id, its "templates" are added; an entry's "package" (e.g. "domain",
             "portOut") picks a per-table package other than its layer's (hexagonal: domain, portOut)
  pkgOf(cls, layer)  the package of a table's class in a layer ("entities", "models", "repositories", "services",
             "controllers"), e.g. pkgOf(r.targetClass, "entities"); per-entity templates also get their own table's
             packages as entityPackage, modelPackage, repositoryPackage, servicePackage, controllerPackage (ARCH-1)
  sharedModelPackage / sharedServicePackage  where PageResponse / ValueConversions live, whatever the layout
  checks     what the project gets for migrations and tests: flyway (NONE | BASELINE | SCHEMA, after fallbacks), ddl
             (V1__baseline.sql), flywayModule, flywaySchemas, controllerTests, applicationTest, inMemory,
             containerClass, containerImport, containerImage, genericContainer(), webMvcTestImport,
             autoConfigureMockMvcImport
  lists      database-flow list endpoints: textSearch (NONE | Q), q(), rangeFilters, unknownSort (REJECT | IGNORE),
             lenientSort()
  style      code style (Settings → Code style): errorFormat (LEGACY_MAP | PROBLEM_DETAIL), problemDetail(),
             dtoSplit (SINGLE | REQUEST_RESPONSE), requestResponse()
  security   api.security: present, usesBasic, usesJwt, usesApiKey, schemes, apiKeys, rules, issuerUri, jwkSetUri,
             proxyForward, credentials (in, name, scheme, excluded), forwardList(), excludedList(),
             outboundType, outboundIn, outboundName
  jakarta    true for jakarta.*, false for javax.*      ns   "jakarta" or "javax"
  pkg        package of the file being generated        imports   JDK / project imports needed by the types used
  doc(text)  makes free text safe inside a Javadoc comment
  source     api.source: kind (SPEC | DATABASE), engine, engineLabel, schemas, jdbcUrl, initializrId, connectionName
  build      resolved build: dependencies (groupId, artifactId, version, scope, comment, hasComment()), lombok,
             bootMajor, warnings (synced with start.spring.io metadata)
  bootMajor  3 or 4 (Spring Boot major of the chosen version)
  generatedNote, basePackage, entityPackage, repositoryPackage, servicePackage, controllerPackage,
  securityPackage, exceptionPackage, modelPackage, enumPackage

Per scope
---------
  ENTITY     entity    className, table, description, fields, idField, idGeneration, finders, repositoryName, fqn
  DTO        dto       className, description, fields
  ENUM       enumDef   className, description, valueType, values (name, literal, raw)
  RESOURCE   resource  name, controllerName, serviceName, serviceVar, basePath, entity (may be missing), entityClass,
                       entityFqn, repositoryName, repositoryFqn, serviceFqn, operations
  (all scopes also expose the element as `item`)

Database flow (flow DATABASE; ENTITY scope): entity.db (also item.db) is the table's mapping
  db         className, varName, table, schema, qualifiedName, path, comment, readOnly, dtoName, mapperName,
             serviceName, controllerName, repositoryName, entityImports, dtoImports, uniqueColumns,
             filterable, sortable, searchable (searched by q; empty unless lists.q()), rangeable (…From / …To;
             empty unless lists.rangeFilters), ops / op("create"|"read"|"list"|"update"|"patch"|"delete"),
             fields, dtoFields(), dtoComponents(), relations, manyToMany, id,
             split (true in the Request and Response style), requestName (the record accepted on create /
             update; equals dtoName unless split), requestComponents() (its components: an assigned key, writable
             fields, relation ids, many-to-many id sets; equals dtoComponents() unless split)
  db.id      kind (SINGLE | COMPOSITE), strategy (IDENTITY | SEQUENCE | UUID | ASSIGNED), field, keyFields,
             sequenceSchema, sequenceName, allocationSize, javaType, composite(), generated()
  DbField    column, name, cap, javaType, jdbcTypeCode, nullable, length, precision, scale, writable, dbDefault,
             validations, relation
  relations  name, cap, targetClass, targetIdName, targetIdCap, targetIdType, column, referencedColumn, nullable,
             mapsId, dtoField, inverse, inverseName
  more       serviceOp("create"…) (offered by the service, also for use cases), hasController(), controller,
             dtoComponents() (record components in order), inverses (name, cap, sourceClass, mappedBy),
             childQueries (relations the repository finds children by: findAllBy<Relation>_<TargetId>)

Use cases (scope USECASE): useCase (also item)
  useCase    name, path, controllerName, serviceName, operations, records, services, mappers, repositories,
             imports, has("AGGREGATE_CREATE" | "AGGREGATE_UPDATE" | "WITH_CHILDREN" | "CHILDREN" | "ENTITY")
  operation  kind, operation (ENTITY), method, path (with its {variables}), httpMethod(), itemPath(), entity,
             child, link (the child's relation to the parent), linkComponent, requestRecord, responseRecord,
             itemRecord, parentProperty, childrenProperty, childQuery
Records (scope USECASE_RECORD): record (also item): name, doc, components (name, javaType, validations), imports
  manyToMany name, cap, targetClass, joinSchema, joinTable, joinColumn, inverseJoinColumn, targetIdType,
             targetIdCap, dtoName, dtoCap

Field (entity.fields / dto.fields): name, jsonName, jsonNameDiffers, cap, description, column, type (java, kind),
  required, nullable, readOnly, id, persist (BASIC | ELEMENT_COLLECTION | MANY_TO_ONE | MANY_TO_MANY | JSON),
  lob, enumType, enumElement, length, validations (ready-made annotation strings)
Operation (resource.operations): method, httpMethod, mapping, requestMethod (HEAD/OPTIONS with RequestMapping, else ""),
  path, fullPath, summary, description, deprecated,
  params (name, javaName, in, type, required, defaultValue, validations, description, contentTypeSelector), body, returnType, returnsVoid,
  successStatus, consumes, produces, responses, kind (LIST | GET | CREATE | UPDATE | DELETE | CUSTOM),
  lookupKind, lookupParam, lookupExpr, finderMethod, access (Spring EL), publicAccess,
  signature(), callArgs(), contentTypeExpr()
