package [=pkg];

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
<#if security.proxyForward>
import [=ns].servlet.http.Cookie;
import [=ns].servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
</#if>
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

/**
 * Calls the upstream "[=api.title]" API. Every generated service method goes through this class.
 *
 * Base URL, timeouts and credentials come from application.properties (app.upstream.*).
<#if security.proxyForward>
 * Authentication: the caller's own credentials are passed through (app.upstream.forward-credentials) and checked
 * by the upstream API; this app never stores or validates them.
<#else>
 * Authentication: callers authenticate against this app; upstream calls use the app's own credential
 * (app.upstream.credential.*). Callers' credentials are never passed through.
</#if>
 *
 * Redirects are not followed, so credentials are never sent to another host.
 *
 * [=generatedNote]
 */
@Component
public class UpstreamClient {

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^/{}]+)}");

    private final RestClient restClient;
    private final String baseUrl;
<#if security.proxyForward>
    /** {location, name} pairs, location is header, query or cookie */
    private final List<String[]> forwarded = new ArrayList<>();
<#else>
    private final String credentialType;
    private final String credentialIn;
    private final String credentialName;
    private final String credentialValue;
    private final String username;
    private final String password;
</#if>

    public UpstreamClient(RestClient.Builder builder,
            @Value("${app.upstream.base-url}") String baseUrl,
            @Value("${app.upstream.connect-timeout:5s}") Duration connectTimeout,
<#if security.proxyForward>
            @Value("${app.upstream.read-timeout:30s}") Duration readTimeout,
            @Value("${app.upstream.forward-credentials:}") String forwardCredentials) {
<#else>
            @Value("${app.upstream.read-timeout:30s}") Duration readTimeout,
            @Value("${app.upstream.credential.type:NONE}") String credentialType,
            @Value("${app.upstream.credential.in:header}") String credentialIn,
            @Value("${app.upstream.credential.name:Authorization}") String credentialName,
            @Value("${app.upstream.credential.value:}") String credentialValue,
            @Value("${app.upstream.credential.username:}") String username,
            @Value("${app.upstream.credential.password:}") String password) {
</#if>
        String url = baseUrl == null ? "" : baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!url.matches("(?i)https?://[^/?#]+[^?#]*")) {
            throw new IllegalStateException("app.upstream.base-url must be an absolute http(s) URL, got '" + baseUrl + "'");
        }
        this.baseUrl = url;
        HttpClient http = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        this.restClient = builder.requestFactory(factory).build();
<#if security.proxyForward>
        if (forwardCredentials != null) {
            for (String entry : forwardCredentials.split(",")) {
                String[] parts = entry.trim().split(":", 2);
                if (parts.length == 2 && List.of("header", "query", "cookie").contains(parts[0].trim()) && !parts[1].isBlank()) {
                    forwarded.add(new String[] {parts[0].trim(), parts[1].trim()});
                }
            }
        }
<#else>
        this.credentialType = credentialType == null ? "NONE" : credentialType.trim().toUpperCase();
        this.credentialIn = credentialIn == null ? "header" : credentialIn.trim();
        this.credentialName = credentialName == null ? "Authorization" : credentialName.trim();
        this.credentialValue = credentialValue == null ? "" : credentialValue;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
