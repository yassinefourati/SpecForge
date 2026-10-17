package io.specforge.secrets;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Every error shown or logged passes through here (SEC-5): known secrets and credential-looking parts of URLs are
 * removed, only the first line is kept, at most 300 characters.
 */
public final class Redactor {

    private static final Pattern URL_USERINFO = Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^\\s/@]+@");
    private static final Pattern KEY_VALUE = Pattern.compile("(?i)\\b(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?token)\\s*([=:])\\s*[^&;,\\s]+");
    private static final int MAX = 300;

    private Redactor() {}

    public static String redact(Throwable error, Collection<String> secrets) {
        String text = error == null ? "" : error.getMessage();
        if (text == null || text.isBlank()) text = error == null ? "" : error.getClass().getSimpleName();
        return redact(text, secrets);
    }

    public static String redact(String text, Collection<String> secrets) {
        if (text == null) return "";
        String t = text.strip();
        int nl = t.indexOf('\n');
        if (nl >= 0) t = t.substring(0, nl).strip();
        if (secrets != null) {
            String[] sorted = secrets.stream().filter(Objects::nonNull).filter(s -> s.length() >= 3)
                .sorted(Comparator.comparingInt(String::length).reversed()).toArray(String[]::new);
            for (String s : sorted) t = t.replace(s, "•••");
        }
        t = URL_USERINFO.matcher(t).replaceAll("$1•••@");
        t = KEY_VALUE.matcher(t).replaceAll("$1$2•••");
        return t.length() <= MAX ? t : t.substring(0, MAX - 1) + "…";
    }

    public static String redact(String text, String... secrets) {
        return redact(text, Arrays.asList(secrets));
    }
}
