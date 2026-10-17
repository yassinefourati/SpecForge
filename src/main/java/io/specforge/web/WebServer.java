package io.specforge.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny HTTP server (JDK built-in) serving the bundled UI and the REST API.
 * Binds to the loopback interface only. Because it can write files, it also rejects requests whose Host header is
 * not loopback (DNS rebinding) and API calls that lack the custom X-SpecForge header (cross-site requests cannot
 * send it without a CORS preflight, which this server never approves).
 */
public class WebServer {
    private static final long MAX_BODY = 60L * 1024 * 1024;

    private final HttpServer server;
    private final Api api;
    private final ExecutorService pool = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "specforge-http");
        t.setDaemon(true);
        return t;
    });

    private WebServer(HttpServer server, Api api) {
        this.server = server;
        this.api = api;
    }

    /** port = 0 lets the OS pick a free port. */
    public static WebServer start(int port, Path home, Path templatesOverride) throws IOException {
        HttpServer s;
        try {
            s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 64);
        } catch (BindException e) {
            throw new BindException("Port " + port + " is not available (" + e.getMessage() + "). Pick another port or run without one to auto-select.");
        }
        WebServer w = new WebServer(s, new Api(home, templatesOverride));
        s.createContext("/", w::handle);
        s.setExecutor(w.pool);
        s.start();
        return w;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
        pool.shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!hostOk(ex)) {
                send(ex, Api.error(403, "Forbidden host"));
                return;
            }
            String method = ex.getRequestMethod().toUpperCase();
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                if (!"1".equals(ex.getRequestHeaders().getFirst("X-SpecForge"))) {
                    send(ex, Api.error(403, "Missing X-SpecForge header"));
                    return;
                }
                byte[] body = readBody(ex.getRequestBody());
                Api.Resp r = api.handle(method, path, parseQuery(ex.getRequestURI().getRawQuery()), body);
                send(ex, r);
                return;
            }
            if (!method.equals("GET") && !method.equals("HEAD")) {
                send(ex, Api.error(405, "Method not allowed"));
                return;
            }
            serveStatic(ex, path, method.equals("HEAD"));
        } catch (Api.ApiException e) {
            send(ex, Api.error(e.status, e.getMessage()));
        } catch (Throwable t) {
            t.printStackTrace();
            try {
                send(ex, Api.error(500, t.getClass().getSimpleName() + ": " + t.getMessage()));
            } catch (Throwable ignored) {
                // connection already gone
            }
        } finally {
            ex.close();
        }
    }

    private boolean hostOk(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Host");
        if (h == null) return false;
        String host = h;
        String port = "";
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            if (end < 0) return false;
            host = h.substring(0, end + 1);
            if (h.length() > end + 2) port = h.substring(end + 2);
        } else {
            int c = h.lastIndexOf(':');
            if (c >= 0) {
                host = h.substring(0, c);
                port = h.substring(c + 1);
            }
        }
        boolean loopback = host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
        return loopback && (port.isEmpty() || port.equals(String.valueOf(port())));
    }

    private static byte[] readBody(InputStream in) throws IOException {
        byte[] b = in.readNBytes((int) MAX_BODY + 1);
        if (b.length > MAX_BODY) throw new Api.ApiException(413, "Request body too large.");
        return b;
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> q = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return q;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
            q.put(k, v);
        }
        return q;
    }

    private void serveStatic(HttpExchange ex, String path, boolean head) throws IOException {
        if (path.equals("/")) path = "/index.html";
        if (path.contains("..") || path.contains("\\")) {
            send(ex, Api.error(400, "Bad path"));
            return;
        }
        try (InputStream in = WebServer.class.getResourceAsStream("/web" + path)) {
            if (in == null) {
                send(ex, Api.error(404, "Not found"));
                return;
            }
            byte[] data = in.readAllBytes();
            Api.Resp r = new Api.Resp(200, contentType(path), data);
            r.headers.put("Cache-Control", path.endsWith(".html") ? "no-store" : "no-cache");
            if (head) {
                ex.getResponseHeaders().add("Content-Type", r.contentType);
                ex.sendResponseHeaders(200, -1);
                return;
            }
            send(ex, r);
        }
    }

    private static String contentType(String p) {
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".json")) return "application/json; charset=utf-8";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private static void send(HttpExchange ex, Api.Resp r) throws IOException {
        ex.getResponseHeaders().set("Content-Type", r.contentType);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        ex.getResponseHeaders().set("Content-Security-Policy",
            "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'");
        for (Map.Entry<String, String> h : r.headers.entrySet()) ex.getResponseHeaders().set(h.getKey(), h.getValue());
        ex.sendResponseHeaders(r.status, r.body.length == 0 ? -1 : r.body.length);
        if (r.body.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(r.body);
            }
        }
    }
}
