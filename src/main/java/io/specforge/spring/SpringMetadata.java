package io.specforge.spring;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring Boot versions and library coordinates, as published by start.spring.io and Maven Central.
 * A snapshot is bundled in the JAR (spring/metadata.json); see {@link MetadataSnapshotTool}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SpringMetadata {
    /** ISO-8601 time the snapshot was taken. */
    public String fetchedAt = "";
    /** Initializr's default Boot version. */
    public String bootDefault = "";
    /** Initializr's default Java version. */
    public String javaDefault = "17";
    /** Newest first. */
    public List<BootLine> lines = new ArrayList<>();
    /**
     * Driver version properties of the default line's Spring Boot BOM (e.g. postgresql.version → 42.7.13).
     * SpecForge's own bundled drivers must use these versions (VER-4, ADR 002).
     */
    public Map<String, String> bomDriverVersions = new java.util.TreeMap<>();
    /** Initializr's dependency catalog (for the dependency picker). */
    public List<CatalogEntry> catalog = new ArrayList<>();

    /** One offered Spring Boot version and what each dependency id expands to for it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BootLine {
        public String version = "";
        /** initializr | maven-central (older lines Initializr no longer serves; coordinates from a built-in table) */
        public String origin = "initializr";
        public List<String> javaVersions = new ArrayList<>();
        /** Dependencies of a project with no dependency selected (test starter on Boot 3). */
        public List<Artifact> base = new ArrayList<>();
        /** Dependency id (e.g. "web") → the artifacts it adds, main artifact first, test companions after. */
        public Map<String, List<Artifact>> dependencies = new LinkedHashMap<>();

        @JsonIgnore
        public int major() {
            return VersionUtil.major(version);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Artifact {
        public String groupId = "";
        public String artifactId = "";
        /** compile (default) | runtime | test | provided */
        public String scope = "compile";
        public boolean optional;

        public Artifact() {}

        public Artifact(String groupId, String artifactId, String scope, boolean optional) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.scope = scope == null || scope.isBlank() ? "compile" : scope;
            this.optional = optional;
        }

        @JsonIgnore
        public String key() {
            return groupId + ":" + artifactId;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CatalogEntry {
        public String id = "";
        public String name = "";
        public String group = "";
        public String description = "";
        /** Maven-style range of compatible Boot versions, "" when unrestricted. */
        public String versionRange = "";
        public String docs = "";
    }

    @JsonIgnore
    public BootLine line(String version) {
        for (BootLine l : lines) if (l.version.equals(version)) return l;
        return null;
    }
}
