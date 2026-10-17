package io.specforge.mapper;

import io.specforge.config.GeneratorConfig;
import io.specforge.model.Model.*;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import java.util.*;

/** Translates OpenAPI security schemes / requirements into Spring Security rules. */
class SecurityMapper {
    static final String PUBLIC = "permitAll()";

    private final OpenAPI api;
    private final String prefix;
    private final List<Msg> messages;
    final boolean enabled;
    /** PROXY + FORWARD: credentials are passed through to the upstream API, which validates them */
    final boolean proxyForward;
    private final boolean proxy;
    final Map<String, SchemeDef> schemes = new LinkedHashMap<>();

    /** Credentials that let a caller impersonate someone or replay a one-time code are never forwarded by default. */
    private static final java.util.regex.Pattern SENSITIVE = java.util.regex.Pattern.compile("(?i).*(sudo|impersonat|totp|otp).*");

    SecurityMapper(OpenAPI api, GeneratorConfig cfg, List<Msg> messages) {
        this.api = api;
        this.messages = messages;
        this.proxy = cfg.options.isProxy();
        this.proxyForward = proxy && !"SERVICE_ACCOUNT".equalsIgnoreCase(cfg.security.upstreamAuth);
        this.prefix = cfg.security.scopeAuthorityPrefix == null ? "SCOPE_" : cfg.security.scopeAuthorityPrefix;
        Map<String, SecurityScheme> ss = api.getComponents() == null ? null : api.getComponents().getSecuritySchemes();
        boolean declared = ss != null && !ss.isEmpty();
        if (cfg.security.enabled && !declared)
            messages.add(new Msg("INFO", "The spec declares no security schemes, so no Spring Security configuration is generated."));
        this.enabled = cfg.security.enabled && declared;
        // proxy mode needs the schemes for outbound credentials even when no inbound security is generated
        if (enabled || (proxy && declared)) {
            for (Map.Entry<String, SecurityScheme> e : ss.entrySet()) schemes.put(e.getKey(), map(e.getKey(), e.getValue()));
        }
    }

    private SchemeDef map(String name, SecurityScheme s) {
        SchemeDef d = new SchemeDef();
        d.name = name;
        d.javaName = io.specforge.util.Names.javaIdent(name);
        d.description = s.getDescription() == null ? "" : s.getDescription();
        SecurityScheme.Type type = s.getType();
        if (type == null) {
            d.type = "UNSUPPORTED";
            warn("Security scheme '" + name + "' has no type and was ignored.");
            return d;
        }
        switch (type) {
            case APIKEY -> {
                d.type = "APIKEY";
                d.in = s.getIn() == null ? "header" : s.getIn().toString().toLowerCase(Locale.ROOT);
                d.paramName = s.getName() == null ? "X-API-KEY" : s.getName();
            }
            case HTTP -> {
                String sch = s.getScheme() == null ? "" : s.getScheme().toLowerCase(Locale.ROOT);
                if (sch.equals("basic")) d.type = "HTTP_BASIC";
                else if (sch.equals("bearer")) {
                    d.type = "HTTP_BEARER";
                    d.bearerFormat = s.getBearerFormat() == null ? "" : s.getBearerFormat();
                } else {
                    d.type = "UNSUPPORTED";
                    warn("HTTP auth scheme '" + sch + "' (" + name + ") is not supported; endpoints using it are treated as 'authenticated'.");
                }
            }
            case OAUTH2 -> {
                d.type = "OAUTH2";
                OAuthFlows f = s.getFlows();
                if (f != null) {
                    addFlow(d, "implicit", f.getImplicit());
                    addFlow(d, "password", f.getPassword());
                    addFlow(d, "clientCredentials", f.getClientCredentials());
                    addFlow(d, "authorizationCode", f.getAuthorizationCode());
                }
            }
            case OPENIDCONNECT -> {
                d.type = "OPENID_CONNECT";
                d.openIdConnectUrl = s.getOpenIdConnectUrl() == null ? "" : s.getOpenIdConnectUrl();
                d.issuerUri = d.openIdConnectUrl.replace("/.well-known/openid-configuration", "");
            }
            default -> {
                d.type = "UNSUPPORTED";
                warn("Security scheme type " + type + " ('" + name + "') is not supported; endpoints using it are treated as 'authenticated'.");
            }
        }
        return d;
    }

    private void addFlow(SchemeDef d, String name, OAuthFlow f) {
        if (f == null) return;
        d.flows.add(name);
        if (f.getTokenUrl() != null && d.tokenUrl.isEmpty()) d.tokenUrl = f.getTokenUrl();
        if (f.getAuthorizationUrl() != null && d.authorizationUrl.isEmpty()) d.authorizationUrl = f.getAuthorizationUrl();
        if (f.getScopes() != null) d.scopes.putAll(f.getScopes());
    }

