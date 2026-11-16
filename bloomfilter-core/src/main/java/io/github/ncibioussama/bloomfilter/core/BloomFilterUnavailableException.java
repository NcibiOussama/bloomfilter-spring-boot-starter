package io.github.ncibioussama.bloomfilter.core;

/**
 * Thrown when a Bloom filter backend cannot be reached and the filter uses {@link FailureMode#THROW}.
 */
public class BloomFilterUnavailableException extends RuntimeException {

    /**
     * @param message the detail message
     * @param cause   the backend error
     */
    public BloomFilterUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
