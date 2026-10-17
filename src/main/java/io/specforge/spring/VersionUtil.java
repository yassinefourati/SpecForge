package io.specforge.spring;

import java.util.ArrayList;
import java.util.List;

/** Minimal Maven/Spring version handling: numeric parts compare numerically, qualifiers sort before GA. */
public final class VersionUtil {
    private VersionUtil() {}

    public static int major(String v) {
        List<Integer> p = parts(v);
        return p.isEmpty() ? 0 : p.get(0);
    }

    /** "4.1.1" → "4.1". */
    public static String minorLine(String v) {
        List<Integer> p = parts(v);
        return p.size() < 2 ? String.valueOf(major(v)) : p.get(0) + "." + p.get(1);
    }

    /** GA releases only: no SNAPSHOT, milestone or release-candidate qualifier. */
    public static boolean isGa(String v) {
        return v != null && v.matches("\\d+\\.\\d+\\.\\d+(\\.RELEASE)?");
    }

    public static int compare(String a, String b) {
        List<Integer> x = parts(a), y = parts(b);
        for (int i = 0; i < Math.max(x.size(), y.size()); i++) {
            int c = Integer.compare(i < x.size() ? x.get(i) : 0, i < y.size() ? y.get(i) : 0);
            if (c != 0) return c;
        }
        boolean ga = isGa(a), gb = isGa(b);
        return ga == gb ? 0 : ga ? 1 : -1;
    }

    /**
     * Checks a Maven-style range such as "[3.2.0,4.2.0)" or "3.5.0" (meaning "at least"). An empty range matches all.
     */
    public static boolean inRange(String version, String range) {
        if (range == null || range.isBlank()) return true;
        String r = range.trim();
        if (!r.startsWith("[") && !r.startsWith("(")) return compare(version, r) >= 0;
        boolean lowInc = r.startsWith("["), highInc = r.endsWith("]");
        String[] b = r.substring(1, r.length() - 1).split(",", -1);
        String lo = b[0].trim(), hi = b.length > 1 ? b[1].trim() : "";
        if (!lo.isEmpty()) {
            int c = compare(version, lo);
            if (c < 0 || (c == 0 && !lowInc)) return false;
        }
        if (!hi.isEmpty()) {
            int c = compare(version, hi);
            if (c > 0 || (c == 0 && !highInc)) return false;
        }
        return true;
    }

    private static List<Integer> parts(String v) {
        List<Integer> out = new ArrayList<>();
        if (v == null) return out;
        for (String s : v.split("[.-]")) {
            if (!s.matches("\\d+")) break;
            out.add(Integer.parseInt(s));
        }
        return out;
    }
}
