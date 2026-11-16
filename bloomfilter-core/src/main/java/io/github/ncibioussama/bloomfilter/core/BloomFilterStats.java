package io.github.ncibioussama.bloomfilter.core;

/**
 * Point-in-time statistics of a Bloom filter.
 *
 * @param spec    the filter sizing
 * @param bitsSet number of bits currently set to 1
 */
public record BloomFilterStats(BloomFilterSpec spec, long bitsSet) {

    public BloomFilterStats {
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
        if (bitsSet < 0 || bitsSet > spec.bitSize()) {
            throw new IllegalArgumentException("bitsSet out of range: " + bitsSet);
        }
    }

    /**
     * @return fraction of bits set, between 0 and 1
     */
    public double fillRatio() {
        return (double) bitsSet / spec.bitSize();
    }

    /**
     * @return the probability that a random absent key is reported as present, given the current fill
     */
    public double estimatedFalsePositiveRate() {
        return Math.pow(fillRatio(), spec.hashFunctions());
    }

    /**
     * Estimates the number of distinct keys added: {@code -(m / k) ln(1 - X / m)}.
     *
     * @return the estimate, or {@link Long#MAX_VALUE} if every bit is set
     */
    public long approximateElementCount() {
        if (bitsSet >= spec.bitSize()) {
            return Long.MAX_VALUE;
        }
        double m = spec.bitSize();
        return Math.round(-m / spec.hashFunctions() * Math.log1p(-bitsSet / m));
    }

    /**
     * @return {@code true} if more keys were added than the filter was sized for, which means the
     * real false-positive rate is above the target
     */
    public boolean overCapacity() {
        return approximateElementCount() > spec.expectedInsertions();
    }
}
