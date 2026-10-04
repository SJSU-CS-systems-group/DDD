package net.discdd.dropbox.control;

/**
 * Typed failure of {@link DropboxControlCodec}. Receivers use {@link #getKind()} to decide what to do: the
 * design says an unknown {@code type} is logged and dropped, and every other kind means the ADU is unusable.
 */
public class DropboxControlException extends Exception {
    private static final long serialVersionUID = 1L;

    /** What went wrong. */
    public enum Kind {
        /** The encoded message exceeds {@link DropboxControl#MAX_MESSAGE_BYTES}, or a list exceeds its entry
         *  bound. */
        TOO_LARGE,
        /** Not a JSON object, or a field has the wrong JSON type. */
        MALFORMED,
        /** {@code "type"} is missing or not one this code knows. */
        UNKNOWN_TYPE,
        /** {@code "v"} is missing or not {@link DropboxControl#VERSION}. */
        UNSUPPORTED_VERSION,
        /** A known, well-formed message of a type the caller did not ask for (see
         *  {@link DropboxControlCodec#decodeInventory} and {@link DropboxControlCodec#decodePlan}). */
        UNEXPECTED_TYPE,
        /** Well-formed JSON whose values break the contract (bad bundle ID, negative size, missing seq...). */
        INVALID
    }

    private final Kind kind;

    public DropboxControlException(Kind kind, String message) {
        super(kind + ": " + message);
        this.kind = kind;
    }

    public DropboxControlException(Kind kind, String message, Throwable cause) {
        super(kind + ": " + message, cause);
        this.kind = kind;
    }

    /** @return the failure category */
    public Kind getKind() {
        return kind;
    }
}
