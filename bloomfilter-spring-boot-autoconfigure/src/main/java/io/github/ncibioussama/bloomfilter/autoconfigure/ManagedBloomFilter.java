package io.github.ncibioussama.bloomfilter.autoconfigure;

import io.github.ncibioussama.bloomfilter.core.BloomFilter;
import io.github.ncibioussama.bloomfilter.core.BloomFilterSpec;
import io.github.ncibioussama.bloomfilter.core.BloomFilterStats;
import java.util.Iterator;
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
        /** A write was lost (backend failure), so the filter may miss keys: checks are bypassed. */
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

    void markDegraded() {
        if (state != State.DEGRADED) {
            state = State.DEGRADED;
            log.error("Bloom filter '" + name() + "' lost a write because its backend failed. Checks are now "
                    + "bypassed (always 'might contain') to avoid wrong 'not found' answers. Restart or rebuild "
                    + "the filter to re-enable it.");
        }
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
