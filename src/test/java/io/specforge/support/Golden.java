package io.specforge.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.fail;

/**
 * Compares generated output with files under src/test/resources/golden/&lt;scenario&gt;/.
 * Run with -DupdateGolden=true to rewrite them after a deliberate change (P2: add a changelog entry too).
 */
public final class Golden {

    private static final Path ROOT = Path.of("src", "test", "resources", "golden");

    private Golden() {}

    public static void assertMatches(String scenario, Map<String, String> actual) throws IOException {
        Path dir = ROOT.resolve(scenario);
        if (Boolean.getBoolean("updateGolden")) {
            write(dir, actual);
            return;
        }
        if (!Files.isDirectory(dir))
            fail("No golden files for '" + scenario + "'. Create them with -DupdateGolden=true and review the result.");
        Map<String, String> expected = read(dir);
        List<String> problems = new ArrayList<>();
        for (String p : expected.keySet()) if (!actual.containsKey(p)) problems.add("missing   " + p);
        for (String p : actual.keySet()) if (!expected.containsKey(p)) problems.add("unexpected " + p);
        for (Map.Entry<String, String> e : actual.entrySet()) {
            String want = expected.get(e.getKey());
            if (want != null && !want.equals(e.getValue())) problems.add("changed   " + e.getKey() + firstDifference(want, e.getValue()));
        }
        if (!problems.isEmpty())
            fail("Generated output for '" + scenario + "' differs from the golden files (rerun with -DupdateGolden=true if the change is deliberate):\n  "
                + String.join("\n  ", problems));
    }

    private static String firstDifference(String a, String b) {
        String[] x = a.split("\n", -1), y = b.split("\n", -1);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String l1 = i < x.length ? x[i] : "<end>", l2 = i < y.length ? y[i] : "<end>";
            if (!l1.equals(l2)) return " (line " + (i + 1) + ": expected [" + l1.strip() + "] got [" + l2.strip() + "])";
        }
        return "";
    }

    private static Map<String, String> read(Path dir) throws IOException {
        Map<String, String> m = new TreeMap<>();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path f : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator)
                m.put(dir.relativize(f).toString().replace('\\', '/'), Files.readString(f, StandardCharsets.UTF_8).replace("\r\n", "\n"));
        }
        return m;
    }

    private static void write(Path dir, Map<String, String> files) throws IOException {
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.walk(dir)) {
                s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> p.toFile().delete());
            }
        }
        for (Map.Entry<String, String> e : files.entrySet()) {
            Path f = dir.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue(), StandardCharsets.UTF_8);
        }
    }
}
