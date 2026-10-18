package [=pkg];

import java.util.List;

/**
 * A page request the ports understand, without Spring: page number (from 0), size, and the order by API property.
 *
 * [=generatedNote]
 */
public record PageQuery(int page, int size, List<Order> orders) {

    public record Order(String property, boolean ascending) {
    }
}
