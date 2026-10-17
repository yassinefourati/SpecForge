package io.specforge.gen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes generated files to disk (never overwriting unless asked) or packs them into a ZIP. */
public final class ProjectWriter {
    private ProjectWriter() {}

    /** SKIP (default: never overwrite) | BACKUP (rename existing to .bak, then write) | OVERWRITE */
    public static class Report {
        public List<String> created = new ArrayList<>();
        public List<String> skipped = new ArrayList<>();
        public List<String> unchanged = new ArrayList<>();
        public List<String> overwritten = new ArrayList<>();
        public List<String> backedUp = new ArrayList<>();
        public Map<String, String> errors = new LinkedHashMap<>();
    }

    public static Report write(List<GeneratedFile> files, String policy) {
        String pol = policy == null ? "SKIP" : policy.toUpperCase();
        Report r = new Report();
        for (GeneratedFile f : files) {
            Path p = Paths.get(f.absPath);
            try {
                if (Files.exists(p)) {
                    if (Files.isDirectory(p)) {
                        r.errors.put(f.path, "A directory exists at this path.");
                        continue;
                    }
                    String existing = Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n");
                    if (existing.equals(f.content)) {
                        r.unchanged.add(f.path);
                        continue;
                    }
                    switch (pol) {
                        case "OVERWRITE" -> {
                            Files.writeString(p, f.content, StandardCharsets.UTF_8);
                            r.overwritten.add(f.path);
                        }
                        case "BACKUP" -> {
                            Path bak = p.resolveSibling(p.getFileName() + ".bak");
                            int i = 1;
                            while (Files.exists(bak)) bak = p.resolveSibling(p.getFileName() + ".bak" + i++);
                            Files.move(p, bak);
                            Files.writeString(p, f.content, StandardCharsets.UTF_8);
                            r.overwritten.add(f.path);
                            r.backedUp.add(f.path + " -> " + bak.getFileName());
                        }
                        default -> r.skipped.add(f.path);
                    }
                } else {
                    Files.createDirectories(p.getParent());
                    Files.writeString(p, f.content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                    r.created.add(f.path);
                }
            } catch (IOException | RuntimeException e) {
                r.errors.put(f.path, e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        return r;
    }

    public static byte[] zip(List<GeneratedFile> files, String rootFolder) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            for (GeneratedFile f : files) {
                String name = (rootFolder == null || rootFolder.isBlank() ? "" : rootFolder + "/") + f.path;
                ZipEntry e = new ZipEntry(name);
                z.putNextEntry(e);
                z.write(f.content.getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return bos.toByteArray();
    }
}
