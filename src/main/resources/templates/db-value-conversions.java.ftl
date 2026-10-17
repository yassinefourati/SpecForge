package [=pkg];

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Converts values from JSON bodies (PATCH) and query parameters (list filters) to column types.
 * A value that cannot be converted is a client error (400), never a server error.
 *
 * [=generatedNote]
 */
public final class ValueConversions {

    private ValueConversions() {
    }

    public static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    public static Integer asInteger(Object v) {
        return v == null ? null : v instanceof Number n ? Integer.valueOf(n.intValue()) : parse(v, Integer::valueOf);
    }

    public static Long asLong(Object v) {
        return v == null ? null : v instanceof Number n ? Long.valueOf(n.longValue()) : parse(v, Long::valueOf);
    }

    public static Short asShort(Object v) {
        return v == null ? null : v instanceof Number n ? Short.valueOf(n.shortValue()) : parse(v, Short::valueOf);
    }

    public static BigDecimal asBigDecimal(Object v) {
        return v == null ? null : v instanceof BigDecimal b ? b : parse(v, BigDecimal::new);
    }

    public static Double asDouble(Object v) {
        return v == null ? null : v instanceof Number n ? Double.valueOf(n.doubleValue()) : parse(v, Double::valueOf);
    }

    public static Float asFloat(Object v) {
        return v == null ? null : v instanceof Number n ? Float.valueOf(n.floatValue()) : parse(v, Float::valueOf);
    }

    public static Boolean asBoolean(Object v) {
        if (v == null || v instanceof Boolean) {
            return (Boolean) v;
        }
        String s = String.valueOf(v);
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) {
            return Boolean.valueOf(s);
        }
        throw invalid(v);
    }

    public static UUID asUuid(Object v) {
        return v == null ? null : parse(v, UUID::fromString);
    }

    public static LocalDate asLocalDate(Object v) {
        return v == null ? null : parse(v, LocalDate::parse);
    }

    public static LocalTime asLocalTime(Object v) {
        return v == null ? null : parse(v, LocalTime::parse);
    }

    public static OffsetTime asOffsetTime(Object v) {
        return v == null ? null : parse(v, OffsetTime::parse);
    }

    public static LocalDateTime asLocalDateTime(Object v) {
        return v == null ? null : parse(v, LocalDateTime::parse);
    }

    public static OffsetDateTime asOffsetDateTime(Object v) {
        return v == null ? null : parse(v, OffsetDateTime::parse);
    }

    /** Binary values travel as Base64 text, as Jackson writes byte[]. */
    public static byte[] asBytes(Object v) {
        return v == null ? null : parse(v, Base64.getDecoder()::decode);
    }

    public static List<?> asList(Object v) {
        if (v == null) {
            return List.of();
        }
        if (v instanceof Collection<?> c) {
            return List.copyOf(c);
        }
        throw invalid(v);
    }

    private static <T> T parse(Object v, Function<String, T> parser) {
        try {
            return parser.apply(String.valueOf(v).trim());
        } catch (RuntimeException e) {
            // NumberFormatException, DateTimeParseException, IllegalArgumentException: the client sent a bad value
            throw invalid(v);
        }
    }

    private static ResponseStatusException invalid(Object v) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid value: " + v);
    }
}
