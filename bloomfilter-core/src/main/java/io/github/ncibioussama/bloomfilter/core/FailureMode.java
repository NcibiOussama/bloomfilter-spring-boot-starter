package io.github.ncibioussama.bloomfilter.core;

/**
 * What a remote backend does when it is unavailable.
 */
public enum FailureMode {

    /**
     * {@code mightContain} returns {@code true} and {@code put} is skipped. The application keeps working
     * by falling back to its source of truth: slower, but never a wrong "not found". This is the default.
     */
    FAIL_OPEN,

    /**
     * Throw {@link BloomFilterUnavailableException}.
     */
    THROW
}
