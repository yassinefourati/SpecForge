package io.specforge.spring;

import io.specforge.spring.SpringMetadata.Artifact;
import io.specforge.spring.SpringMetadata.BootLine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a Spring Boot version and the dependency ids a project needs into build-file entries, using the coordinates
 * published for that version (VER-3). Versions are never written for BOM-managed libraries.
 */
public final class BuildResolver {

    private BuildResolver() {}

    /** One line of the build file's dependency list: an artifact, or a comment. */
    public static class Entry {
        public String groupId = "";
        public String artifactId = "";
        public String scope = "compile";
        /** only for libraries the Spring Boot BOM does not manage (e.g. ArchUnit); "" = managed */
        public String version = "";
        public boolean optional;
        public String comment = "";

        public boolean hasComment() {
            return !comment.isEmpty();
        }
    }

    public static class Build {
        public String bootVersion = "";
        public int bootMajor;
        /** The metadata line whose coordinates are used. */
        public String lineVersion = "";
        public List<Entry> dependencies = new ArrayList<>();
        public boolean lombok;
        public List<String> warnings = new ArrayList<>();
    }

    /**
     * @param ids dependency ids in the order they should appear; an id starting with "#" is emitted as a comment;
     *            "=group:artifact[:scope[:version]]" is a library Initializr has no id for: managed by the Spring
     *            Boot BOM (no version, VER-3), or pinned when the BOM does not manage it
     */
    public static Build resolve(SpringMetadata m, String bootVersion, List<String> ids) {
        Build b = new Build();
        b.bootVersion = bootVersion;
        b.bootMajor = VersionUtil.major(bootVersion);
        BootLine line = pickLine(m, bootVersion, b.warnings);
        b.lineVersion = line.version;

        List<Entry> main = new ArrayList<>(), tests = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String id : ids) {
            if (id.startsWith("#")) {
                Entry c = new Entry();
                c.comment = id.substring(1).trim();
                main.add(c);
                continue;
            }
            if (id.startsWith("=")) {
                String[] gav = id.substring(1).split(":");
                Entry e = new Entry();
                e.groupId = gav[0];
                e.artifactId = gav[1];
                e.scope = gav.length > 2 ? gav[2] : "compile";
                e.version = gav.length > 3 ? gav[3] : "";
                if (seen.add(e.groupId + ":" + e.artifactId + ":" + e.scope)) main.add(e);
                continue;
            }
            List<Artifact> arts = line.dependencies.get(id);
            if (arts == null) {
                b.warnings.add("Spring Boot " + line.version + " has no '" + id + "' dependency; it was left out of the build file.");
                continue;
            }
            if (id.equals("lombok")) b.lombok = true;
            for (Artifact a : arts) {
                if (!seen.add(a.key() + ":" + a.scope)) continue;
                ("test".equals(a.scope) ? tests : main).add(entry(a));
            }
        }
        // Boot 4 ships a test companion per starter; Boot 3 has one test starter for everything
        if (tests.isEmpty()) for (Artifact a : line.base) if ("test".equals(a.scope)) tests.add(entry(a));
        b.dependencies.addAll(main);
        b.dependencies.addAll(tests);
        return b;
    }

    /** Exact version, else the same minor line, else the newest line of the same major, else the newest line. */
    static BootLine pickLine(SpringMetadata m, String version, List<String> warnings) {
        BootLine exact = m.line(version);
        if (exact != null) return exact;
        String minor = VersionUtil.minorLine(version);
        for (BootLine l : m.lines) if (VersionUtil.minorLine(l.version).equals(minor)) return l;
        int major = VersionUtil.major(version);
        for (BootLine l : m.lines) {
            if (l.major() == major) {
                warnings.add("Spring Boot " + version + " is not in SpecForge's tested versions; library coordinates of " + l.version + " were used.");
                return l;
            }
        }
        BootLine newest = m.lines.get(0);
        warnings.add("Spring Boot " + version + " is not supported by SpecForge's templates; library coordinates of " + newest.version + " were used.");
        return newest;
    }

    private static Entry entry(Artifact a) {
        Entry e = new Entry();
        e.groupId = a.groupId;
        e.artifactId = a.artifactId;
        e.scope = a.scope;
        e.optional = a.optional;
        return e;
    }
}
