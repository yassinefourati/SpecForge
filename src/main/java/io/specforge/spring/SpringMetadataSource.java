package io.specforge.spring;

import io.specforge.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Where generation gets Spring metadata. R1 serves the snapshot bundled in the JAR (SYNC-4), so generation never
 * depends on the network (P7); a cached live refresh (SYNC-3) comes in R2.
 */
public final class SpringMetadataSource {

    private static final String BUNDLED = "/spring/metadata.json";
    private static volatile SpringMetadata bundled;

    private SpringMetadataSource() {}

    public static SpringMetadata current() {
        SpringMetadata m = bundled;
        if (m == null) {
            synchronized (SpringMetadataSource.class) {
                if (bundled == null) bundled = load();
                m = bundled;
            }
        }
        return m;
    }

    private static SpringMetadata load() {
        try (InputStream in = SpringMetadataSource.class.getResourceAsStream(BUNDLED)) {
            if (in == null) throw new IllegalStateException("The bundled Spring metadata " + BUNDLED + " is missing from the JAR.");
            SpringMetadata m = Json.MAPPER.readValue(in, SpringMetadata.class);
            if (m.lines.isEmpty()) throw new IllegalStateException("The bundled Spring metadata lists no Spring Boot version.");
            return m;
        } catch (IOException e) {
            throw new UncheckedIOException("The bundled Spring metadata cannot be read: " + e.getMessage(), e);
        }
    }
}
