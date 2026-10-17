package io.specforge.gen;

import io.specforge.config.GeneratorConfig;
import io.specforge.spring.SpringMetadata;
import io.specforge.spring.SpringMetadataSource;
import io.specforge.support.Generation;
import io.specforge.support.Maven;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2: generated projects compile on the latest patch of every supported Boot line, for every sample spec and mode.
 * Nightly: run with -Dspecforge.matrix=true (needs Maven and network access for dependencies on first run).
 */
@EnabledIfSystemProperty(named = "specforge.matrix", matches = "true")
class BootMatrixIT {

    private static final String[][] SPECS = {
        {"petstore", "samples/petstore.yaml"},
        {"gitea", "src/test/resources/specs/gitea.json"},
    };
    private static final String[][] MODES = {
        {"database", "DATABASE", "FORWARD"},
        {"proxy-forward", "PROXY", "FORWARD"},
        {"proxy-account", "PROXY", "SERVICE_ACCOUNT"},
    };

    @TestFactory
    List<DynamicTest> everySpecAndModeCompilesOnEveryBootLine() throws Exception {
        Path root = Files.createTempDirectory("specforge-matrix");
        List<DynamicTest> tests = new ArrayList<>();
        for (SpringMetadata.BootLine line : SpringMetadataSource.current().lines) {
            for (String[] spec : SPECS) {
                for (String[] mode : MODES) {
                    for (boolean lombok : new boolean[] {false, true}) {
                        if (lombok && !spec[0].equals("petstore")) continue;
                        String name = line.version + " " + spec[0] + " " + mode[0] + (lombok ? " lombok" : "");
                        tests.add(DynamicTest.dynamicTest(name, () -> {
                            Path dir = root.resolve(name.replace(' ', '-'));
                            GeneratorConfig cfg = Generation.config(dir);
                            cfg.options.springBootVersion = line.version;
                            cfg.options.serviceMode = mode[1];
                            cfg.security.upstreamAuth = mode[2];
                            cfg.options.lombok = lombok;
                            Maven.write(Generation.generate(Generation.mapSpec(Path.of(spec[1]), cfg), cfg));
                            Maven.Result r = Maven.run(dir, "-DskipTests", "compile");
                            assertThat(r.ok()).as("%s does not compile:%n%s", name, r.errors()).isTrue();
                        }));
                    }
                }
            }
        }
        return tests;
    }
}
