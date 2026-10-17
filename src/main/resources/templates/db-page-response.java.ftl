package [=pkg];

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * One page of a list operation. A stable JSON shape, independent of Spring Data's Page serialization.
 *
 * [=generatedNote]
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
