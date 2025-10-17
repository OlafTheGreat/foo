package de.ben;

/**
 * Unchecked exception representing a copy failure with contextual information.
 */
public class CopyFailedException extends RuntimeException {
    public CopyFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
