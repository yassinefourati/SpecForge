package io.specforge.source;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * How to reach one database (CONN-2). {@code password} and {@code url} are secrets: stored encrypted (SEC-2), never
 * returned by the API (SEC-4), overridden by {@code passwordEnv} / {@code urlEnv} when those name a set variable
 * (SEC-3).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConnectionSettings {
    public String id = "";
    public String name = "";
    public String kind = "POSTGRESQL";
    public String host = "localhost";
    public int port;
    public String database = "";
    /** file path for file-based engines (SQLite, H2) */
    public String path = "";
    public String user = "";
    /** secret; null in an update request means "keep the stored value", "" clears it */
    public String password;
    public String passwordEnv = "";
    /** advanced raw JDBC URL override; secret, because URLs can carry credentials (null = keep, "" = clear) */
    public String url;
    public String urlEnv = "";
    /** schemas to read; empty = all visible non-system schemas */
    public List<String> schemas = new ArrayList<>();
    /** disable | prefer | require | verify-ca | verify-full (empty = driver default); mapped per engine by {@link #jdbcUrl()} */
    public String sslMode = "";

    @JsonIgnore
    public SourceKind sourceKind() {
        return SourceKind.of(kind);
    }

    /** The password, from its environment variable when that is set (SEC-3). */
    @JsonIgnore
    public String effectivePassword() {
        String env = passwordEnv == null || passwordEnv.isBlank() ? null : System.getenv(passwordEnv.trim());
        return env != null && !env.isEmpty() ? env : password == null ? "" : password;
    }

    @JsonIgnore
    public String effectiveUrl() {
        String env = urlEnv == null || urlEnv.isBlank() ? null : System.getenv(urlEnv.trim());
        return env != null && !env.isEmpty() ? env : url == null ? "" : url;
    }

    /**
     * JDBC URL without credentials: from the override, else built from the engine's template. This is the URL a
     * generated application uses; SpecForge itself connects with {@link #connectUrl()}.
     */
    @JsonIgnore
    public String jdbcUrl() {
        String raw = effectiveUrl();
        if (!raw.isBlank()) return raw.trim();
        SourceKind k = sourceKind();
        String u = k.urlTemplate
            .replace("{host}", host == null || host.isBlank() ? "localhost" : host.trim())
            .replace("{port}", String.valueOf(port > 0 ? port : k.defaultPort))
            .replace("{database}", database == null ? "" : database.trim())
            .replace("{path}", filePath());
        return u + sslSuffix(k, sslMode == null ? "" : sslMode.trim());
    }

    /** The URL SpecForge opens: {@link #jdbcUrl()} plus the engine's read-only switches where they live in the URL (RO-1). */
    @JsonIgnore
    public String connectUrl() {
        String u = jdbcUrl();
        if (sourceKind() == SourceKind.H2) {
            String upper = u.toUpperCase(java.util.Locale.ROOT);
            if (!upper.contains("IFEXISTS=")) u += ";IFEXISTS=TRUE";
            if (!upper.contains("ACCESS_MODE_DATA=")) u += ";ACCESS_MODE_DATA=r";
        }
        return u;
    }

    /** File path for SQLite / H2 with forward slashes; H2's ".mv.db" suffix is dropped because H2 adds it itself. */
    @JsonIgnore
    public String filePath() {
        String p = path == null ? "" : path.trim().replace('\\', '/');
        if (sourceKind() == SourceKind.H2 && p.endsWith(".mv.db")) p = p.substring(0, p.length() - ".mv.db".length());
        return p;
    }

    /** sslMode (disable | prefer | require | verify-ca | verify-full) in each engine's own URL syntax; "" = driver default. */
    private static String sslSuffix(SourceKind k, String mode) {
        return switch (k) {
            case POSTGRESQL -> mode.isEmpty() ? "" : "?sslmode=" + mode;
            case MYSQL -> mode.isEmpty() ? "" : "?sslMode=" + switch (mode) {
                case "disable" -> "DISABLED";
                case "require" -> "REQUIRED";
                case "verify-ca" -> "VERIFY_CA";
                case "verify-full" -> "VERIFY_IDENTITY";
                default -> "PREFERRED";
            };
            case MARIADB -> mode.isEmpty() || mode.equals("prefer") ? "" : "?sslMode=" + switch (mode) {
                case "disable" -> "disable";
                case "require" -> "trust";
                default -> mode;
            };
            // the template has no encrypt option: the driver default (encrypt, verify the certificate) unless chosen here
            case SQLSERVER -> switch (mode) {
                case "disable" -> ";encrypt=false";
                case "prefer", "require" -> ";encrypt=true;trustServerCertificate=true";
                default -> ";encrypt=true";
            };
            case DB2 -> mode.isEmpty() || mode.equals("disable") || mode.equals("prefer") ? "" : ":sslConnection=true;";
            default -> "";
        };
    }

    /** Values that must never appear in output (SEC-5). */
    @JsonIgnore
    public List<String> secrets() {
        List<String> s = new ArrayList<>();
        s.add(effectivePassword());
        s.add(password);
        s.add(effectiveUrl());
        s.add(url);
        return s;
    }
}
