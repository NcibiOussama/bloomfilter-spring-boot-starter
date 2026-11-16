package io.github.ncibioussama.bloomfilter.autoconfigure;

import io.github.ncibioussama.bloomfilter.core.BloomFilter;
import io.github.ncibioussama.bloomfilter.core.BloomFilterSpec;
import io.github.ncibioussama.bloomfilter.core.BloomFilterStats;
import java.time.Instant;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Wraps every configured filter to add counters and a safety state.
 * <p>
 * While a filter is {@link State#WARMING_UP} or {@link State#DEGRADED}, {@link #mightContain(Object)}
 * returns {@code true} without consulting the filter, so callers fall back to their source of truth
 * instead of receiving a wrong "absent" answer.
 */
public final class ManagedBloomFilter implements BloomFilter {

    private static final Log log = LogFactory.getLog(ManagedBloomFilter.class);

    /**
     * Lifecycle state.
     */
    public enum State {
        /** A loader has not finished yet: checks are bypassed. */
        WARMING_UP,
        /** Normal operation. */
        READY,
        /**
         * The filter may miss keys (a write was lost, or loading failed): checks are bypassed until a
         * successful rebuild.
         */
        DEGRADED
    }

    private final BloomFilter delegate;
    private final String backend;
    private volatile State state;

    private final LongAdder mightContain = new LongAdder();
    private final LongAdder absent = new LongAdder();
    private final LongAdder bypassed = new LongAdder();
    private final LongAdder puts = new LongAdder();
    private final LongAdder falsePositives = new LongAdder();
    private final LongAdder rebuildSuccesses = new LongAdder();
    private final LongAdder rebuildFailures = new LongAdder();
    private final AtomicBoolean rebuilding = new AtomicBoolean();
    private volatile Instant lastRebuild;

    ManagedBloomFilter(BloomFilter delegate, String backend, State initialState) {
        this.delegate = delegate;
        this.backend = backend;
        this.state = initialState;
    }

    @Override
    public boolean mightContain(Object key) {
        if (state != State.READY) {
            bypassed.increment();
            return true;
        }
        boolean result = delegate.mightContain(key);
        (result ? mightContain : absent).increment();
        return result;
    }

    @Override
    public boolean put(Object key) {
        puts.increment();
        long errorsBefore = delegate.backendErrors();
        try {
            boolean changed = delegate.put(key);
            detectLostWrite(errorsBefore);
            return changed;
        } catch (RuntimeException e) {
            markDegraded();
            throw e;
        }
    }

    @Override
    public long putAll(Iterable<?> keys) {
        long errorsBefore = delegate.backendErrors();
        try {
            long added = delegate.putAll(counting(keys));
            detectLostWrite(errorsBefore);
            return added;
        } catch (RuntimeException e) {
            markDegraded();
            throw e;
        }
    }

    @Override
    public BloomFilterSpec spec() {
        return delegate.spec();
    }

    @Override
    public BloomFilterStats stats() {
        return delegate.stats();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public void recordFalsePositive() {
        falsePositives.increment();
    }

    @Override
    public long backendErrors() {
        return delegate.backendErrors();
    }

    /**
     * @return the current state
     */
    public State state() {
        return state;
    }

    /**
     * @return the backend name, for example {@code memory}
     */
    public String backend() {
        return backend;
    }

    /**
     * @return the wrapped filter
     */
    public BloomFilter delegate() {
        return delegate;
    }

    void markReady() {
        if (state != State.DEGRADED) {
            state = State.READY;
        }
    }

    /** After a successful full rebuild: the content is complete again, whatever the previous state. */
    void markRecovered() {
        state = State.READY;
    }

    void markDegraded() {
        markDegraded("it lost a write because its backend failed");
    }

    void markDegraded(String reason) {
        if (state != State.DEGRADED) {
            state = State.DEGRADED;
            log.error("Bloom filter '" + name() + "' is degraded: " + reason + ". Checks are bypassed "
                    + "(always 'might contain') to avoid wrong 'not found' answers until the filter is rebuilt.");
        }
    }

    boolean tryStartRebuild() {
        return rebuilding.compareAndSet(false, true);
    }

    void finishRebuild(boolean success) {
        if (success) {
            rebuildSuccesses.increment();
            lastRebuild = Instant.now();
        } else {
            rebuildFailures.increment();
        }
        rebuilding.set(false);
    }

    void cancelRebuild() {
        rebuilding.set(false);
    }

    /** @return whether a rebuild is running on this instance */
    public boolean isRebuilding() {
        return rebuilding.get();
    }

    /** @return successful rebuilds since startup */
    public long rebuildSuccessCount() {
        return rebuildSuccesses.sum();
    }

    /** @return failed rebuilds since startup */
    public long rebuildFailureCount() {
        return rebuildFailures.sum();
    }

    /** @return time of the last successful rebuild on this instance, or {@code null} */
    public Instant lastRebuild() {
        return lastRebuild;
    }

    @Override
    public boolean isPopulated() {
        return delegate.isPopulated();
    }

    @Override
    public void markPopulated() {
        delegate.markPopulated();
    }

    private void detectLostWrite(long errorsBefore) {
        if (delegate.backendErrors() > errorsBefore) {
            markDegraded();
        }
    }

    private Iterable<?> counting(Iterable<?> keys) {
        return () -> {
            Iterator<?> it = keys.iterator();
            return new Iterator<Object>() {
                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }

                @Override
                public Object next() {
                    Object next = it.next();
                    puts.increment();
                    return next;
                }
            };
        };
    }

    /** @return checks answered "might contain" */
    public long mightContainCount() {
        return mightContain.sum();
    }

    /** @return checks answered "definitely absent" */
    public long absentCount() {
        return absent.sum();
    }

    /** @return checks bypassed while warming up or degraded */
    public long bypassedCount() {
        return bypassed.sum();
    }

    /** @return keys added */
    public long putCount() {
        return puts.sum();
    }

    /** @return recorded false positives */
    public long falsePositiveCount() {
        return falsePositives.sum();
    }

    @Override
    public String toString() {
        return "ManagedBloomFilter[" + name() + ", " + backend + ", " + state + "]";
    }
}
