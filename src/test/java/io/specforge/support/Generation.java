package io.specforge.support;

import io.specforge.config.GeneratorConfig;
import io.specforge.gen.GeneratedFile;
import io.specforge.gen.Generator;
import io.specforge.gen.TemplateManager;
import io.specforge.mapper.OpenApiMapper;
import io.specforge.model.Model.ApiModel;
import io.specforge.spec.SpecLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Runs the real generation pipeline the way the web API does, for tests. */
public final class Generation {

    private Generation() {}

    public static GeneratorConfig config(Path target) {
        GeneratorConfig cfg = GeneratorConfig.defaults();
        cfg.targetDir = target.toString();
        return cfg;
    }

    /** Applies a named style preset (GeneratorConfig.PRESETS), as the UI does. */
    public static void style(GeneratorConfig cfg, String preset) {
        GeneratorConfig.Style p = GeneratorConfig.PRESETS.get(preset);
        if (p == null) throw new IllegalArgumentException("Unknown style preset " + preset + "; known: " + GeneratorConfig.PRESETS.keySet());
        cfg.style = new GeneratorConfig.Style(p.errorFormat, p.dtoSplit);
    }

    public static ApiModel mapSpec(Path spec, GeneratorConfig cfg) throws Exception {
        SpecLoader.Loaded l = new SpecLoader().fromText(Files.readString(spec), spec.getFileName().toString());
        return new OpenApiMapper(l.api, cfg, Map.of()).sourceUrl(l.source).map();
    }

    public static List<GeneratedFile> generate(ApiModel model, GeneratorConfig cfg) {
        return new Generator(new TemplateManager(null)).generate(model, cfg).files;
    }

    /** Relative path → content, sorted, line endings normalized. */
    public static Map<String, String> byPath(List<GeneratedFile> files) {
        Map<String, String> m = new TreeMap<>();
        for (GeneratedFile f : files) m.put(f.path, f.content.replace("\r\n", "\n"));
        return m;
    }
}
