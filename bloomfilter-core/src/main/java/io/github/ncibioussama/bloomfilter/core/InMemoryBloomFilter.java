package io.github.ncibioussama.bloomfilter.core;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free, thread-safe in-memory Bloom filter. Each JVM has its own copy and the content is lost
 * on restart; use a distributed backend to share a filter between instances.
 */
public final class InMemoryBloomFilter implements BloomFilter {

    private final BloomFilterSpec spec;
    private final HashStrategy hashStrategy;
    private final KeyEncoder keyEncoder;
    private final AtomicLongArray words;
    private final LongAdder bitsSet = new LongAdder();

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
        long wordCount = spec.bitSize() / 64;
        if (wordCount > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("Bloom filter '" + spec.name() + "' is too large for memory");
        }
        this.spec = spec;
        this.hashStrategy = hashStrategy;
        this.keyEncoder = keyEncoder;
        this.words = new AtomicLongArray((int) wordCount);
    }

    @Override
    public boolean mightContain(Object key) {
        for (long index : indexes(key)) {
            if ((words.get((int) (index >>> 6)) & (1L << index)) == 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean put(Object key) {
        boolean changed = false;
        for (long index : indexes(key)) {
            changed |= setBit(index);
        }
        return changed;
    }

    @Override
    public BloomFilterSpec spec() {
        return spec;
    }

    @Override
    public BloomFilterStats stats() {
        return new BloomFilterStats(spec, Math.max(0, Math.min(bitsSet.sum(), spec.bitSize())));
    }

    @Override
    public boolean isEmpty() {
        return bitsSet.sum() == 0;
    }

    /**
     * Clears the filter. Not atomic with respect to concurrent {@link #put(Object)} calls.
     */
    @Override
    public void clear() {
        for (int i = 0; i < words.length(); i++) {
            words.set(i, 0L);
        }
        bitsSet.reset();
    }

    private long[] indexes(Object key) {
        return hashStrategy.indexes(keyEncoder.encode(key), spec.hashFunctions(), spec.bitSize());
    }

    private boolean setBit(long index) {
        int word = (int) (index >>> 6);
        long mask = 1L << index;
        long current;
        do {
            current = words.get(word);
            if ((current & mask) != 0) {
                return false;
            }
        } while (!words.compareAndSet(word, current, current | mask));
        bitsSet.increment();
        return true;
    }

    @Override
    public String toString() {
        return "InMemoryBloomFilter[" + spec.name() + "]";
    }
}
