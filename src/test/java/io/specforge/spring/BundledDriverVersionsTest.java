package io.specforge.spring;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** VER-4 / ADR 002: SpecForge bundles the driver versions of the default Spring Boot line's BOM. */
class BundledDriverVersionsTest {

    @Test
    void bundledDriverVersionsMatchTheSpringBootBom() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        Map<String, String> bom = SpringMetadataSource.current().bomDriverVersions;
        assertThat(bom).as("bundled metadata lacks BOM driver versions; refresh spring/metadata.json").isNotEmpty();
        Matcher m = Pattern.compile("<([a-z0-9-]+\\.version)>([^<]+)</").matcher(pom);
        int checked = 0;
        while (m.find()) {
            String expected = bom.get(m.group(1));
            if (expected == null) continue;
            assertThat(m.group(2)).as("pom.xml %s must equal the Spring Boot %s BOM", m.group(1), SpringMetadataSource.current().bootDefault)
                .isEqualTo(expected);
            checked++;
        }
        assertThat(checked).as("no bundled driver version found in pom.xml").isPositive();
    }
}