</#if>
    }

    /** Starts a call to the upstream path template, e.g. "/users/{username}". */
    public Call call(HttpMethod method, String pathTemplate) {
        return new Call(method, pathTemplate);
    }

    /** One upstream request; values are encoded here, so callers pass them raw. */
    public final class Call {
        private final HttpMethod method;
        private final String pathTemplate;
        private final Map<String, Object> pathVariables = new HashMap<>();
        private final List<String[]> query = new ArrayList<>();
        private final HttpHeaders headers = new HttpHeaders();
        private final List<String> cookies = new ArrayList<>();
        private final MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        private Object body;
        private String contentType;
        private String accept;

        private Call(HttpMethod method, String pathTemplate) {
            this.method = method;
            this.pathTemplate = pathTemplate;
        }

        public Call pathVariable(String name, Object value) {
            pathVariables.put(name, value);
            return this;
        }

        public Call query(String name, Object value) {
            for (Object v : values(value)) {
                query.add(new String[] {name, String.valueOf(v)});
            }
            return this;
        }

        public Call header(String name, Object value) {
            for (Object v : values(value)) {
                headers.add(name, String.valueOf(v));
            }
            return this;
        }

        public Call cookie(String name, Object value) {
            for (Object v : values(value)) {
                cookies.add(name + "=" + v);
            }
            return this;
        }

        /** A form field or multipart part; uploaded files are streamed through. */
        public Call part(String name, Object value) {
            for (Object v : values(value)) {
                parts.add(name, v instanceof MultipartFile file ? file.getResource() : v);
            }
            return this;
        }

        public Call body(Object body) {
            this.body = body;
            return this;
        }

        public Call contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        public Call accept(String accept) {
            this.accept = accept;
            return this;
        }

        /** Sends the request and discards the response body. Upstream errors surface as RestClientResponseException. */
        public void execute() {
            send().toBodilessEntity();
        }

        /** Sends the request and reads the response body as the given type. */
        public <T> T retrieve(ParameterizedTypeReference<T> type) {
            return send().body(type);
        }

        private RestClient.ResponseSpec send() {
            authenticate();
            RestClient.RequestBodySpec request = restClient.method(method).uri(uri()).headers(h -> h.addAll(headers));
            if (!cookies.isEmpty()) {
                request.header(HttpHeaders.COOKIE, String.join("; ", cookies));
            }
            if (accept != null && !accept.isBlank()) {
                request.header(HttpHeaders.ACCEPT, accept);
            }
            MediaType type = contentType == null || contentType.isBlank() ? null : MediaType.parseMediaType(contentType);
            if (body != null) {
                if (type != null) {
                    request.contentType(type);
                }
                request.body(body);
            } else if (!parts.isEmpty()) {
                if (type != null && "multipart".equalsIgnoreCase(type.getType())) {
                    request.contentType(type);
                    request.body(parts);
                } else {
                    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
                    parts.forEach((k, vs) -> vs.forEach(v -> form.add(k, String.valueOf(v))));
                    request.contentType(type == null ? MediaType.APPLICATION_FORM_URLENCODED : type);
                    request.body(form);
                }
            }
            return request.retrieve();
        }

        private URI uri() {
            StringBuilder path = new StringBuilder();
            Matcher m = PATH_VARIABLE.matcher(pathTemplate);
            int last = 0;
            while (m.find()) {
                path.append(pathTemplate, last, m.start());
                Object v = pathVariables.get(m.group(1));
                String s = v == null ? "" : String.valueOf(v);
                // "." and ".." would let a caller walk to another upstream path
                if (s.isEmpty() || s.equals(".") || s.equals("..")) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid value for path variable {" + m.group(1) + "}");
                }
                path.append(UriUtils.encodePathSegment(s, StandardCharsets.UTF_8));
                last = m.end();
            }
            path.append(pathTemplate.substring(last));
            StringJoiner q = new StringJoiner("&");
            for (String[] p : query) {
                q.add(encodeQuery(p[0]) + "=" + encodeQuery(p[1]));
            }
            return URI.create(baseUrl + path + (q.length() == 0 ? "" : "?" + q));
        }
<#if security.proxyForward>

        /** Copies the configured credentials of the current caller onto the upstream request. */
        private void authenticate() {
            if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
                return;
            }
            HttpServletRequest caller = attributes.getRequest();
            for (String[] c : forwarded) {
                switch (c[0]) {
                    case "header" -> {
                        String v = caller.getHeader(c[1]);
                        if (v != null && headers.getFirst(c[1]) == null) {
                            headers.set(c[1], v);
                        }
                    }
                    case "query" -> {
                        String v = caller.getParameter(c[1]);
                        if (v != null && query.stream().noneMatch(p -> p[0].equals(c[1]))) {
                            query.add(new String[] {c[1], v});
                        }
                    }
                    default -> {
                        if (caller.getCookies() != null) {
                            for (Cookie cookie : caller.getCookies()) {
                                if (cookie.getName().equals(c[1])) {
                                    cookies.add(c[1] + "=" + cookie.getValue());
                                }
                            }
                        }
                    }
                }
            }
        }
<#else>

        /** Adds the app's own upstream credential. */
        private void authenticate() {
            switch (credentialType) {
                case "BASIC" -> {
                    if (!username.isEmpty()) {
                        headers.setBasicAuth(username, password, StandardCharsets.UTF_8);
                    }
                }
                case "BEARER" -> {
                    if (!credentialValue.isEmpty()) {
                        headers.setBearerAuth(credentialValue);
                    }
                }
                case "API_KEY" -> {
                    if (credentialValue.isEmpty()) {
                        return;
                    }
                    switch (credentialIn) {
                        case "query" -> query.add(new String[] {credentialName, credentialValue});
                        case "cookie" -> cookies.add(credentialName + "=" + credentialValue);
                        default -> headers.set(credentialName, credentialValue);
                    }
                }
                default -> {
                    // NONE: the upstream API needs no credential
                }
            }
        }
</#if>
    }

    private static List<?> values(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> c) {
            return c.stream().filter(v -> v != null).toList();
        }
        return List.of(value);
    }

    private static String encodeQuery(String s) {
        // a literal '+' would be read as a space by most servers
        return UriUtils.encodeQueryParam(s, StandardCharsets.UTF_8).replace("+", "%2B");
    }
}
