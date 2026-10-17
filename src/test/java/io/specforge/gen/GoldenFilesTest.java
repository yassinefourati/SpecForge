package io.specforge.gen;

import io.specforge.config.GeneratorConfig;
import io.specforge.support.Generation;
import io.specforge.support.Golden;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;

/** Spec-flow output must not change unintentionally (P2, S5). */
class GoldenFilesTest {

    @TempDir
    Path target;

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "petstore-default,         DATABASE, FORWARD,         ''",
        "petstore-proxy-forward,   PROXY,    FORWARD,         ''",
        "petstore-proxy-account,   PROXY,    SERVICE_ACCOUNT, ''",
        // a profile pinned to an older Boot version must keep producing the same output (P2)
        "petstore-boot-3.5.6,      DATABASE, FORWARD,         3.5.6",
        "petstore-boot-3.5.6-proxy,PROXY,    FORWARD,         3.5.6",
    })
    void specFlowOutputIsStable(String scenario, String serviceMode, String upstreamAuth, String bootVersion) throws Exception {
        GeneratorConfig cfg = Generation.config(target);
        cfg.options.serviceMode = serviceMode;
        cfg.security.upstreamAuth = upstreamAuth;
        if (!bootVersion.isEmpty()) cfg.options.springBootVersion = bootVersion;
        var model = Generation.mapSpec(Path.of("samples", "petstore.yaml"), cfg);
        Golden.assertMatches(scenario, Generation.byPath(Generation.generate(model, cfg)));
    }
}
