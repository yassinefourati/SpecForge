package io.specforge.util;

import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-processes generated Java: de-duplicates and sorts imports, drops same-package and java.lang imports,
 * collapses runs of blank lines. Lets templates stay simple (they may list imports freely).
 */
public final class JavaTidy {
    private JavaTidy() {}

    private static final Pattern IMPORT = Pattern.compile("^import\\s+(static\\s+)?([\\w.]+(?:\\.\\*)?)\\s*;\\s*$");

    public static String tidy(String src, String pkg) {
        String[] lines = src.replace("\r\n", "\n").split("\n", -1);
        int i = 0;
        StringBuilder sb = new StringBuilder();
        // 1) everything up to and including the package declaration
        boolean sawPackage = false;
        while (i < lines.length) {
            String t = lines[i].trim();
            sb.append(lines[i]).append('\n');
            i++;
            if (t.startsWith("package ")) {
                sawPackage = true;
                break;
            }
        }
        if (!sawPackage) return src; // not a normal compilation unit; leave untouched
        // 2) import block (blank lines allowed between imports)
        TreeSet<String> imports = new TreeSet<>();
        TreeSet<String> statics = new TreeSet<>();
        while (i < lines.length) {
            String t = lines[i].trim();
            if (t.isEmpty()) { i++; continue; }
            Matcher m = IMPORT.matcher(t);
            if (!m.matches()) break;
            String name = m.group(2);
            if (m.group(1) != null) statics.add(name);
            else if (!dropImport(name, pkg)) imports.add(name);
            i++;
        }
        StringBuilder rest = new StringBuilder();
        for (int k = i; k < lines.length; k++) rest.append(lines[k]).append('\n');
        String restText = rest.toString();
        imports.removeIf(imp -> !imp.endsWith(".*") && !usedIn(restText, imp.substring(imp.lastIndexOf('.') + 1)));
        trimTrailingBlank(sb);
        sb.append("\n\n");
        if (!imports.isEmpty() || !statics.isEmpty()) {
            writeGroup(sb, imports, g -> g.startsWith("java."));
            writeGroup(sb, imports, g -> g.startsWith("javax.") || g.startsWith("jakarta."));
            writeGroup(sb, imports, g -> !g.startsWith("java.") && !g.startsWith("javax.") && !g.startsWith("jakarta."));
            if (!statics.isEmpty()) {
                for (String s : statics) sb.append("import static ").append(s).append(";\n");
                sb.append('\n');
            }
        }
        // 3) the rest
        for (; i < lines.length; i++) sb.append(lines[i]).append('\n');
        String out = sb.toString().replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n");
        return out.stripTrailing() + "\n";
    }

    private static boolean usedIn(String text, String simpleName) {
        return Pattern.compile("(?<![\\w.])" + Pattern.quote(simpleName) + "(?![\\w])").matcher(text).find();
    }

    private static void writeGroup(StringBuilder sb, TreeSet<String> imports, Predicate<String> p) {
        boolean any = false;
        for (String s : imports) {
            if (p.test(s)) {
                sb.append("import ").append(s).append(";\n");
                any = true;
            }
        }
        if (any) sb.append('\n');
    }

    private static void trimTrailingBlank(StringBuilder sb) {
        int n = sb.length();
        while (n > 0 && Character.isWhitespace(sb.charAt(n - 1))) n--;
        sb.setLength(n);
    }

    private static boolean dropImport(String name, String pkg) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return true;
        String p = name.substring(0, dot);
        return p.equals("java.lang") || p.equals(pkg);
    }
}