    /** Spring Security expression guarding the operation ("permitAll()" when public). */
    String expressionFor(Operation op) {
        if (!enabled) return "";
        List<SecurityRequirement> reqs = op.getSecurity() != null ? op.getSecurity() : api.getSecurity();
        if (reqs == null || reqs.isEmpty()) return PUBLIC;
        LinkedHashSet<String> alts = new LinkedHashSet<>();
        for (SecurityRequirement r : reqs) {
            if (r.isEmpty()) return PUBLIC; // {} = authentication optional
            List<String> terms = new ArrayList<>();
            for (Map.Entry<String, List<String>> e : r.entrySet()) {
                SchemeDef d = schemes.get(e.getKey());
                if (d == null) {
                    warn("Operation references undefined security scheme '" + e.getKey() + "'.");
                    continue;
                }
                d.used = true;
                boolean scoped = d.type.equals("OAUTH2") || d.type.equals("OPENID_CONNECT");
                if (scoped && e.getValue() != null && !e.getValue().isEmpty()) {
                    for (String sc : e.getValue()) terms.add("hasAuthority('" + prefix + sc.replace("\\", "\\\\").replace("'", "\\'") + "')");
                }
            }
            if (terms.isEmpty()) alts.add("isAuthenticated()");
            else alts.add(terms.size() == 1 ? terms.get(0) : "(" + String.join(" and ", terms) + ")");
        }
        return String.join(" or ", alts);
    }

    SecurityDef finish(List<ResourceModel> resources) {
        SecurityDef sd = new SecurityDef();
        sd.present = enabled;
        if (!enabled) {
            if (proxy) proxyCredentials(sd, schemes.values());
            return sd;
        }
        boolean anyUsed = schemes.values().stream().anyMatch(s -> s.used);
        for (SchemeDef d : schemes.values()) {
            if (!anyUsed) d.used = true;
            if (!d.used) continue;
            sd.schemes.add(d);
            switch (d.type) {
                case "HTTP_BASIC" -> sd.usesBasic = true;
                case "HTTP_BEARER", "OAUTH2", "OPENID_CONNECT" -> {
                    sd.usesJwt = true;
                    if (d.type.equals("OPENID_CONNECT") && sd.issuerUri.isEmpty()) sd.issuerUri = d.issuerUri;
                }
                case "APIKEY" -> {
                    sd.usesApiKey = true;
                    sd.apiKeys.add(d);
                }
                default -> { }
            }
            for (String sc : d.scopes.keySet()) if (!sd.allScopes.contains(sc)) sd.allScopes.add(sc);
        }
        if (proxy) proxyCredentials(sd, sd.schemes);
        for (ResourceModel r : resources) {
            for (OperationModel o : r.operations) {
                Rule rule = new Rule();
                rule.method = o.httpMethod;
                rule.pattern = o.fullPath;
                rule.operation = r.name + "." + o.method;
                rule.expression = o.access;
                rule.kind = PUBLIC.equals(o.access) ? "PERMIT_ALL" : "isAuthenticated()".equals(o.access) ? "AUTHENTICATED" : "EXPRESSION";
                sd.rules.add(rule);
            }
        }
        // most specific first: fewer path variables, then longer pattern
        sd.rules.sort(Comparator.<Rule>comparingInt(x -> x.pattern.length() - x.pattern.replace("{", "").length())
            .thenComparing(x -> -x.pattern.length()));
        return sd;
    }

    /** Where callers' credentials live, and which one the app itself would use in SERVICE_ACCOUNT mode. */
    private void proxyCredentials(SecurityDef sd, Collection<SchemeDef> declared) {
        Set<String> seen = new HashSet<>();
        int bestRank = Integer.MAX_VALUE;
        for (SchemeDef d : declared) {
            CredentialDef c = new CredentialDef();
            c.scheme = d.name;
            switch (d.type) {
                case "APIKEY" -> {
                    c.in = d.in;
                    c.name = d.paramName;
                }
                case "HTTP_BASIC", "HTTP_BEARER", "OAUTH2", "OPENID_CONNECT" -> c.name = "Authorization";
                default -> {
                    continue;
                }
            }
            c.excluded = SENSITIVE.matcher(d.name).matches() || SENSITIVE.matcher(c.name).matches();
            if (seen.add(c.in + ":" + c.name.toLowerCase(Locale.ROOT))) sd.credentials.add(c);
            // the app's own credential: prefer headers, since query strings end up in access logs
            int rank = switch (d.type) {
                case "HTTP_BEARER", "OAUTH2", "OPENID_CONNECT" -> 0;
                case "APIKEY" -> c.in.equals("header") ? 1 : 3;
                default -> 2;
            };
            if (!c.excluded && rank < bestRank) {
                bestRank = rank;
                sd.outboundType = switch (d.type) {
                    case "APIKEY" -> "API_KEY";
                    case "HTTP_BASIC" -> "BASIC";
                    default -> "BEARER";
                };
                sd.outboundIn = c.in;
                sd.outboundName = c.name;
            }
        }
        sd.proxyForward = proxyForward;
        if (proxyForward && enabled) {
            // nothing is validated locally: no user store, API keys or JWT decoding in the generated app
            sd.usesBasic = false;
            sd.usesJwt = false;
            sd.usesApiKey = false;
            sd.apiKeys.clear();
        }
        if (proxyForward) for (CredentialDef c : sd.credentials)
            if (c.excluded) messages.add(new Msg("INFO", "Proxy mode does not forward the '" + c.scheme + "' credential (" + c.in + " " + c.name
                + ") by default because it can impersonate users or replay one-time codes; see app.upstream.forward-credentials."));
    }

    private void warn(String m) {
        for (Msg x : messages) if (x.text.equals(m)) return;
        messages.add(new Msg("WARN", m));
    }
}
