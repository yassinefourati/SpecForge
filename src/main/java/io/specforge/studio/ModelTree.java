package io.specforge.studio;

import freemarker.template.TemplateMethodModelEx;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * STU-4: the template model of one sample as a tree the UI can browse; a click inserts {@code [=path]}. Depth and
 * list lengths are capped, cycles are cut; public no-argument methods are listed (templates can call them) but
 * never invoked here.
 */
public final class ModelTree {

    private static final int MAX_DEPTH = 6;
    private static final int MAX_ITEMS = 8;
    private static final int MAX_VALUE = 120;

    private ModelTree() {}

    public static List<Map<String, Object>> of(Map<String, Object> root) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : new TreeMap<>(root).entrySet()) {
            out.add(node(e.getKey(), e.getKey(), e.getValue(), 0, new IdentityHashMap<>()));
        }
        return out;
    }

    private static Map<String, Object> node(String name, String path, Object v, int depth, IdentityHashMap<Object, Boolean> seen) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("name", name);
        n.put("path", path);
        if (v == null) {
            n.put("type", "null");
            return n;
        }
        if (v instanceof TemplateMethodModelEx) {
            n.put("type", "function");
            n.put("path", path + "(text)");
            return n;
        }
        if (v instanceof CharSequence || v instanceof Number || v instanceof Boolean || v instanceof Enum<?>) {
            n.put("type", v instanceof CharSequence ? "string" : v instanceof Boolean ? "boolean" : v instanceof Number ? "number" : "string");
            String s = String.valueOf(v);
            n.put("value", s.length() > MAX_VALUE ? s.substring(0, MAX_VALUE) + "…" : s);
            return n;
        }
        if (depth >= MAX_DEPTH || seen.containsKey(v)) {
            n.put("type", v.getClass().getSimpleName());
            n.put("truncated", true);
            return n;
        }
        seen.put(v, true);
        List<Map<String, Object>> kids = new ArrayList<>();
        if (v instanceof Map<?, ?> m) {
            n.put("type", "hash (" + m.size() + ")");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (i++ >= MAX_ITEMS * 3) break;
                String k = String.valueOf(e.getKey());
                kids.add(node(k, path + (k.matches("[A-Za-z_][A-Za-z0-9_]*") ? "." + k : "[\"" + k + "\"]"), e.getValue(), depth + 1, seen));
            }
        } else if (v instanceof Collection<?> c) {
            n.put("type", "sequence (" + c.size() + ")");
            int i = 0;
            for (Object o : c) {
                if (i >= MAX_ITEMS) break;
                kids.add(node("[" + i + "]", path + "[" + i + "]", o, depth + 1, seen));
                i++;
            }
        } else if (v.getClass().getName().startsWith("io.specforge.")) {
            n.put("type", v.getClass().getSimpleName());
            for (Field f : v.getClass().getFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    kids.add(node(f.getName(), path + "." + f.getName(), f.get(v), depth + 1, seen));
                } catch (IllegalAccessException ignored) {
                    // public fields only
                }
            }
            for (Method m : v.getClass().getMethods()) {
                if (m.getDeclaringClass() == Object.class || Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0
                    || m.getName().startsWith("get") || m.getReturnType() == void.class) continue;
                Map<String, Object> k = new LinkedHashMap<>();
                k.put("name", m.getName() + "()");
                k.put("path", path + "." + m.getName() + "()");
                k.put("type", "method → " + m.getReturnType().getSimpleName());
                kids.add(k);
            }
        } else {
            n.put("type", v.getClass().getSimpleName());
            n.put("value", String.valueOf(v));
        }
        seen.remove(v);
        n.put("children", kids);
        return n;
    }
}
