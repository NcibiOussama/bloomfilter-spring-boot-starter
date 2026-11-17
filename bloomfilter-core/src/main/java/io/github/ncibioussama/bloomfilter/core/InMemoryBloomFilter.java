package io.github.ncibioussama.bloomfilter.core;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free, thread-safe in-memory Bloom filter. Each JVM has its own copy and the content is lost
 * on restart; use a distributed backend to share a filter between instances.
 * <p>
 * Supports {@link Rebuildable rebuilds}: a second bit array is filled in the background while
 * {@code put} writes to both, then swapped in with a single reference assignment. A rebuild needs
 * twice the filter's memory until it is committed.
 */
public final class InMemoryBloomFilter implements BloomFilter, Rebuildable {

    private final BloomFilterSpec spec;
    private final HashStrategy hashStrategy;
    private final KeyEncoder keyEncoder;

    /*
     * Ordering that makes the swap lossless: put() reads `next` before `current`, while commit()
     * assigns `current = next` before clearing `next`. A put therefore either sees the rebuild
     * target (and writes into it) or already sees the swapped-in array as `current`.
     */
    private volatile Bits current;
    private volatile Bits next;

    /**
     * Creates a filter keyed with a random SipHash key and the default key encoder.
     *
     * @param spec the sizing
     */
    public InMemoryBloomFilter(BloomFilterSpec spec) {
        this(spec, SipHashStrategy.withRandomKey(), new DefaultKeyEncoder());
    }

    /**
     * @param spec         the sizing
     * @param hashStrategy the hash strategy
     * @param keyEncoder   the key encoder
     */
    public InMemoryBloomFilter(BloomFilterSpec spec, HashStrategy hashStrategy, KeyEncoder keyEncoder) {
        if (spec == null || hashStrategy == null || keyEncoder == null) {
            throw new IllegalArgumentException("spec, hashStrategy and keyEncoder must not be null");
        }
        if (spec.bitSize() / 64 > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("Bloom filter '" + spec.name() + "' is too large for memory");
        }
        this.spec = spec;
        this.hashStrategy = hashStrategy;
        this.keyEncoder = keyEncoder;
        this.current = new Bits(spec);
    }

    private InMemoryBloomFilter(InMemoryBloomFilter parent, Bits bits) {
        this.spec = parent.spec;
        this.hashStrategy = parent.hashStrategy;
        this.keyEncoder = parent.keyEncoder;
        this.current = bits;
    }

    @Override
    public boolean mightContain(Object key) {
        long[] indexes = indexes(key);
        if (indexes == null) {
            return true; // oversized keys are never stored: "might contain" sends the caller to the source of truth
        }
        Bits bits = current;
        for (long index : indexes) {
            if (!bits.get(index)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean put(Object key) {
        long[] indexes = indexes(key);
        if (indexes == null) {
            return false; // oversized keys are not stored; checks for them always answer "might contain"
        }
        Bits target = next;      // read next before current, see field comment
        Bits bits = current;
        boolean changed = false;
        for (long index : indexes) {
            changed |= bits.set(index);
        }
        if (target != null && target != bits) {
            for (long index : indexes) {
                target.set(index);
            }
        }
        return changed;
    }

    @Override
    public BloomFilterSpec spec() {
        return spec;
    }

    @Override
    public BloomFilterStats stats() {
        return new BloomFilterStats(spec, current.count());
    }

    @Override
    public boolean isEmpty() {
        return current.count() == 0;
    }

    /**
     * Clears the filter. Not atomic with respect to concurrent {@link #put(Object)} calls.
     */
    @Override
    public void clear() {
        current.clear();
    }

    @Override
    public synchronized RebuildSession beginRebuild(Duration maxDuration) {
        if (next != null) {
            return null;
        }
        Bits target = new Bits(spec);
        next = target;
        return new Session(target);
    }

    private synchronized boolean commit(Bits target) {
        if (next != target) {
            return false;
        }
        current = target;
        next = null;
        return true;
    }

    private synchronized void abort(Bits target) {
        if (next == target) {
            next = null;
        }
    }

    /** @return the bit indexes of the key, or {@code null} if the key exceeds the encoder's size limit */
    private long[] indexes(Object key) {
        byte[] encoded;
        try {
            encoded = keyEncoder.encode(key);
        } catch (KeyTooLargeException e) {
            return null;
        }
        return hashStrategy.indexes(encoded, spec.hashFunctions(), spec.bitSize());
    }

    @Override
    public String toString() {
        return "InMemoryBloomFilter[" + spec.name() + "]";
    }

    private final class Session implements RebuildSession {

        private final Bits bits;
        private final BloomFilter target;

        Session(Bits bits) {
            this.bits = bits;
            this.target = new InMemoryBloomFilter(InMemoryBloomFilter.this, bits);
        }

        @Override
        public BloomFilter target() {
            return target;
        }

        @Override
        public boolean commit() {
            return InMemoryBloomFilter.this.commit(bits);
        }

        @Override
        public void abort() {
            InMemoryBloomFilter.this.abort(bits);
        }
    }

    /** One bit array with its population count. */
    private static final class Bits {

        private final AtomicLongArray words;
        private final long bitSize;
        private final LongAdder bitsSet = new LongAdder();

        Bits(BloomFilterSpec spec) {
            this.words = new AtomicLongArray((int) (spec.bitSize() / 64));
            this.bitSize = spec.bitSize();
        }

        boolean get(long index) {
            return (words.get((int) (index >>> 6)) & (1L << index)) != 0;
        }

        boolean set(long index) {
            int word = (int) (index >>> 6);
            long mask = 1L << index;
            long value;
            do {
                value = words.get(word);
                if ((value & mask) != 0) {
                    return false;
                }
            } while (!words.compareAndSet(word, value, value | mask));
            bitsSet.increment();
            return true;
        }

        long count() {
            return Math.max(0, Math.min(bitsSet.sum(), bitSize));
        }

        void clear() {
            for (int i = 0; i < words.length(); i++) {
                words.set(i, 0L);
            }
            bitsSet.reset();
        }
    }
}
