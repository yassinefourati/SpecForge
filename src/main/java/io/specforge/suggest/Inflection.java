package io.specforge.suggest;

import io.specforge.util.Names;
import org.atteo.evo.inflector.English;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Singular class names and plural collection / path names (MAP-10). Plurals are formed from the singular, so an
 * already-plural table name never gets a second plural ("roles" → Role → roles, never "roleses").
 */
public final class Inflection {

    private static final Map<String, String> IRREGULAR_SINGULAR = Map.ofEntries(
        Map.entry("people", "person"), Map.entry("children", "child"), Map.entry("men", "man"), Map.entry("women", "woman"),
        Map.entry("mice", "mouse"), Map.entry("geese", "goose"), Map.entry("teeth", "tooth"), Map.entry("feet", "foot"),
        Map.entry("indices", "index"), Map.entry("matrices", "matrix"), Map.entry("vertices", "vertex"),
        Map.entry("analyses", "analysis"), Map.entry("criteria", "criterion"), Map.entry("phenomena", "phenomenon"),
        Map.entry("statuses", "status"), Map.entry("addresses", "address"), Map.entry("aliases", "alias"),
        Map.entry("buses", "bus"), Map.entry("quizzes", "quiz"), Map.entry("movies", "movie"), Map.entry("series", "series"),
        Map.entry("species", "species"), Map.entry("news", "news"), Map.entry("data", "data"), Map.entry("metadata", "metadata"));

    private static final Map<String, String> IRREGULAR_PLURAL = Map.ofEntries(
        Map.entry("person", "people"), Map.entry("child", "children"), Map.entry("man", "men"), Map.entry("woman", "women"),
        Map.entry("mouse", "mice"), Map.entry("goose", "geese"), Map.entry("tooth", "teeth"), Map.entry("foot", "feet"),
        Map.entry("analysis", "analyses"), Map.entry("criterion", "criteria"), Map.entry("phenomenon", "phenomena"));

    private Inflection() {}

    /** "user_roles" → "user_role", "categories" → "category", "people" → "person". */
    public static String singular(String name) {
        // Oracle, Db2 and H2 store unquoted names in uppercase: inflect them as words, not as acronyms
        if (name != null && name.equals(name.toUpperCase(Locale.ROOT))) name = name.toLowerCase(Locale.ROOT);
        List<String> words = Names.words(name);
        if (words.isEmpty()) return name;
        String last = words.get(words.size() - 1);
        String lower = last.toLowerCase(Locale.ROOT);
        String single = IRREGULAR_SINGULAR.getOrDefault(lower, Names.singular(last));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.size() - 1; i++) sb.append(words.get(i)).append('_');
        return sb.append(single).toString();
    }

    /** Plural of the singular form: "role" → "roles", "person" → "people", "status" → "statuses". */
    public static String plural(String name) {
        String s = singular(name);
        List<String> words = Names.words(s);
        if (words.isEmpty()) return name;
        String last = words.get(words.size() - 1).toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.size() - 1; i++) sb.append(words.get(i)).append('_');
        return sb.append(IRREGULAR_PLURAL.getOrDefault(last, English.plural(last))).toString();
    }
}
