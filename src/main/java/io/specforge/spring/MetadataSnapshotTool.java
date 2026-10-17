package io.specforge.spring;

import com.fasterxml.jackson.databind.JsonNode;
import io.specforge.spring.SpringMetadata.Artifact;
import io.specforge.spring.SpringMetadata.BootLine;
import io.specforge.spring.SpringMetadata.CatalogEntry;
import io.specforge.util.Json;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds the bundled Spring metadata snapshot (SYNC-1, SYNC-2, SYNC-4):
 * {@code java -jar specforge.jar --refresh-spring-metadata=src/main/resources/spring/metadata.json}.
 * Review the diff before committing it.
 */
public final class MetadataSnapshotTool {

    /** Boot versions the default templates are tested with (manifest bootRange). */
    public static final String TESTED_RANGE = "[3.5.0,4.2.0)";

    /** Dependency ids SpecForge itself may add to generated projects. */
    static final List<String> GENERATED_IDS = List.of(
        "web", "data-jpa", "validation", "security", "oauth2-resource-server", "lombok", "actuator",
        "h2", "postgresql", "mysql", "mariadb", "sqlserver", "oracle", "db2", "sqlite",
        "flyway", "liquibase", "data-mongodb", "data-elasticsearch");

    private static final String V22 = "application/vnd.initializr.v2.2+json";

    /** BOM properties holding the versions of drivers SpecForge bundles (VER-4). */
    static final List<String> DRIVER_VERSION_PROPERTIES = List.of(
        "postgresql.version", "mysql.version", "mariadb.version", "mssql-jdbc.version", "oracle-database.version",
        "db2-jdbc.version", "h2.version", "sqlite-jdbc.version", "mongodb.version", "elasticsearch-client.version");

    private final MetadataClient client;

    public MetadataSnapshotTool(MetadataClient client) {
        this.client = client;
    }

