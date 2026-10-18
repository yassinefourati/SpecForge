package [=pkg];

import java.util.List;
import java.util.function.Function;

/**
 * One page of results, without Spring.
 *
 * [=generatedNote]
 */
public record PageResult<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public <R> PageResult<R> map(Function<T, R> mapper) {
        return new PageResult<>(content.stream().map(mapper).toList(), page, size, totalElements, totalPages);
    }
}
