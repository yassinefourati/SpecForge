package io.specforge.spec;

import com.fasterxml.jackson.databind.JsonNode;
import io.specforge.model.Model.Msg;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Loads and parses a Swagger 2.0 / OpenAPI 3.x document from text or URL. */
public class SpecLoader {

    public static final int MAX_BYTES = 25 * 1024 * 1024;

    /** Thrown for problems the user must fix; message is user-facing. */
    public static class SpecException extends Exception {
        public final List<Msg> messages;

        public SpecException(String message, List<Msg> messages) {
            super(message);
            this.messages = messages;
        }
    }

    public static class Loaded {
        public String id;
        public String source;
        public String text;
        public String format;       // JSON | YAML
        public String declared;     // e.g. "Swagger 2.0 (converted to OpenAPI 3)" / "OpenAPI 3.0.3"
        public OpenAPI api;
        public List<Msg> messages = new ArrayList<>();
    }

    private static final Pattern SWAGGER2 = Pattern.compile("(?m)^\\s*\"?swagger\"?\\s*:\\s*\"?2");

    public Loaded fromText(String text, String sourceName) throws SpecException {
        if (text == null || text.isBlank()) throw single("The specification is empty. Paste, upload or link a Swagger/OpenAPI document.");
        if (text.length() > MAX_BYTES) throw single("The specification is larger than " + (MAX_BYTES / 1024 / 1024) + " MB.");
        if (looksLikeHtml(text))
            throw single("This looks like an HTML page, not a spec. If this is a Swagger UI page, link the raw document instead "
                + "(e.g. /v3/api-docs, /swagger.json or /openapi.yaml).");
        SwaggerParseResult r = new OpenAPIParser().readContents(text, null, options());
        return finish(r, text, sourceName);
    }

