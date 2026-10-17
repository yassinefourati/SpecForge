package io.specforge.model;

import java.util.*;

/**
 * The language-neutral intermediate model between the OpenAPI document and the templates.
 * All fields are public (templates read them directly); strings are never null, lists never null.
 */
public final class Model {
    private Model() {}

    public static class Msg {
        public String level = "WARN"; // ERROR | WARN | INFO
        public String text = "";

        public Msg() {}

        public Msg(String level, String text) {
            this.level = level;
            this.text = text;
        }
    }

    /** A resolved Java type. */
    public static class TypeRef {
        public String java = "Object";
        /** SCALAR ENUM ENTITY DTO LIST SET MAP OBJECT VOID BINARY FILE */
        public String kind = "OBJECT";
        /** Unit key (schema name) when kind is ENTITY / DTO / ENUM. */
        public String ref = "";
        /** Element type for LIST / SET, value type for MAP. */
        public TypeRef element;
        public Set<String> imports = new TreeSet<>();

        public TypeRef() {}

        public TypeRef(String java, String kind) {
            this.java = java;
            this.kind = kind;
        }

        public static TypeRef scalar(String java, String... imports) {
            TypeRef t = new TypeRef(java, "SCALAR");
            t.imports.addAll(Arrays.asList(imports));
            return t;
        }

        public boolean isCollection() {
            return "LIST".equals(kind) || "SET".equals(kind);
        }

        public boolean isModel() {
            return "ENTITY".equals(kind) || "DTO".equals(kind);
        }

        /** Every project unit key referenced by this type (recursively). */
        public Set<String> allRefs() {
            Set<String> out = new TreeSet<>();
            collectRefs(out);
            return out;
        }

        private void collectRefs(Set<String> out) {
            if (!ref.isEmpty()) out.add(ref);
            if (element != null) element.collectRefs(out);
        }

        public Set<String> allImports() {
            Set<String> out = new TreeSet<>(imports);
            if (element != null) out.addAll(element.allImports());
            return out;
        }

        /** Does this type (or its element) point at a project model class (entity/DTO)? */
        public boolean needsValid() {
            return isModel() || (element != null && element.needsValid() && !"MAP".equals(kind));
        }
    }

    public static class FieldDef {
        public String name = "";
        public String jsonName = "";
        public String cap = "";
        public String description = "";
        public String column = "";
        public TypeRef type = new TypeRef();
        public boolean required, nullable, readOnly, writeOnly, deprecated, id, synthetic, jsonNameDiffers;
        /** BASIC | ELEMENT_COLLECTION | MANY_TO_ONE | MANY_TO_MANY | JSON | TRANSIENT (entities only) */
        public String persist = "BASIC";
        public boolean lob;
        public boolean enumType;
        public boolean enumElement;
        public Integer length;
        public List<String> validations = new ArrayList<>();
    }

    public static class Finder {
        public String method = "";
        public String field = "";
        public String javaType = "";
        public String cap = "";
    }

    /** An entity or a plain DTO. */
    public static class ClassDef {
        public String key = "";
        public String className = "";
        /** ENTITY | DTO */
        public String kind = "DTO";
        public String description = "";
        public String table = "";
        /** Why the schema was classified the way it was (shown in the UI). */
        public String reason = "";
        public boolean synthetic;
        public List<FieldDef> fields = new ArrayList<>();
        public FieldDef idField;
        /** IDENTITY | UUID | NONE */
        public String idGeneration = "NONE";
        public List<Finder> finders = new ArrayList<>();
        // filled in by the generator
        public String pkg = "";
        public String fqn = "";
        public String repositoryName = "";
        public String repositoryFqn = "";
        /** Database flow only: the effective table model the db-*.ftl templates render (null in the spec flow). */
        public DbModel.DbEntity db;

        public List<TypeRef> allTypes() {
            List<TypeRef> l = new ArrayList<>();
            for (FieldDef f : fields) l.add(f.type);
            return l;
        }
    }

