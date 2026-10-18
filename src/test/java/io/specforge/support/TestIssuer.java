package io.specforge.support;

import com.sun.net.httpserver.HttpServer;
import io.specforge.util.Json;

import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A stand-in for a Keycloak realm in tests: an RSA key, its JWK set served over HTTP, and RS256 access tokens with
 * Keycloak's claims (iss, aud, realm_access.roles). Enough for a resource server to verify tokens exactly as it
 * would against Keycloak, without the container.
 */
public final class TestIssuer implements AutoCloseable {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final KeyPair keys;
    private final HttpServer server;
    public final String issuer;
    public final String jwkSetUri;

    public TestIssuer() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        keys = g.generateKeyPair();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        RSAPublicKey pub = (RSAPublicKey) keys.getPublic();
        byte[] jwks = Json.write(Map.of("keys", List.of(Map.of("kty", "RSA", "kid", "test", "use", "sig", "alg", "RS256",
            "n", b64(pub.getModulus()), "e", b64(pub.getPublicExponent()))))).getBytes(StandardCharsets.UTF_8);
        server.createContext("/jwks", ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, jwks.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(jwks);
            }
        });
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort();
        issuer = base + "/realms/test";
        jwkSetUri = base + "/jwks";
    }

    /** "Bearer ..." for a token of this issuer, valid for 5 minutes, with the given audience and realm roles. */
    public String bearer(String audience, List<String> roles) throws Exception {
        return bearer(issuer, audience, roles, Instant.now().plusSeconds(300));
    }

    public String bearer(String iss, String audience, List<String> roles, Instant expires) throws Exception {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", iss);
        claims.put("sub", "test-user");
        claims.put("aud", audience);
        claims.put("iat", Instant.now().getEpochSecond() - 5);
        claims.put("exp", expires.getEpochSecond());
        claims.put("realm_access", Map.of("roles", roles));
        String header = B64.encodeToString(Json.write(Map.of("alg", "RS256", "kid", "test", "typ", "JWT")).getBytes(StandardCharsets.UTF_8));
        String payload = B64.encodeToString(Json.write(claims).getBytes(StandardCharsets.UTF_8));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(keys.getPrivate());
        sig.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
        return "Bearer " + header + "." + payload + "." + B64.encodeToString(sig.sign());
    }

    private static String b64(BigInteger v) {
        byte[] b = v.toByteArray();
        if (b.length > 1 && b[0] == 0) b = java.util.Arrays.copyOfRange(b, 1, b.length);
        return B64.encodeToString(b);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
