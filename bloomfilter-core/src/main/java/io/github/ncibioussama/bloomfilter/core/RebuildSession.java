package io.github.ncibioussama.bloomfilter.core;

/**
 * A running rebuild. See {@link Rebuildable}.
 */
public interface RebuildSession {

    /**
     * The empty filter being built. Add every existing key to it. Operations on the target throw
     * {@link BloomFilterUnavailableException} on backend failures instead of failing open, so an
     * incomplete target can never be committed silently.
     *
     * @return the target filter
     */
    BloomFilter target();

    /**
     * Atomically replaces the filter's content with the target and ends the session.
     *
     * @return {@code true} if swapped; {@code false} if the session was no longer valid (aborted, or its
     * distributed lock expired), in which case nothing changed
     */
    boolean commit();

    /**
     * Discards the target and ends the session. The filter keeps its current content.
     */
    void abort();
}
