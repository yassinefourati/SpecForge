package io.specforge.support;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.specforge.contract.AdminTemplateModule;
import io.specforge.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A throw-away copy of an admin-template checkout, and the steps of the generated {@code specforge-<module>.md}
 * applied the way a developer would: if these edits are not enough for the project's own tests, the instructions
 * are incomplete (CONTRACT-5).
 */
public final class AdminTemplateCheckout {

    private AdminTemplateCheckout() {}

    /** The checkout to test against: -Dspecforge.adminTemplate or ADMIN_TEMPLATE_DIR; null when neither is set. */
    public static Path source() {
        String p = System.getProperty("specforge.adminTemplate", System.getenv().getOrDefault("ADMIN_TEMPLATE_DIR", ""));
        if (p.isBlank()) return null;
        Path dir = Path.of(p);
        return Files.isRegularFile(dir.resolve("backend/pom.xml")) ? dir : null;
    }

    /** -Dspecforge.adminTemplate.frontend=true: also check the pages (installs the frontend's dependencies, minutes). */
    public static boolean withFrontend() {
        return Boolean.getBoolean("specforge.adminTemplate.frontend");
    }

    /** Copies backend/ (and frontend/ when asked), without build output, into a new temporary folder. */
    public static Path copy(Path source, boolean frontend) throws IOException {
        Path target = Files.createTempDirectory("specforge-admin-template");
        for (String part : frontend ? List.of("backend", "frontend") : List.of("backend")) {
            Path from = source.resolve(part);
            try (Stream<Path> s = Files.walk(from)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    Path rel = from.relativize(p);
                    String r = rel.toString().replace('\\', '/');
                    if (rel.startsWith("target") || r.contains("node_modules") || r.contains(".next") || r.contains("test-results")) continue;
                    Path to = target.resolve(part).resolve(rel.toString());
                    if (Files.isDirectory(p)) Files.createDirectories(to);
                    else Files.copy(p, to);
                }
            }
        }
        return target;
    }

    /** Runs the frontend's package manager (the pinned pnpm, through npx) in {@code dir}. */
    public static Maven.Result pnpm(Path dir, String... args) throws IOException, InterruptedException {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        List<String> cmd = new java.util.ArrayList<>(List.of(windows ? "npx.cmd" : "npx", "-y", "pnpm@12.5.1"));
        cmd.addAll(List.of(args));
        Path log = Files.createTempFile("specforge-pnpm", ".log");
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!p.waitFor(20, java.util.concurrent.TimeUnit.MINUTES)) {
            p.destroyForcibly();
            return new Maven.Result(-1, "pnpm timed out in " + dir);
        }
        String out = Files.readString(log, StandardCharsets.UTF_8);
        Files.deleteIfExists(log);
        return new Maven.Result(p.exitValue(), out);
    }

    /** The registry edits of the module's instructions. */
    public static void integrate(Path root, AdminTemplateModule m) throws IOException {
        Path b = root.resolve("backend");
        Path main = b.resolve("src/main/java/com/admintemplate");
        Path test = b.resolve("src/test/java/com/admintemplate");
        if (m.newPermission) {
            replace(main.resolve("security/Permissions.java"), "    public static final String AUDIT_VIEW = \"audit.view\";",
                "    public static final String AUDIT_VIEW = \"audit.view\";\n    public static final String " + m.permissionConstant + " = \"" + m.permission + "\";");
            replace(main.resolve("security/Permissions.java"), "SETTINGS_ADMIN, AUDIT_VIEW);", "SETTINGS_ADMIN, AUDIT_VIEW, " + m.permissionConstant + ");");
            replace(main.resolve("security/Authorize.java"), "    public static final String AUDIT_VIEW = HAS + Permissions.AUDIT_VIEW + END;",
                "    public static final String AUDIT_VIEW = HAS + Permissions.AUDIT_VIEW + END;\n    public static final String "
                    + m.permissionConstant + " = HAS + Permissions." + m.permissionConstant + " + END;");
            replace(test.resolve("security/PermissionControllerIT.java"), "\"settings.admin\", \"audit.view\"};", "\"settings.admin\", \"audit.view\", \"" + m.permission + "\"};");
            replace(test.resolve("security/PermissionControllerIT.java"), "\"ADMINISTRATION\")))", "\"ADMINISTRATION\", \"" + m.permissionArea + "\")))");
            Path menuIt = test.resolve("navigation/MenuControllerIT.java");
            String menu = Files.readString(menuIt, StandardCharsets.UTF_8);
            String count = "jsonPath(\"$.permissions.length()\").value(";
            int at = menu.indexOf(count);
            assertThat(at).as("the permission count in MenuControllerIT").isNotNegative();
            int from = at + count.length(), to = menu.indexOf(')', from);
            Files.writeString(menuIt, menu.substring(0, from) + (Integer.parseInt(menu.substring(from, to).trim()) + 1) + menu.substring(to),
                StandardCharsets.UTF_8);
            Path permissionsTs = root.resolve("frontend/apps/web/src/lib/auth/permissions.ts");
            if (m.frontend && Files.isRegularFile(permissionsTs))
                replace(permissionsTs, "  | 'comments.moderate'\n", "  | 'comments.moderate'\n  | '" + m.permission + "'\n");
            replace(test.resolve("user/MeControllerIT.java"), "\"settings.admin\", \"audit.view\")));", "\"settings.admin\", \"audit.view\", \"" + m.permission + "\")));");
        }
        String forms = m.entities.stream().map(x -> "        forms.put(\"" + x.formKey + "\", com.admintemplate." + m.name + "." + x.className + "Dtos."
            + x.className + "Request.class);\n").collect(Collectors.joining());
        replace(main.resolve("reference/FormConstraints.java"), "        return Collections.unmodifiableMap(forms);", forms + "        return Collections.unmodifiableMap(forms);");
        String keys = m.entities.stream().map(x -> ", \"" + x.formKey + "\"").collect(Collectors.joining());
        replace(test.resolve("reference/FormConstraintsTest.java"), "\"menuGroup\");", "\"menuGroup\"" + keys + ");");
        String notAudited = m.entities.stream().map(x -> "            \"com.admintemplate." + m.name + "." + x.className + "\",\n").collect(Collectors.joining());
        replace(main.resolve("audit/AuditedEntities.java"), "    public static final Set<String> NOT_AUDITED = Set.of(\n",
            "    public static final Set<String> NOT_AUDITED = Set.of(\n" + notAudited);
        for (String lang : List.of("en", "fr")) {
            Path f = b.resolve("src/main/resources/i18n/ui/" + lang + ".json");
            ObjectNode ui = (ObjectNode) Json.MAPPER.readTree(f.toFile());
            if (!m.menuSection.isEmpty()) for (AdminTemplateModule.Entity x : m.entities) ((ObjectNode) ui.get("nav")).put(x.navKey, x.label);
            if (m.newPermission) ((ObjectNode) ui.get("permissionNames")).putObject(m.name).put("manage", "Manage " + m.name + " data");
            if (m.frontend) ui.setAll((ObjectNode) Json.MAPPER.valueToTree(m.uiTexts()));
            Files.writeString(f, Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(ui), StandardCharsets.UTF_8);
        }
    }

    private static void replace(Path file, String find, String with) throws IOException {
        String s = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(s).as("the instructions expect %s in %s", find, file).contains(find);
        Files.writeString(file, s.replace(find, with), StandardCharsets.UTF_8);
    }
}
