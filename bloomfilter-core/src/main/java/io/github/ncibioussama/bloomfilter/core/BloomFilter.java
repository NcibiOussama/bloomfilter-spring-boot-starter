package io.github.ncibioussama.bloomfilter.core;

/**
 * A probabilistic set membership structure.
 * <p>
 * {@link #mightContain(Object)} never returns {@code false} for a key that was {@link #put(Object) put}
 * (no false negatives), but may return {@code true} for a key that was never added (false positive),
 * with a probability close to {@link BloomFilterSpec#falsePositiveRate()} as long as the filter is not
 * filled beyond {@link BloomFilterSpec#expectedInsertions()}.
 * <p>
 * Keys are converted to bytes by a {@link KeyEncoder}. With the default encoder, {@code 42L},
 * {@code 42} and {@code "42"} are the same key.
 * <p>
 * Implementations are thread-safe.
 */
public interface BloomFilter {

    /**
     * @return the unique name of this filter
     */
    default String name() {
        return spec().name();
    }

    /**
     * @param key the key to test, must not be {@code null}
     * @return {@code false} if the key was definitely never added, {@code true} if it probably was
     */
    boolean mightContain(Object key);

    /**
     * Adds a key.
     *
     * @param key the key to add, must not be {@code null}
     * @return {@code true} if the filter changed, which means the key was definitely not present before
     */
    boolean put(Object key);

    /**
     * Adds many keys. Backends may batch this into fewer network round-trips.
     *
     * @param keys the keys to add
     * @return number of keys that changed the filter (were definitely new)
     */
    default long putAll(Iterable<?> keys) {
        long added = 0;
        for (Object key : keys) {
            if (put(key)) {
                added++;
            }
        }
        return added;
    }

    /**
     * @return the sizing of this filter
     */
    BloomFilterSpec spec();

    /**
     * Computes current statistics. May require a network call for remote backends.
     *
     * @return a snapshot of the filter's statistics
     */
    BloomFilterStats stats();

    /**
     * @return {@code true} if no key has been added yet
     */
    default boolean isEmpty() {
        return stats().bitsSet() == 0;
    }

    /**
     * Whether the filter was completely loaded with all existing keys (see {@link #markPopulated()}).
     * A shared filter can be non-empty while another instance is still loading it, so this, not
     * {@link #isEmpty()}, decides whether loading can be skipped.
     *
     * @return {@code true} if a complete load or rebuild has finished
     */
    default boolean isPopulated() {
        return !isEmpty();
    }

    /**
     * Records that every existing key has been added. Persistent backends store this marker.
     */
    default void markPopulated() {
    }

    /**
     * Removes all keys.
     */
    void clear();

    /**
     * Signals that {@link #mightContain(Object)} returned {@code true} but the source of truth
     * proved the key absent. Used for metrics only.
     */
    default void recordFalsePositive() {
    }

    /**
     * @return number of backend failures (for example Redis unavailable) since startup
     */
    default long backendErrors() {
        return 0;
    }
}
