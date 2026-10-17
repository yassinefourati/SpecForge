package io.specforge.util;

import java.util.*;
import java.util.regex.Pattern;

/** Naming helpers: case conversion, pluralisation, Java identifier safety. */
public final class Names {
    private Names() {}

    public static final Set<String> JAVA_KEYWORDS = Set.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
        "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
        "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
        "volatile", "while", "true", "false", "null", "var", "record", "yield", "_");

    /** Simple names that would collide with java.lang / annotations / Spring types used by the templates. */
    public static final Set<String> RESERVED_CLASS_NAMES = Set.of(
        "Object", "String", "Integer", "Long", "Short", "Byte", "Boolean", "Double", "Float", "Number",
        "Character", "Void", "Class", "Enum", "Error", "Exception", "Throwable", "Override", "Deprecated",
        "Record", "Math", "System", "Thread", "Process", "Runtime", "Package", "Module", "Iterable",
        "List", "Map", "Set", "Optional", "Collection", "Date", "UUID", "BigDecimal", "File", "Objects",
        "Entity", "Table", "Column", "Id", "Embedded", "Embeddable", "Version", "Index", "Lob", "Transient",
        "Enumerated", "Valid", "Size", "Min", "Max", "Pattern", "Email", "NotNull", "Service", "Repository",
        "Controller", "Component", "Configuration", "Bean", "Value", "Autowired", "Transactional",
        "ResponseEntity", "HttpStatus", "HttpMethod", "RequestBody", "RequestParam", "PathVariable", "RequestHeader", "CookieValue",
        "RequestPart", "GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping", "Validated", "PreAuthorize",
        "EnumType", "GeneratedValue", "GenerationType", "ElementCollection", "ManyToOne", "ManyToMany", "CascadeType", "JoinColumn",
        "JdbcTypeCode", "SqlTypes", "DecimalMin", "DecimalMax", "Getter", "Setter", "Builder", "NoArgsConstructor", "AllArgsConstructor",
        "JsonValue", "JsonCreator", "JpaRepository", "SecurityConfig", "ApiKeyAuthFilter", "ResourceNotFoundException", "ApiExceptionHandler",
        "RequestMapping", "RestController", "MultipartFile", "JsonProperty", "Application");

    private static final Pattern SPLIT = Pattern.compile(
        "(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|[^A-Za-z0-9]+");

    public static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String w : SPLIT.split(s)) if (!w.isEmpty()) out.add(w);
        return out;
    }

    public static String cap(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public static String decap(String s) {
        return s == null || s.isEmpty() ? "" : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    public static String pascal(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : words(s)) sb.append(cap(w.toLowerCase(Locale.ROOT)));
        return sb.toString();
    }

    public static String camel(String s) {
        List<String> ws = words(s);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ws.size(); i++) {
            String w = ws.get(i).toLowerCase(Locale.ROOT);
            sb.append(i == 0 ? w : cap(w));
        }
        return sb.toString();
    }

    public static String snake(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : words(s)) {
            if (sb.length() > 0) sb.append('_');
            sb.append(w.toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    public static String upperSnake(String s) {
        return snake(s).toUpperCase(Locale.ROOT);
    }

    /** A valid, non-keyword lower-camel Java identifier. */
    public static String javaIdent(String s) {
        String c = camel(s);
        if (c.isEmpty()) c = "value";
        if (Character.isDigit(c.charAt(0))) c = "_" + c;
        if (JAVA_KEYWORDS.contains(c)) c = c + "_";
        return c;
    }

    /** A valid upper-camel Java class name. */
    public static String className(String s) {
        String c = pascal(s);
        if (c.isEmpty()) c = "Model";
        if (Character.isDigit(c.charAt(0))) c = "Model" + c;
        return c;
    }

    public static String singular(String w) {
        if (w == null || w.length() < 3) return w == null ? "" : w;
        String l = w.toLowerCase(Locale.ROOT);
        if (l.endsWith("ies")) return w.substring(0, w.length() - 3) + "y";
        if (l.endsWith("sses") || l.endsWith("xes") || l.endsWith("ches") || l.endsWith("shes") || l.endsWith("zes"))
            return w.substring(0, w.length() - 2);
        if (l.endsWith("ss") || l.endsWith("us") || l.endsWith("is")) return w;
        if (l.endsWith("s")) return w.substring(0, w.length() - 1);
        return w;
    }

    public static String plural(String w) {
        if (w == null || w.isEmpty()) return "";
        String l = w.toLowerCase(Locale.ROOT);
        if (l.endsWith("s") && !l.endsWith("ss") && !l.endsWith("us") && !l.endsWith("is")) return w; // already plural-ish
        if (l.matches(".*[^aeiou]y$")) return w.substring(0, w.length() - 1) + "ies";
        if (l.endsWith("s") || l.endsWith("x") || l.endsWith("z") || l.endsWith("ch") || l.endsWith("sh")) return w + "es";
        return w + "s";
    }

    public static boolean isJavaIdentifier(String s) {
        if (s == null || s.isEmpty() || JAVA_KEYWORDS.contains(s)) return false;
        if (!Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        return true;
    }

    /** "" is allowed (means: the base package itself). */
    public static boolean isValidPackage(String p) {
        if (p == null || p.isEmpty()) return true;
        for (String part : p.split("\\.", -1)) if (!isJavaIdentifier(part)) return false;
        return true;
    }

    public static String escapeJava(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 32) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /** Makes arbitrary text safe inside a Javadoc comment (single line). */
    public static String doc(String s) {
        if (s == null) return "";
        String t = s.replace("*/", "*&#47;").replace("/*", "&#47;*").replace("\\u", "\\&#117;")
            .replaceAll("\\s+", " ").trim();
        return t.length() > 400 ? t.substring(0, 397) + "..." : t;
    }

    public static String slug(String s) {
        String sn = snake(s).replace('_', '-');
        return sn.isEmpty() ? "api" : sn;
    }
}
