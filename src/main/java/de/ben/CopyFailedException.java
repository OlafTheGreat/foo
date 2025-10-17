package de.ben;

/**
 * Unchecked exception representing a copy failure with contextual information.
 */
public class CopyFailedException extends RuntimeException {
    public CopyFailedException() {
        super();
    }

    public CopyFailedException(String message) {
        super(message);
    }

    public CopyFailedException(String message, Throwable cause) {
        super(message, cause);
    }

    public CopyFailedException(Throwable cause) {
        super(cause);
    }
}