    public static class EnumConst {
        public String name = "";
        /** Java source literal for the constant's value, e.g. "available" (quoted) or 3 */
        public String literal = "";
        public String raw = "";
    }

    public static class EnumDef {
        public String key = "";
        public String className = "";
        public String description = "";
        /** String | Integer | Long | Double */
        public String valueType = "String";
        public boolean synthetic;
        public List<EnumConst> values = new ArrayList<>();
        public String pkg = "";
        public String fqn = "";
    }

    public static class ParamDef {
        public String name = "";
        public String javaName = "";
        /** path | query | header | cookie | body | form | part */
        public String in = "query";
        public String description = "";
        public String defaultValue = "";
        public String allowedValues = "";
        public TypeRef type = new TypeRef();
        public boolean required;
        public boolean file;
        /** The synthetic Content-Type header of a method that accepts several media types */
        public boolean contentTypeSelector;
        public List<String> validations = new ArrayList<>();
    }

    public static class ResponseDoc {
        public String code = "";
        public String description = "";
    }

    public static class OperationModel {
        public String operationId = "";
        public String method = "";
        public String httpMethod = "GET";
        /** e.g. GetMapping */
        public String mapping = "GetMapping";
        /** RequestMethod constant (HEAD, OPTIONS) when mapping is RequestMapping; "" otherwise */
        public String requestMethod = "";
        /** Path relative to the controller's base path, e.g. /pets/{petId} */
        public String path = "";
        /** basePath + path */
        public String fullPath = "";
        public String summary = "";
        public String description = "";
        public boolean deprecated;
        public List<ParamDef> params = new ArrayList<>();
        public ParamDef body;
        public TypeRef returnType = new TypeRef("void", "VOID");
        public boolean returnsVoid = true;
        public int successStatus = 200;
        public List<String> consumes = new ArrayList<>();
        public List<String> produces = new ArrayList<>();
        public List<ResponseDoc> responses = new ArrayList<>();
        /** LIST | GET | CREATE | UPDATE | DELETE | CUSTOM */
        public String kind = "CUSTOM";
        /** ID | PROPERTY | "" */
        public String lookupKind = "";
        public String lookupParam = "";
        /** expression converting the path variable to the id/property type */
        public String lookupExpr = "";
        public String finderMethod = "";
        /** Spring EL for @PreAuthorize / URL rules; "permitAll()" when public; "" when security is off */
        public String access = "";
        public boolean publicAccess = true;
        public String warning = "";

        public String callArgs() {
            StringJoiner j = new StringJoiner(", ");
            for (ParamDef p : params) j.add(p.javaName);
            return j.toString();
        }

        /** Java expression for the request media type: the Content-Type selector parameter, else the declared type, else "". */
        public String contentTypeExpr() {
            for (ParamDef p : params) if (p.contentTypeSelector) return p.javaName;
            return consumes.isEmpty() ? "" : "\"" + consumes.get(0) + "\"";
        }

        public String signature() {
            StringJoiner j = new StringJoiner(", ");
            for (ParamDef p : params) j.add(p.type.java + " " + p.javaName);
            return j.toString();
        }
    }

    public static class ResourceModel {
        public String name = "";
        public String tag = "";
        public String description = "";
        public String entityKey = "";
        public ClassDef entity;
        public String basePath = "";
        public List<OperationModel> operations = new ArrayList<>();
        // filled in by the generator
        public String controllerName = "";
        public String controllerFqn = "";
        public String serviceName = "";
        public String serviceFqn = "";
        public String serviceVar = "";
        public String repositoryName = "";
        public String repositoryFqn = "";
        public String entityFqn = "";
        public String entityClass = "";
        public String idType = "";