    public static void run(Path out) throws Exception {
        SpringMetadata m = new MetadataSnapshotTool(new MetadataClient()).build();
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, Json.pretty(m) + "\n", StandardCharsets.UTF_8);
        System.out.println("Wrote " + out + ": " + m.lines.size() + " Boot versions, " + m.catalog.size() + " catalog entries.");
        for (BootLine l : m.lines) System.out.println("  " + l.version + " (" + l.origin + "), Java " + l.javaVersions);
    }

    public SpringMetadata build() throws Exception {
        JsonNode root = Json.MAPPER.readTree(client.get(MetadataClient.INITIALIZR + "/", V22));
        if (!root.path("bootVersion").path("values").isArray() || !root.path("dependencies").path("values").isArray())
            throw new MetadataClient.MetadataUnavailable("start.spring.io returned unexpected metadata (no bootVersion or dependencies).");
        SpringMetadata m = new SpringMetadata();
        m.fetchedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        m.bootDefault = root.path("bootVersion").path("default").asText("");
        m.javaDefault = root.path("javaVersion").path("default").asText("17");
        List<String> javaVersions = new ArrayList<>();
        for (JsonNode j : root.path("javaVersion").path("values")) javaVersions.add(j.path("id").asText());
        for (JsonNode g : root.path("dependencies").path("values")) {
            for (JsonNode d : g.path("values")) {
                CatalogEntry e = new CatalogEntry();
                e.id = d.path("id").asText();
                e.name = d.path("name").asText();
                e.group = g.path("name").asText();
                e.description = d.path("description").asText("");
                e.versionRange = d.path("versionRange").asText("");
                JsonNode ref = d.path("_links").path("reference");
                e.docs = ref.isArray() ? ref.path(0).path("href").asText("") : ref.path("href").asText("");
                m.catalog.add(e);
            }
        }

        Set<String> initializrLines = new LinkedHashSet<>();
        for (JsonNode v : root.path("bootVersion").path("values")) {
            String version = v.path("id").asText();
            if (!VersionUtil.isGa(version)) continue;
            m.lines.add(initializrLine(version, javaVersions));
            initializrLines.add(VersionUtil.minorLine(version));
        }
        if (m.lines.isEmpty()) throw new MetadataClient.MetadataUnavailable("start.spring.io lists no GA Spring Boot version.");

        // older lines Initializr no longer serves: latest patch from Maven Central, coordinates from the Boot 3 table
        String central = client.get(MetadataClient.CENTRAL + "/org/springframework/boot/spring-boot-dependencies/maven-metadata.xml", "application/xml");
        Map<String, String> latestPerLine = new TreeMap<>((a, b) -> VersionUtil.compare(b, a));
        for (String v : xmlValues(central, "version")) {
            if (!VersionUtil.isGa(v) || !VersionUtil.inRange(v, TESTED_RANGE)) continue;
            String line = VersionUtil.minorLine(v);
            if (initializrLines.contains(line)) continue;
            latestPerLine.merge(line, v, (a, b) -> VersionUtil.compare(a, b) >= 0 ? a : b);
        }
        for (String v : latestPerLine.values()) {
            if (VersionUtil.major(v) == 3) m.lines.add(boot3Line(v));
        }

        // driver versions of the default line's BOM, for SpecForge's own bundled drivers (VER-4)
        String bom = client.get(MetadataClient.CENTRAL + "/org/springframework/boot/spring-boot-dependencies/" + m.bootDefault
            + "/spring-boot-dependencies-" + m.bootDefault + ".pom", "application/xml");
        Document bomDoc = parse(bom);
        for (String p : DRIVER_VERSION_PROPERTIES) {
            NodeList n = bomDoc.getElementsByTagName(p);
            if (n.getLength() > 0) m.bomDriverVersions.put(p, n.item(0).getTextContent().trim());
        }
        return m;
    }

    private BootLine initializrLine(String version, List<String> javaVersions) throws Exception {
        BootLine line = new BootLine();
        line.version = version;
        line.origin = "initializr";
        line.javaVersions = new ArrayList<>(javaVersions);
        List<Artifact> base = pomDependencies(version, "");
        Set<String> baseKeys = new LinkedHashSet<>();
        for (Artifact a : base) baseKeys.add(a.key());
        for (Artifact a : base) if (!a.artifactId.equals("spring-boot-starter")) line.base.add(a);
        for (String id : GENERATED_IDS) {
            List<Artifact> deps;
            try {
                deps = pomDependencies(version, id);
            } catch (MetadataClient.MetadataUnavailable e) {
                continue; // not available for this Boot version
            }
            List<Artifact> added = new ArrayList<>();
            for (Artifact a : deps) if (!baseKeys.contains(a.key())) added.add(a);
            // main artifacts first, test companions after
            added.sort((a, b) -> Boolean.compare("test".equals(a.scope), "test".equals(b.scope)));
            if (!added.isEmpty()) line.dependencies.put(id, added);
        }
        return line;
    }

    private List<Artifact> pomDependencies(String bootVersion, String id) throws Exception {
        String url = MetadataClient.INITIALIZR + "/pom.xml?type=maven-project&language=java&bootVersion=" + bootVersion
            + (id.isEmpty() ? "" : "&dependencies=" + id);
        Document doc = parse(client.get(url, "application/xml"));
        List<Artifact> out = new ArrayList<>();
        NodeList deps = doc.getElementsByTagName("dependency");
        for (int i = 0; i < deps.getLength(); i++) {
            Element d = (Element) deps.item(i);
            if (!"dependencies".equals(d.getParentNode().getNodeName())) continue;
            if (!"project".equals(d.getParentNode().getParentNode().getNodeName())) continue; // skip dependencyManagement
            out.add(new Artifact(text(d, "groupId"), text(d, "artifactId"), text(d, "scope"), "true".equals(text(d, "optional"))));
        }
        return out;
    }

    /** Coordinates for Boot 3 lines, which start.spring.io no longer serves. Matches SpecForge's Boot 3 output. */
    static BootLine boot3Line(String version) {
        BootLine l = new BootLine();
        l.version = version;
        l.origin = "maven-central";
        l.javaVersions = List.of("25", "21", "17");
        l.base = List.of(new Artifact("org.springframework.boot", "spring-boot-starter-test", "test", false));
        Map<String, List<Artifact>> d = new LinkedHashMap<>();
        d.put("web", List.of(boot("spring-boot-starter-web")));
        d.put("data-jpa", List.of(boot("spring-boot-starter-data-jpa")));
        d.put("validation", List.of(boot("spring-boot-starter-validation")));
        d.put("security", List.of(boot("spring-boot-starter-security")));
        d.put("oauth2-resource-server", List.of(boot("spring-boot-starter-oauth2-resource-server")));
        d.put("lombok", List.of(new Artifact("org.projectlombok", "lombok", "compile", true)));
        d.put("actuator", List.of(boot("spring-boot-starter-actuator")));
        d.put("h2", List.of(new Artifact("com.h2database", "h2", "runtime", false)));
        d.put("postgresql", List.of(new Artifact("org.postgresql", "postgresql", "runtime", false)));
        d.put("mysql", List.of(new Artifact("com.mysql", "mysql-connector-j", "runtime", false)));
        d.put("mariadb", List.of(new Artifact("org.mariadb.jdbc", "mariadb-java-client", "runtime", false)));
        d.put("sqlserver", List.of(new Artifact("com.microsoft.sqlserver", "mssql-jdbc", "runtime", false)));
        d.put("oracle", List.of(new Artifact("com.oracle.database.jdbc", "ojdbc11", "runtime", false)));
        d.put("db2", List.of(new Artifact("com.ibm.db2", "jcc", "runtime", false)));
        d.put("sqlite", List.of(new Artifact("org.xerial", "sqlite-jdbc", "runtime", false)));
        d.put("flyway", List.of(new Artifact("org.flywaydb", "flyway-core", "compile", false)));
        d.put("liquibase", List.of(new Artifact("org.liquibase", "liquibase-core", "compile", false)));
        d.put("data-mongodb", List.of(boot("spring-boot-starter-data-mongodb")));
        d.put("data-elasticsearch", List.of(boot("spring-boot-starter-data-elasticsearch")));
        l.dependencies = d;
        return l;
    }

    private static Artifact boot(String artifactId) {
        return new Artifact("org.springframework.boot", artifactId, "compile", false);
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<String> xmlValues(String xml, String tag) throws Exception {
        NodeList n = parse(xml).getElementsByTagName(tag);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n.getLength(); i++) out.add(n.item(i).getTextContent().trim());
        return out;
    }

    private static String text(Element parent, String tag) {
        NodeList n = parent.getElementsByTagName(tag);
        return n.getLength() == 0 ? "" : n.item(0).getTextContent().trim();
    }
}
