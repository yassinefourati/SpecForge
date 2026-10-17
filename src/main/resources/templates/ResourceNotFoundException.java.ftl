package [=pkg];

/**
 * Thrown by services when a requested record does not exist; mapped to HTTP 404.
 *
 * [=generatedNote]
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
