package io.specforge.support;

import io.specforge.gen.GeneratedFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Builds generated projects with the local Maven installation, for tests that prove generated code compiles. */
public final class Maven {

    private Maven() {}

    public record Result(int exitCode, String output) {
        public boolean ok() {
            return exitCode == 0;
        }

        /** The compiler errors only, for readable assertion messages. */
        public String errors() {
            StringBuilder sb = new StringBuilder();
            for (String line : output.split("\\R")) if (line.contains("[ERROR]")) sb.append(line).append('\n');
            return sb.length() == 0 ? output : sb.toString();
        }
    }

    public static void write(List<GeneratedFile> files) throws IOException {
        for (GeneratedFile f : files) {
            Path p = Path.of(f.absPath);
            Files.createDirectories(p.getParent());
            Files.writeString(p, f.content, StandardCharsets.UTF_8);
        }
    }

    public static Result run(Path project, String... goals) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        cmd.add(windows ? "mvn.cmd" : "mvn");
        cmd.add("-q");
        cmd.add("-B");
        cmd.addAll(List.of(goals));
        Path log = Files.createTempFile("specforge-mvn", ".log");
        Process p = new ProcessBuilder(cmd).directory(project.toFile()).redirectErrorStream(true)
            .redirectOutput(log.toFile()).start();
        if (!p.waitFor(15, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            return new Result(-1, "Maven timed out in " + project);
        }
        String out = Files.readString(log, StandardCharsets.UTF_8);
        Files.deleteIfExists(log);
        return new Result(p.exitValue(), out);
    }
}
