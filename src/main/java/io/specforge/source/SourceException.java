package io.specforge.source;

/**
 * A failure the user can act on, with a code from the catalog in the phase document (Appendix C). The message
 * MUST already be redacted (SEC-5) when it can contain driver text.
 */
public class SourceException extends RuntimeException {
    public final String code;
    public final String hint;
    public final String action;

    public SourceException(String code, String message, String hint, String action) {
        super(message);
        this.code = code;
        this.hint = hint == null ? "" : hint;
        this.action = action == null ? "" : action;
    }
}
