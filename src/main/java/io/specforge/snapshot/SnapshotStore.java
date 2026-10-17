package io.specforge.snapshot;

import io.specforge.secrets.AtomicFiles;
import io.specforge.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Snapshots by fingerprint (INT-9): in memory for speed, on disk under ~/.specforge/snapshots/ so the UI can move
 * between steps and sessions without reconnecting. Snapshots hold schema metadata only; they are deletable.
 */
public class SnapshotStore {

    private static final int MAX_IN_MEMORY = 8;

    private final Path dir;
    private final Map<String, Snapshot> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Snapshot> e) {
            return size() > MAX_IN_MEMORY;
        }
    };

    public SnapshotStore(Path home) {
        this.dir = home.resolve("snapshots");
    }

    public synchronized void put(Snapshot s) throws IOException {
        cache.put(s.id, s);
        AtomicFiles.write(file(s.id), Json.write(s).getBytes(java.nio.charset.StandardCharsets.UTF_8), true);
    }

    public synchronized Snapshot get(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{16,128}")) return null;
        Snapshot s = cache.get(id);
        if (s != null) return s;
        Path f = file(id);
        if (!Files.isRegularFile(f)) return null;
        s = Json.MAPPER.readValue(Files.readString(f), Snapshot.class);
        cache.put(id, s);
        return s;
    }

    public synchronized boolean delete(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{16,128}")) return false;
        cache.remove(id);
        return Files.deleteIfExists(file(id));
    }

    private Path file(String id) {
        return dir.resolve(id + ".json");
    }
}
