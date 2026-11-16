package io.github.ncibioussama.bloomfilter.core;

import java.time.Duration;

/**
 * A filter that can be rebuilt from scratch and swapped in atomically, without a window in which
 * concurrent {@link BloomFilter#put(Object) puts} are lost.
 * <p>
 * Protocol:
 * <ol>
 *     <li>{@link #beginRebuild(Duration)} creates an empty target. From now on every {@code put} on this
 *     filter is written to both the current filter and the target (dual-write).</li>
 *     <li>The caller adds every existing key to {@link RebuildSession#target()}.</li>
 *     <li>{@link RebuildSession#commit()} atomically replaces the current content with the target.</li>
 * </ol>
 * Checks keep using the current content until the commit, so answers stay correct during the rebuild.
 * Rebuilding removes keys that no longer exist in the source of truth.
 */
public interface Rebuildable {

    /**
     * Starts a rebuild.
     *
     * @param maxDuration upper bound for the rebuild; distributed backends release their lock after it,
     *                    and a commit after that point fails instead of risking lost writes
     * @return the session, or {@code null} if a rebuild of this filter is already running (possibly on
     * another application instance)
     */
    RebuildSession beginRebuild(Duration maxDuration);
}
