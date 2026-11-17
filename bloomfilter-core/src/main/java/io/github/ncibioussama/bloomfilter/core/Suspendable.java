package io.github.ncibioussama.bloomfilter.core;

/**
 * A filter that can be told to stop trusting its content, for example while a SQL migration, a bulk import or
 * another service inserts rows without going through the filter.
 * <p>
 * While suspended, {@link BloomFilter#mightContain(Object)} answers {@code true} for every key, so callers use
 * their source of truth. Writes still go to the filter. For a shared backend the suspension is shared too: every
 * application instance bypasses the filter as soon as {@link #suspend()} returns.
 * <p>
 * Rebuilds do not end a suspension on their own (a scheduled rebuild could otherwise finish in the middle of the
 * migration). Only {@link #resume(String)} does, with the token of the suspension it ends, so a newer suspension
 * started in the meantime stays in place.
 */
public interface Suspendable {

    /**
     * Suspends the filter. Replaces any current suspension.
     *
     * @return a token identifying this suspension, to pass to {@link #resume(String)}
     * @throws BloomFilterUnavailableException if the suspension could not be recorded
     */
    String suspend();

    /**
     * @return the token of the current suspension, or {@code null} if the filter is not suspended
     * @throws BloomFilterUnavailableException if the backend cannot be reached
     */
    String suspension();

    /**
     * Ends the suspension identified by {@code token}. Does nothing if the filter was suspended again since.
     *
     * @param token the token returned by {@link #suspend()} or {@link #suspension()}
     * @return {@code true} if the suspension ended
     * @throws BloomFilterUnavailableException if the backend cannot be reached
     */
    boolean resume(String token);
}