        public List<TypeRef> allTypes() {
            List<TypeRef> l = new ArrayList<>();
            for (OperationModel o : operations) {
                for (ParamDef p : o.params) l.add(p.type);
                l.add(o.returnType);
            }
            return l;
        }
    }

    public static class SchemeDef {
        public String name = "";
        public String javaName = "";
        /** APIKEY | HTTP_BASIC | HTTP_BEARER | OAUTH2 | OPENID_CONNECT | UNSUPPORTED */
        public String type = "";
        public String description = "";
        /** header | query | cookie (APIKEY) */
        public String in = "";
        public String paramName = "";
        public String bearerFormat = "";
        public String openIdConnectUrl = "";
        public String issuerUri = "";
        public List<String> flows = new ArrayList<>();
        public String tokenUrl = "";
        public String authorizationUrl = "";
        public Map<String, String> scopes = new LinkedHashMap<>();
        public boolean used;
    }

    public static class Rule {
        public String method = "";
        public String pattern = "";
        public String operation = "";
        /** PERMIT_ALL | AUTHENTICATED | EXPRESSION */
        public String kind = "AUTHENTICATED";
        public String expression = "";
    }

    public static class SecurityDef {
        public boolean present;
        public List<SchemeDef> schemes = new ArrayList<>();
        public boolean usesBasic, usesJwt, usesApiKey;
        public List<SchemeDef> apiKeys = new ArrayList<>();
        public List<Rule> rules = new ArrayList<>();
        public String jwkSetUri = "http://localhost:9000/oauth2/jwks";
        public String issuerUri = "";
        public List<String> allScopes = new ArrayList<>();
        /** PROXY + FORWARD: credentials are only checked for presence here and passed through to the upstream API */
        public boolean proxyForward;
        /** PROXY mode: where callers' credentials live (from the spec's schemes); excluded ones are not forwarded by default */
        public List<CredentialDef> credentials = new ArrayList<>();
        /** PROXY + SERVICE_ACCOUNT: suggested outbound credential (BASIC | BEARER | API_KEY), "" when the spec has none */
        public String outboundType = "";
        public String outboundIn = "header";
        public String outboundName = "Authorization";

        /** "header:Authorization,query:access_token" for the forwarded (not excluded) credentials */
        public String forwardList() {
            StringJoiner j = new StringJoiner(",");
            for (CredentialDef c : credentials) if (!c.excluded) j.add(c.in + ":" + c.name);
            return j.toString();
        }

        public String excludedList() {
            StringJoiner j = new StringJoiner(",");
            for (CredentialDef c : credentials) if (c.excluded) j.add(c.in + ":" + c.name);
            return j.toString();
        }
    }

    public static class CredentialDef {
        /** header | query | cookie */
        public String in = "header";
        public String name = "";
        public String scheme = "";
        /** impersonation / one-time-code credentials (sudo, OTP) are not forwarded unless you opt in */
        public boolean excluded;
    }

    public static class ApiModel {
        public String title = "API";
        public String version = "1.0.0";
        /** Maven-safe version */
        public String projectVersion = "0.0.1-SNAPSHOT";
        public String description = "";
        public String openapiVersion = "";
        public String basePath = "";
        public String artifactId = "api";
        public String applicationClass = "Application";
        /** Where this model came from: an OpenAPI spec or a database (STU-12). */
        public DbModel.SourceFacts source = new DbModel.SourceFacts();
        /** PROXY mode: base URL of the upstream API, including its base path */
        public String upstreamUrl = "";
        public List<ResourceModel> resources = new ArrayList<>();
        public List<ClassDef> entities = new ArrayList<>();
        public List<ClassDef> dtos = new ArrayList<>();
        public List<EnumDef> enums = new ArrayList<>();
        /** database flow: use-case controllers composed from several entities (OPS-3) */
        public List<DbModel.DbUseCase> useCases = new ArrayList<>();
        public SecurityDef security = new SecurityDef();
        public List<Msg> messages = new ArrayList<>();
    }
}
