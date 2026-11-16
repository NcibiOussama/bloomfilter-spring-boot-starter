package io.github.ncibioussama.bloomfilter.core;

/**
 * Converts a key into the bytes that are hashed.
 * <p>
 * The same logical key must always produce the same bytes, otherwise the filter returns false negatives.
 */
@FunctionalInterface
public interface KeyEncoder {

    /**
     * @param key the key, never {@code null}
     * @return the bytes to hash
     * @throws IllegalArgumentException if the key type is unsupported or the key is too large
     */
    byte[] encode(Object key);
}
