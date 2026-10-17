package io.specforge;

import io.specforge.web.WebServer;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Entry point: java -jar specforge.jar [port] [--no-browser] [--templates=DIR] [--home=DIR]
 */
public class App {
    /** The version in pom.xml, recorded at build time (specforge/version.properties). */
    public static final String VERSION = readVersion();

    private static String readVersion() {
        try (var in = App.class.getResourceAsStream("/specforge/version.properties")) {
            java.util.Properties p = new java.util.Properties();
            if (in != null) p.load(in);
            String v = p.getProperty("version", "");
            return v.isBlank() || v.startsWith("${") ? "dev" : v;
        } catch (java.io.IOException e) {
            return "dev";
        }
    }

    public static void main(String[] args) throws Exception {
        int port = 0;
        boolean browser = true;
        Path templates = null;
        String homeArg = null;
        for (String a : args) {
            if (a.equals("-h") || a.equals("--help")) {
                usage();
                return;
            } else if (a.equals("--version")) {
                System.out.println("SpecForge " + VERSION);
                return;
            } else if (a.equals("--no-browser")) {
                browser = false;
            } else if (a.startsWith("--port=")) {
                port = parsePort(a.substring(7));
            } else if (a.startsWith("--templates=")) {
                templates = Paths.get(a.substring(12)).toAbsolutePath().normalize();
            } else if (a.startsWith("--home=")) {
                homeArg = a.substring(7);
            } else if (a.startsWith("--refresh-spring-metadata=")) {
                io.specforge.spring.MetadataSnapshotTool.run(Paths.get(a.substring("--refresh-spring-metadata=".length())));
                return;
            } else if (a.matches("\\d+")) {
                port = parsePort(a);
            } else {
                System.err.println("Unknown argument: " + a);
                usage();
                System.exit(2);
            }
        }
        Path home = resolveHome(homeArg);
        WebServer server;
        try {
            server = WebServer.start(port, home, templates);
        } catch (java.io.IOException e) {
            System.err.println("Could not start the server: " + e.getMessage());
            System.exit(1);
            return;
        }
        String url = "http://localhost:" + server.port() + "/";
        System.out.println("SpecForge " + VERSION + " is running at " + url);
        System.out.println("  profiles and templates live in " + home);
        // DRV-3: every bundled driver is loaded once now; a failure is reported, never skipped
        long loaded = io.specforge.source.jdbc.Drivers.report().stream().filter(d -> Boolean.TRUE.equals(d.get("available"))).count();
        int total = io.specforge.source.jdbc.Drivers.report().size();
        System.out.println("  database drivers: " + loaded + " of " + total + " loaded" + (loaded < total ? " (see Drivers in the app)" : ""));
        System.out.println("  press Ctrl+C to stop");
        if (browser) openBrowser(url);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        Thread.currentThread().join();
    }

    private static int parsePort(String s) {
        try {
            int p = Integer.parseInt(s.trim());
            if (p < 0 || p > 65535) throw new NumberFormatException();
            return p;
        } catch (NumberFormatException e) {
            System.err.println("Invalid port: " + s);
            System.exit(2);
            return 0;
        }
    }

    private static Path resolveHome(String arg) {
        String h = arg != null ? arg : System.getProperty("specforge.home", System.getenv("SPECFORGE_HOME"));
        if (h == null || h.isBlank()) h = Paths.get(System.getProperty("user.home", "."), ".specforge").toString();
        return Paths.get(h).toAbsolutePath().normalize();
    }

    private static void openBrowser(String url) {
        try {
            if (!GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("mac")) new ProcessBuilder("open", url).start();
            else if (os.contains("win")) new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            else new ProcessBuilder("xdg-open", url).start();
        } catch (Exception e) {
            System.out.println("  (could not open a browser automatically; open the URL above yourself)");
        }
    }

    private static void usage() {
        System.out.println("""
            SpecForge %s - Swagger/OpenAPI to Spring Boot skeleton generator

            Usage: java -jar specforge.jar [port] [options]

              port                 TCP port to listen on (default: a free port chosen automatically)
              --port=N             same as the positional port
              --no-browser         do not open the default browser
              --templates=DIR      external template folder (overrides bundled templates file by file)
              --home=DIR           where profiles/ and templates/ are stored (default ~/.specforge,
                                   or the SPECFORGE_HOME environment variable)
              --refresh-spring-metadata=FILE
                                   rebuild the Spring Boot metadata snapshot from start.spring.io and
                                   Maven Central into FILE (maintainers: src/main/resources/spring/metadata.json)
              --version, --help
            """.formatted(VERSION));
    }
}
