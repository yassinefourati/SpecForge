package io.specforge.config;

import io.specforge.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Saves GeneratorConfig profiles as JSON files under ~/.specforge/profiles. */
public class ProfileStore {
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 _.-]{0,63}");
    private final Path dir;

    public ProfileStore(Path dir) {
        this.dir = dir;
    }

    public Path dir() {
        return dir;
    }

    public List<String> list() throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        List<String> names = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                String n = p.getFileName().toString();
                names.add(n.substring(0, n.length() - 5));
            });
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public GeneratorConfig load(String name) throws IOException {
        Path p = file(name);
        if (!Files.isRegularFile(p)) 
            throw new NoSuchFileException("No such profile: " + name);
        return Json.MAPPER.readValue(Files.readString(p, StandardCharsets.UTF_8), GeneratorConfig.class);
    }

    public void save(String name, GeneratorConfig cfg) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(file(name), Json.pretty(cfg), StandardCharsets.UTF_8);
    }

    public boolean delete(String name) throws IOException {
        return Files.deleteIfExists(file(name));
    }

    private Path file(String name) {
        if (name == null || !SAFE.matcher(name).matches())
            throw new IllegalArgumentException("Profile names may contain letters, digits, space, '.', '_' and '-' (max 64 chars).");
        return dir.resolve(name + ".json");
    }
}
