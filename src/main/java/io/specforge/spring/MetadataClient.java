package io.specforge.spring;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/**
 * The only network client of the Spring sync (SYNC-5): HTTPS GETs to start.spring.io and Maven Central, with
 * timeouts and a size cap. Nothing about the user's project is ever sent.
 */
public class MetadataClient {

    public static final String INITIALIZR = "https://start.spring.io";
    public static final String CENTRAL = "https://repo1.maven.org/maven2";
    private static final Set<String> HOSTS = Set.of("start.spring.io", "repo1.maven.org");
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    /** Thrown when the network or the response is unusable; callers fall back to the bundled snapshot (P7). */
    public static class MetadataUnavailable extends IOException {
        public MetadataUnavailable(String message) {
            super(message);
        }
    }

    public String get(String url, String accept) throws MetadataUnavailable {
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) || !HOSTS.contains(uri.getHost()))
            throw new MetadataUnavailable("Refusing to fetch " + uri.getHost() + ": only start.spring.io and repo1.maven.org are allowed.");
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("Accept", accept)
            .header("User-Agent", "SpecForge").GET().build();
        try {
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = resp.body()) {
                byte[] body = in.readNBytes(MAX_BYTES + 1);
                if (body.length > MAX_BYTES) throw new MetadataUnavailable("Response from " + uri.getHost() + " is larger than " + MAX_BYTES + " bytes.");
                if (resp.statusCode() != 200)
                    throw new MetadataUnavailable(uri.getHost() + " answered HTTP " + resp.statusCode() + " for " + uri.getPath());
                return new String(body, StandardCharsets.UTF_8);
            }
        } catch (MetadataUnavailable e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MetadataUnavailable("Interrupted while contacting " + uri.getHost());
        } catch (IOException e) {
            throw new MetadataUnavailable("Cannot reach " + uri.getHost() + ": " + e.getClass().getSimpleName());
        }
    }
}
