package io.specforge.mapper;

import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;

import java.util.*;

/** Helpers for reading swagger-parser's schema model (3.0 and 3.1 flavours). */
final class SchemaUtil {
    private SchemaUtil() {}

    /** Merged view of an object schema: own properties plus everything pulled in via allOf / oneOf / anyOf. */
    static final class View {
        final LinkedHashMap<String, Schema<?>> props = new LinkedHashMap<>();
        final Set<String> required = new LinkedHashSet<>();
        String description = "";
        boolean unionUsed;        // oneOf / anyOf were merged
        boolean discriminator;    // a discriminator was present
        final List<String> parents = new ArrayList<>();
    }

    interface Resolver {
        Schema<?> component(String name);
    }

    static String refName(String ref) {
        if (ref == null) return null;
        int i = ref.lastIndexOf('/');
        String n = i >= 0 ? ref.substring(i + 1) : ref;
        return n.replace("~1", "/").replace("~0", "~");
    }

    static String typeOf(Schema<?> s) {
        if (s == null) return null;
        if (s.getType() != null) return s.getType();
        Set<String> types = s.getTypes();
        if (types != null) {
            for (String t : types) if (!"null".equals(t)) return t;
        }
        if (s.getProperties() != null && !s.getProperties().isEmpty()) return "object";
        if (items(s) != null) return "array";
        return null;
    }

    static Schema<?> items(Schema<?> s) {
        if (s instanceof ArraySchema a) return a.getItems();
        return s == null ? null : s.getItems();
    }

    static boolean isEnum(Schema<?> s) {
        return s != null && s.getEnum() != null && !s.getEnum().isEmpty() && !"array".equals(typeOf(s))
            && !"object".equals(typeOf(s));
    }

    static List<Schema> allOf(Schema<?> s) {
        return s == null || s.getAllOf() == null ? List.of() : s.getAllOf();
    }

    static List<Schema> oneOf(Schema<?> s) {
        return s == null || s.getOneOf() == null ? List.of() : s.getOneOf();
    }

    static List<Schema> anyOf(Schema<?> s) {
        return s == null || s.getAnyOf() == null ? List.of() : s.getAnyOf();
    }

    static boolean isNullSchema(Schema<?> s) {
        if (s == null) return false;
        if ("null".equals(s.getType())) return true;
        Set<String> t = s.getTypes();
        return t != null && t.size() == 1 && t.contains("null");
    }

    static boolean hasComposition(Schema<?> s) {
        return !allOf(s).isEmpty() || !oneOf(s).isEmpty() || !anyOf(s).isEmpty();
    }

    /** Follow $ref chains to the underlying schema (returns the argument if it is not a ref). */
    static Schema<?> deref(Schema<?> s, Resolver r) {
        int guard = 0;
        while (s != null && s.get$ref() != null && guard++ < 20) {
            Schema<?> c = r.component(refName(s.get$ref()));
            if (c == null) return s;
            s = c;
        }
        return s;
    }

    /** True when the schema (after dereferencing) is an object-like thing that deserves its own class. */
    static boolean isObjectLike(Schema<?> s, Resolver r) {
        s = deref(s, r);
        if (s == null || isEnum(s)) return false;
        String t = typeOf(s);
        if ("array".equals(t)) return false;
        if (s.getProperties() != null && !s.getProperties().isEmpty()) return true;
        if (hasComposition(s)) {
            for (Schema m : allOf(s)) if (isObjectLike(m, r)) return true;
            for (Schema m : oneOf(s)) if (isObjectLike(m, r)) return true;
            for (Schema m : anyOf(s)) if (isObjectLike(m, r)) return true;
            return false;
        }
        return false;
    }

    static View view(Schema<?> s, Resolver r) {
        View v = new View();
        merge(s, r, v, new HashSet<>(), 0, true);
        return v;
    }

    private static void merge(Schema<?> s, Resolver r, View v, Set<Schema<?>> seen, int depth, boolean top) {
        if (s == null || depth > 25) return;
        Schema<?> d = deref(s, r);
        if (d == null || !seen.add(d)) return;
        if (top || v.description.isEmpty()) {
            if (d.getDescription() != null && v.description.isEmpty()) v.description = d.getDescription();
        }
        if (d.getDiscriminator() != null) v.discriminator = true;
        for (Schema m : allOf(d)) {
            if (m.get$ref() != null) v.parents.add(refName(m.get$ref()));
            merge(m, r, v, seen, depth + 1, false);
        }
        if (d.getProperties() != null) {
            for (Map.Entry<String, Schema> e : d.getProperties().entrySet()) v.props.putIfAbsent(e.getKey(), e.getValue());
        }
        if (d.getRequired() != null) v.required.addAll(d.getRequired());
        boolean union = false;
        for (Schema m : oneOf(d)) {
            if (isNullSchema(m)) continue;
            union = true;
            mergeOptional(m, r, v, seen, depth);
        }
        for (Schema m : anyOf(d)) {
            if (isNullSchema(m)) continue;
            union = true;
            mergeOptional(m, r, v, seen, depth);
        }
        if (union) v.unionUsed = true;
    }

    /** Union members contribute their properties, but never as required ones. */
    private static void mergeOptional(Schema<?> m, Resolver r, View v, Set<Schema<?>> seen, int depth) {
        View sub = new View();
        merge(m, r, sub, new HashSet<>(seen), depth + 1, false);
        for (Map.Entry<String, Schema<?>> e : sub.props.entrySet()) v.props.putIfAbsent(e.getKey(), e.getValue());
        if (sub.discriminator) v.discriminator = true;
    }
}
