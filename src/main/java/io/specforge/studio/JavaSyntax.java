package io.specforge.studio;

import com.sun.source.util.JavacTask;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * STU-5: syntax check of rendered Java with the JDK's own parser (no type checking, no classpath needed). Running
 * on a JRE without the compiler module, the check is reported as unavailable instead of failing.
 */
public final class JavaSyntax {

    private JavaSyntax() {}

    /** Syntax errors as {line, column, message}; null when no Java compiler is available. */
    public static List<Map<String, Object>> check(String fileName, String source) {
        JavaCompiler compiler;
        try {
            compiler = ToolProvider.getSystemJavaCompiler();
        } catch (Throwable e) {
            return null;
        }
        if (compiler == null) return null;
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///" + fileName.replaceAll("[^A-Za-z0-9_.]", "_")), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            JavacTask task = (JavacTask) compiler.getTask(null, null, diagnostics, List.of("-proc:none"), null, List.of(file));
            task.parse();
        } catch (Exception e) {
            return null;
        }
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() != Diagnostic.Kind.ERROR) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("line", d.getLineNumber());
            m.put("column", d.getColumnNumber());
            m.put("message", d.getMessage(Locale.ENGLISH));
            out.add(m);
        }
        return out;
    }
}
