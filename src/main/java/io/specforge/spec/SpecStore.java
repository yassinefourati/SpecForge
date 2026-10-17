package io.specforge.spec;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small in-memory LRU cache of parsed specs so the UI only uploads a spec once. */
public class SpecStore {
    private static final int MAX = 12;
    private final Map<String, SpecLoader.Loaded> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SpecLoader.Loaded> e) {
            return size() > MAX;
        }
    };

    public synchronized void put(SpecLoader.Loaded l) {
        cache.put(l.id, l);
    }

    public synchronized SpecLoader.Loaded get(String id) {
        return id == null ? null : cache.get(id);
    }
}