    public Loaded fromUrl(String url) throws SpecException {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            throw single("Not a valid URL: " + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) throw single("Only http(s) URLs are supported.");
        String body;
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build();
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json, application/yaml, text/yaml, */*").GET().build();
            HttpResponse<byte[]> resp = c.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) throw single("The server answered HTTP " + resp.statusCode() + " for " + url);
            if (resp.body().length > MAX_BYTES) throw single("The document is larger than " + (MAX_BYTES / 1024 / 1024) + " MB.");
            body = new String(resp.body(), StandardCharsets.UTF_8);
        } catch (SpecException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw single("Interrupted while downloading " + url);
        } catch (Exception e) {
            throw single("Could not download " + url + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (body.isBlank()) throw single("The URL returned an empty body.");
        if (looksLikeHtml(body))
            throw single("The URL returned an HTML page (probably Swagger UI). Use the raw spec URL, "
                + "e.g. /v3/api-docs, /swagger.json or /openapi.yaml.");
        // readLocation lets relative external $refs resolve against the URL
        SwaggerParseResult r;
        try {
            r = new OpenAPIParser().readLocation(url.trim(), null, options());
        } catch (Exception e) {
            r = null;
        }
        if (r == null || r.getOpenAPI() == null) r = new OpenAPIParser().readContents(body, null, options());
        return finish(r, body, url.trim());
    }

    private Loaded finish(SwaggerParseResult r, String text, String source) throws SpecException {
        List<Msg> msgs = new ArrayList<>();
        boolean fatal = r == null || r.getOpenAPI() == null;
        if (r != null && r.getMessages() != null)
            for (String m : r.getMessages()) msgs.add(new Msg(fatal ? "ERROR" : "WARN", "Parser: " + m));
        if (fatal) {
            if (msgs.isEmpty()) msgs.add(new Msg("ERROR", "The document could not be parsed as Swagger 2.0 or OpenAPI 3.x."));
            throw new SpecException("The specification is invalid and cannot be used.", msgs);
        }
        Loaded l = new Loaded();
        l.api = r.getOpenAPI();
        l.messages = msgs;
        l.text = text;
        l.source = source;
        l.format = text.stripLeading().startsWith("{") ? "JSON" : "YAML";
        JsonNode raw = readTree(text, l.format);
        // the "swagger" key may come last (e.g. Kubernetes' swagger.json), so prefer the parsed document over a text scan
        boolean sw2 = raw != null ? raw.path("swagger").asText("").startsWith("2")
            : SWAGGER2.matcher(text.length() > 4000 ? text.substring(0, 4000) : text).find();
        String v = l.api.getOpenapi();
        l.declared = sw2 ? "Swagger 2.0 (converted to OpenAPI 3)" : "OpenAPI " + (v == null ? "3.x" : v);
        if (sw2 && raw != null) restoreSwagger2PathParams(raw, l);
        l.id = sha1(source + "\n" + text);
        return l;
    }

    /**
     * The Swagger 2 converter drops a path parameter when a query parameter has the same name (Kubernetes declares
     * both a path and a query "path" on its proxy endpoints). Re-adds such path parameters from the raw document.
     */
    private static void restoreSwagger2PathParams(JsonNode root, Loaded l) {
        if (l.api.getPaths() == null) return;
        JsonNode globals = root.path("parameters");
        int restored = 0;
        for (Iterator<Map.Entry<String, JsonNode>> it = root.path("paths").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            PathItem item = l.api.getPaths().get(e.getKey());
            if (item == null) continue;
            Map<String, JsonNode> shared = pathParams(e.getValue().path("parameters"), globals, new LinkedHashMap<>());
            for (Map.Entry<PathItem.HttpMethod, Operation> o : item.readOperationsMap().entrySet()) {
                JsonNode rawOp = e.getValue().path(o.getKey().name().toLowerCase(Locale.ROOT));
                Map<String, JsonNode> declared = pathParams(rawOp.path("parameters"), globals, new LinkedHashMap<>(shared));
                for (Map.Entry<String, JsonNode> p : declared.entrySet()) {
                    if (hasPathParam(item.getParameters(), p.getKey()) || hasPathParam(o.getValue().getParameters(), p.getKey())) continue;
                    o.getValue().addParametersItem(toPathParameter(p.getKey(), p.getValue()));
                    restored++;
                }
            }
        }
        if (restored > 0)
            l.messages.add(new Msg("INFO", "Restored " + restored + " path parameter(s) lost in the Swagger 2 conversion "
                + "because a query parameter has the same name."));
    }

    private static JsonNode readTree(String text, String format) {
        try {
            JsonNode n = (format.equals("JSON") ? Json.mapper() : Yaml.mapper()).readTree(text);
            return n != null && n.isObject() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, JsonNode> pathParams(JsonNode list, JsonNode globals, Map<String, JsonNode> into) {
        for (JsonNode p : list) {
            String ref = p.path("$ref").asText("");
            if (ref.startsWith("#/parameters/")) p = globals.path(ref.substring("#/parameters/".length()));
            if ("path".equals(p.path("in").asText()) && p.hasNonNull("name")) into.put(p.get("name").asText(), p);
        }
        return into;
    }

    private static boolean hasPathParam(List<Parameter> params, String name) {
        if (params == null) return false;
        for (Parameter p : params) if (name.equals(p.getName()) && "path".equals(p.getIn())) return true;
        return false;
    }

    private static Parameter toPathParameter(String name, JsonNode raw) {
        Schema<Object> s = new Schema<>();
        String type = raw.path("type").asText("string");
        s.setType(Set.of("integer", "number", "boolean").contains(type) ? type : "string");
        if (raw.hasNonNull("format")) s.setFormat(raw.get("format").asText());
        PathParameter p = new PathParameter();
        p.setName(name);
        p.setRequired(true);
        p.setSchema(s);
        if (raw.hasNonNull("description")) p.setDescription(raw.get("description").asText());
        return p;
    }

    private static ParseOptions options() {
        ParseOptions o = new ParseOptions();
        o.setResolve(true);          // resolve external $refs, keep internal ones (we want named schemas)
        o.setResolveFully(false);
        o.setResolveCombinators(false);
        o.setFlatten(false);
        return o;
    }

    private static boolean looksLikeHtml(String text) {
        String head = text.stripLeading();
        head = head.length() > 300 ? head.substring(0, 300).toLowerCase() : head.toLowerCase();
        return head.startsWith("<!doctype html") || head.startsWith("<html") || head.contains("<head>");
    }

    private static SpecException single(String m) {
        List<Msg> l = new ArrayList<>();
        l.add(new Msg("ERROR", m));
        return new SpecException(m, l);
    }

    private static String sha1(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
