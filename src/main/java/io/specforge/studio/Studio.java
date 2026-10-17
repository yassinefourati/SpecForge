package io.specforge.studio;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.specforge.gen.TemplateManager;
import io.specforge.secrets.AtomicFiles;
import io.specforge.util.Json;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Spring Boot Studio's files (STU-1, STU-6, STU-7): named template sets under {@code ~/.specforge/templates/<set>/}.
 * A set holds only the templates the user customized; everything else keeps rendering from the bundled defaults.
 * When a template is customized, the hash of the default it started from is kept in {@code .studio.json}, so a
 * newer SpecForge can tell "Custom" from "Custom, default changed since".
 */
public final class Studio {

    /** Per set: the default's SHA-256 when each template was customized. */
    private static final String STATE = ".studio.json";
    private static final long MAX_ZIP_ENTRY = 2_000_000;
    private static final int MAX_ZIP_ENTRIES = 500;

    private final Path root;

    public Studio(Path home) {
        this.root = home.resolve("templates").toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    // ------------------------------------------------------------------ sets

    public List<Map<String, Object>> sets() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> s = Files.list(root)) {
            for (Path p : s.filter(Files::isDirectory).sorted().toList()) {
                String name = p.getFileName().toString();
                if (!TemplateManager.safeName(name)) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("dir", p.toString());
                m.put("customized", customized(p).size());
                out.add(m);
            }
        }
        return out;
    }

    /** The folder of a set; it must exist unless {@code create}. */
    public Path set(String name, boolean create) throws IOException {
        if (!TemplateManager.safeName(name)) throw new IllegalArgumentException("A set name uses letters, digits, '.', '_' and '-' only.");
        Path p = root.resolve(name).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("Invalid set name.");
        if (create) Files.createDirectories(p);
        else if (!Files.isDirectory(p)) throw new NoSuchFileException("No template set named '" + name + "'.");
        return p;
    }

    /** A new set, empty (every template Default) or a copy of another set's customizations. */
    public Path create(String name, String copyOf) throws IOException {
        if (!TemplateManager.safeName(name)) throw new IllegalArgumentException("A set name uses letters, digits, '.', '_' and '-' only.");
        if (Files.isDirectory(root.resolve(name))) throw new IllegalArgumentException("A template set named '" + name + "' already exists.");
        Path target = set(name, true);
        if (copyOf != null && !copyOf.isBlank()) {
            Path from = set(copyOf, false);
            try (Stream<Path> s = Files.list(from)) {
                for (Path f : s.filter(Files::isRegularFile).toList()) Files.copy(f, target.resolve(f.getFileName().toString()));
            }
        }
        return target;
    }

    public void delete(String name) throws IOException {
        Path p = set(name, false);
        try (Stream<Path> s = Files.walk(p)) {
            for (Path f : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(f);
        }
    }

    // ------------------------------------------------------------------ templates of a set

    /** Every template of the bundled manifest (and of the set's own manifest), with its status in this set. */
    public List<Map<String, Object>> templates(Path set) throws IOException {
        TemplateManager tm = new TemplateManager(set);
        Map<String, TemplateManager.Entry> byTemplate = new LinkedHashMap<>();
        for (TemplateManager.Entry e : TemplateManager.bundled().templates) byTemplate.putIfAbsent(e.template, e);
        for (TemplateManager.Entry e : tm.manifest().templates) byTemplate.putIfAbsent(e.template, e);
        Map<String, String> base = baseHashes(set);
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        names.add("manifest.json");
        names.addAll(byTemplate.keySet());
        for (String n : names) {
            TemplateManager.Entry e = byTemplate.get(n);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("template", n);
            m.put("id", e == null ? "manifest" : e.id);
            m.put("layer", e == null ? "project" : e.layer);
            m.put("scope", e == null ? "-" : e.scope);
            m.put("flow", e == null ? "ANY" : e.flow == null || e.flow.isBlank() ? ("GLOBAL".equalsIgnoreCase(e.scope) ? "ANY" : "SPEC") : e.flow);
            m.put("description", e == null ? "Which template renders what, and where it is written" : e.description);
            m.put("status", status(set, n, base));
            out.add(m);
        }
        return out;
    }

    /** DEFAULT, CUSTOM, CUSTOM_DEFAULT_CHANGED (the bundled default changed since it was customized) or CUSTOM_ONLY (no default). */
    public String status(Path set, String name, Map<String, String> base) throws IOException {
        if (!Files.isRegularFile(set.resolve(name))) return "DEFAULT";
        String def = TemplateManager.bundledSource(name);
        if (def == null) return "CUSTOM_ONLY";
        String recorded = base.get(name);
        return recorded == null || recorded.equals(sha256(def)) ? "CUSTOM" : "CUSTOM_DEFAULT_CHANGED";
    }

    public Map<String, Object> template(Path set, String name) throws IOException {
        check(name);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("template", name);
        String def = TemplateManager.bundledSource(name);
        Path f = set.resolve(name);
        m.put("content", Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : def);
        m.put("default", def);
        m.put("status", status(set, name, baseHashes(set)));
        return m;
    }

    /** STU-6: copies the default into the set and records which default it started from. */
    public void customize(Path set, String name) throws IOException {
        check(name);
        Path f = set.resolve(name);
        if (Files.isRegularFile(f)) return;
        String def = TemplateManager.bundledSource(name);
        if (def == null) throw new NoSuchFileException("No default template named '" + name + "'.");
        AtomicFiles.write(f, def);
        Map<String, String> base = baseHashes(set);
        base.put(name, sha256(def));
        saveBaseHashes(set, base);
    }

    public void save(Path set, String name, String content) throws IOException {
        check(name);
        if (content == null) throw new IllegalArgumentException("Nothing to save.");
        if (content.length() > MAX_ZIP_ENTRY) throw new IllegalArgumentException("A template may be at most " + MAX_ZIP_ENTRY + " characters.");
        if (name.equals("manifest.json")) Json.MAPPER.readValue(content, TemplateManager.Manifest.class); // must stay valid
        Map<String, String> base = baseHashes(set);
        if (!Files.isRegularFile(set.resolve(name)) && TemplateManager.bundledSource(name) != null) {
            base.put(name, sha256(TemplateManager.bundledSource(name)));
            saveBaseHashes(set, base);
        }
        AtomicFiles.write(set.resolve(name), content);
    }

    /** STU-6: back to the default; the custom copy is deleted. */
    public void reset(Path set, String name) throws IOException {
        check(name);
        Files.deleteIfExists(set.resolve(name));
        Map<String, String> base = baseHashes(set);
        if (base.remove(name) != null) saveBaseHashes(set, base);
    }

    // ------------------------------------------------------------------ ZIP export / import (STU-7)

    public byte[] exportZip(Path set) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes); Stream<Path> s = Files.list(set)) {
            for (Path f : s.filter(Files::isRegularFile).sorted().toList()) {
                zip.putNextEntry(new ZipEntry(f.getFileName().toString()));
                zip.write(Files.readAllBytes(f));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** Flat ZIPs only: no folders, no paths, safe names, bounded sizes. Existing files of the set are replaced. */
    public int importZip(Path set, byte[] zipBytes) throws IOException {
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (!TemplateManager.safeName(name) && !name.equals(STATE)) throw new IllegalArgumentException("The ZIP holds '" + name + "': only template files at its top level are accepted.");
                if (++count > MAX_ZIP_ENTRIES) throw new IllegalArgumentException("The ZIP holds more than " + MAX_ZIP_ENTRIES + " files.");
                byte[] data = zip.readNBytes((int) MAX_ZIP_ENTRY + 1);
                if (data.length > MAX_ZIP_ENTRY) throw new IllegalArgumentException("'" + name + "' is larger than " + MAX_ZIP_ENTRY + " bytes.");
                if (name.equals("manifest.json")) Json.MAPPER.readValue(data, TemplateManager.Manifest.class);
                AtomicFiles.write(set.resolve(name), data, false);
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> customized(Path set) throws IOException {
        try (Stream<Path> s = Files.list(set)) {
            return s.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).filter(n -> !n.equals(STATE)).toList();
        }
    }

    private static void check(String name) {
        if (!TemplateManager.safeName(name) || name.equals(STATE)) throw new IllegalArgumentException("Invalid template name.");
    }

    private static Map<String, String> baseHashes(Path set) throws IOException {
        Path f = set.resolve(STATE);
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(f)) return out;
        ObjectNode n = (ObjectNode) Json.MAPPER.readTree(Files.readString(f, StandardCharsets.UTF_8));
        if (n.has("base")) n.get("base").fields().forEachRemaining(x -> out.put(x.getKey(), x.getValue().asText()));
        return out;
    }

    private static void saveBaseHashes(Path set, Map<String, String> base) throws IOException {
        AtomicFiles.write(set.resolve(STATE), Json.write(Map.of("base", base)));
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
