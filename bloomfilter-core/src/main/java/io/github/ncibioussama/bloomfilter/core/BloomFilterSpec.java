package io.github.ncibioussama.bloomfilter.core;

import java.util.regex.Pattern;

/**
 * Immutable sizing of a Bloom filter.
 *
 * @param name               filter name: 1-64 characters out of {@code [A-Za-z0-9._-]}
 * @param expectedInsertions number of keys the filter is sized for
 * @param falsePositiveRate  target false-positive probability, strictly between 0 and 1
 * @param bitSize            number of bits (m), always a multiple of 64
 * @param hashFunctions      number of hash functions (k), between 1 and {@value #MAX_HASH_FUNCTIONS}
 */
public record BloomFilterSpec(String name, long expectedInsertions, double falsePositiveRate,
                              long bitSize, int hashFunctions) {

    /** Default upper bound for {@link #bitSize()}: 2^32 bits = 512 MiB (also the Redis bitmap limit). */
    public static final long DEFAULT_MAX_BITS = 1L << 32;

    /** Upper bound for {@link #hashFunctions()}. */
    public static final int MAX_HASH_FUNCTIONS = 30;

    /** Lowest accepted false-positive rate. */
    public static final double MIN_FALSE_POSITIVE_RATE = 1e-9;

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final double LN2_SQUARED = Math.log(2) * Math.log(2);

    public BloomFilterSpec {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Invalid Bloom filter name '" + name + "': use 1-64 characters out of [A-Za-z0-9._-]");
        }
        if (expectedInsertions <= 0) {
            throw new IllegalArgumentException(
                    "Bloom filter '" + name + "': expectedInsertions must be > 0 but was " + expectedInsertions);
        }
        if (!(falsePositiveRate >= MIN_FALSE_POSITIVE_RATE && falsePositiveRate < 1)) {
            throw new IllegalArgumentException("Bloom filter '" + name + "': falsePositiveRate must be in ["
                    + MIN_FALSE_POSITIVE_RATE + ", 1) but was " + falsePositiveRate);
        }
        if (bitSize <= 0 || bitSize % 64 != 0) {
            throw new IllegalArgumentException(
                    "Bloom filter '" + name + "': bitSize must be a positive multiple of 64 but was " + bitSize);
        }
        if (hashFunctions < 1 || hashFunctions > MAX_HASH_FUNCTIONS) {
            throw new IllegalArgumentException("Bloom filter '" + name + "': hashFunctions must be in [1, "
                    + MAX_HASH_FUNCTIONS + "] but was " + hashFunctions);
        }
    }

    /**
     * Computes the optimal sizing with the {@link #DEFAULT_MAX_BITS default size limit}.
     *
     * @param name               filter name
     * @param expectedInsertions expected number of keys
     * @param falsePositiveRate  target false-positive probability
     * @return the spec
     */
    public static BloomFilterSpec of(String name, long expectedInsertions, double falsePositiveRate) {
        return of(name, expectedInsertions, falsePositiveRate, DEFAULT_MAX_BITS);
    }

    /**
     * Computes the optimal sizing: {@code m = -n ln(p) / (ln 2)^2} and {@code k = (m / n) ln 2}.
     *
     * @param name               filter name
     * @param expectedInsertions expected number of keys
     * @param falsePositiveRate  target false-positive probability
     * @param maxBits            refuse sizes above this many bits (protects against misconfiguration)
     * @return the spec
     * @throws IllegalArgumentException if the parameters are invalid or the filter would be too large
     */
    public static BloomFilterSpec of(String name, long expectedInsertions, double falsePositiveRate, long maxBits) {
        if (expectedInsertions <= 0 || !(falsePositiveRate >= MIN_FALSE_POSITIVE_RATE && falsePositiveRate < 1)) {
            // let the canonical constructor produce the descriptive error
            return new BloomFilterSpec(name, expectedInsertions, falsePositiveRate, 64, 1);
        }
        double rawBits = Math.ceil(-expectedInsertions * Math.log(falsePositiveRate) / LN2_SQUARED);
        if (rawBits > maxBits) {
            throw new IllegalArgumentException(String.format(
                    "Bloom filter '%s' needs %.0f bits (~%.1f MiB) for %d insertions at %s false-positive rate, "
                            + "which exceeds the limit of %d bits (~%.1f MiB). Lower expectedInsertions, raise "
                            + "falsePositiveRate or raise the limit.",
                    name, rawBits, rawBits / 8 / 1024 / 1024, expectedInsertions, falsePositiveRate,
                    maxBits, maxBits / 8.0 / 1024 / 1024));
        }
        long bits = Math.max(64, ((long) rawBits + 63) / 64 * 64);
        int k = (int) Math.round((double) bits / expectedInsertions * Math.log(2));
        k = Math.max(1, Math.min(MAX_HASH_FUNCTIONS, k));
        return new BloomFilterSpec(name, expectedInsertions, falsePositiveRate, bits, k);
    }
}
