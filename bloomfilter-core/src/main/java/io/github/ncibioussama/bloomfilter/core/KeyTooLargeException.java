package io.github.ncibioussama.bloomfilter.core;

/**
 * Thrown by a {@link KeyEncoder} when a key exceeds the configured size limit.
 * <p>
 * Filters never store such keys and answer "might contain" for them, so the caller falls back to its source of
 * truth. An oversized value (for example one an attacker submitted) therefore cannot fail a write, a bulk load
 * or a rebuild, and cannot produce a wrong "not found". The message never contains the key.
 */
public class KeyTooLargeException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /**
     * @param keyBytes     encoded size of the rejected key
     * @param maxKeyBytes  the limit
     */
    public KeyTooLargeException(int keyBytes, int maxKeyBytes) {
        super("Bloom filter key is " + keyBytes + " bytes, which exceeds the limit of " + maxKeyBytes);
    }
}
