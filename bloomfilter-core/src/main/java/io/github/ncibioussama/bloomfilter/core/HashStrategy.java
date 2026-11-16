package io.github.ncibioussama.bloomfilter.core;

/**
 * Produces two independent 64-bit hashes of a key, combined into {@code k} bit positions
 * with the Kirsch-Mitzenmacher technique ({@code h1 + i * h2}).
 */
public interface HashStrategy {

    /**
     * @param data the encoded key
     * @return an array of exactly two 64-bit hashes
     */
    long[] hash(byte[] data);

    /**
     * A stable identifier of the algorithm and its key material, without revealing the key.
     * Two strategies with the same fingerprint must map every key to the same bits.
     * Used to detect incompatible configuration changes on persistent backends.
     *
     * @return the fingerprint
     */
    String fingerprint();

    /**
     * Computes the {@code k} bit indexes of a key in a filter of {@code m} bits.
     *
     * @param data          encoded key
     * @param hashFunctions k
     * @param bitSize       m
     * @return k indexes in {@code [0, m)}
     */
    default long[] indexes(byte[] data, int hashFunctions, long bitSize) {
        long[] h = hash(data);
        long combined = h[0];
        long[] result = new long[hashFunctions];
        for (int i = 0; i < hashFunctions; i++) {
            result[i] = (combined & Long.MAX_VALUE) % bitSize;
            combined += h[1];
        }
        return result;
    }
}
