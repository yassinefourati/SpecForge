package io.specforge.source;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.specforge.secrets.AtomicFiles;
import io.specforge.secrets.SecretStore;
import io.specforge.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Saved connections in ~/.specforge/connections.json, separate from profiles (SEC-1). Secret fields are encrypted
 * (SEC-2); the public view never contains them (SEC-4).
 */
public class ConnectionStore {

    private final Path file;
    private final SecretStore secrets;

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class StoredFile {
        public List<Stored> connections = new ArrayList<>();
    }

    /** A connection as written to disk: settings without secrets, plus encrypted secrets. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Stored {
        public ConnectionSettings settings = new ConnectionSettings();
        public Map<String, String> secrets = new LinkedHashMap<>();
    }

    public ConnectionStore(Path home, SecretStore secrets) {
        this.file = home.resolve("connections.json");
        this.secrets = secrets;
    }

    public synchronized List<Map<String, Object>> list() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Stored s : read().connections) out.add(publicView(s));
        return out;
    }

    /** Full settings with decrypted secrets, for connecting. */
    public synchronized ConnectionSettings get(String id) throws IOException {
        Stored s = find(read(), id);
        ConnectionSettings c = copyWithoutSecrets(s.settings);
        try {
            if (s.secrets.containsKey("password")) c.password = secrets.decrypt(s.secrets.get("password"));
            if (s.secrets.containsKey("url")) c.url = secrets.decrypt(s.secrets.get("url"));
        } catch (SecretStore.SecretUnavailable e) {
            throw new SourceException("SECRET_UNDECRYPTABLE", e.getMessage(), "Edit the connection and re-enter its password.", "Re-enter the secret");
        }
        return c;
    }

    /** Creates (id empty) or updates a connection. A null secret keeps the stored one, "" clears it. */
    public synchronized Map<String, Object> save(ConnectionSettings in) throws IOException {
        SourceKind.of(in.kind);
        StoredFile f = read();
        Stored target = null;
        if (in.id != null && !in.id.isBlank()) target = find(f, in.id);
        if (target == null) {
            target = new Stored();
            in.id = newId(in.name);
            f.connections.add(target);
        }
        Map<String, String> enc = new LinkedHashMap<>(target.secrets);
        putSecret(enc, "password", in.password);
        putSecret(enc, "url", in.url);
        target.settings = copyWithoutSecrets(in);
        target.secrets = enc;
        write(f);
        return publicView(target);
    }

    public synchronized void delete(String id) throws IOException {
        StoredFile f = read();
        f.connections.remove(find(f, id));
        write(f);
    }

    private void putSecret(Map<String, String> enc, String key, String value) {
        if (value == null) return;
        if (value.isEmpty()) enc.remove(key);
        else enc.put(key, secrets.encrypt(value));
    }

    private Map<String, Object> publicView(Stored s) {
        Map<String, Object> m = Json.MAPPER.convertValue(copyWithoutSecrets(s.settings), new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
        m.remove("password");
        m.remove("url");
        Map<String, Object> sv = new LinkedHashMap<>();
        sv.put("password", secretView(s, "password", s.settings.passwordEnv));
        sv.put("url", secretView(s, "url", s.settings.urlEnv));
        m.put("secrets", sv);
        return m;
    }

    private Map<String, Object> secretView(Stored s, String key, String env) {
        String value = "";
        if (s.secrets.containsKey(key)) {
            try {
                value = secrets.decrypt(s.secrets.get(key));
            } catch (SecretStore.SecretUnavailable e) {
                value = "";
            }
        }
        boolean fromEnv = env != null && !env.isBlank() && System.getenv(env.trim()) != null;
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("set", !value.isEmpty() || fromEnv);
        v.put("masked", SecretStore.mask(value));
        v.put("env", env == null || env.isBlank() ? null : env.trim());
        v.put("fromEnv", fromEnv);
        return v;
    }

    private static ConnectionSettings copyWithoutSecrets(ConnectionSettings in) {
        ConnectionSettings c = Json.MAPPER.convertValue(in, ConnectionSettings.class);
        c.password = null;
        c.url = null;
        return c;
    }

    private static Stored find(StoredFile f, String id) {
        for (Stored s : f.connections) if (s.settings.id.equals(id)) return s;
        throw new SourceException("CONNECTION_NOT_FOUND", "No saved connection with id '" + id + "'.", "", "");
    }

    private static String newId(String name) {
        String base = (name == null ? "" : name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (base.isEmpty()) base = "connection";
        if (base.length() > 30) base = base.substring(0, 30);
        byte[] r = new byte[3];
        new SecureRandom().nextBytes(r);
        return base + "-" + HexFormat.of().formatHex(r);
    }

    private StoredFile read() throws IOException {
        if (!Files.isRegularFile(file)) return new StoredFile();
        return Json.MAPPER.readValue(Files.readString(file), StoredFile.class);
    }

    private void write(StoredFile f) throws IOException {
        AtomicFiles.write(file, Json.pretty(f).getBytes(java.nio.charset.StandardCharsets.UTF_8), true);
    }
}
