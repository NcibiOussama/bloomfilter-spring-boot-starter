package io.github.ncibioussama.bloomfilter.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Encodes keys through a canonical string form, so that the same logical key always matches
 * regardless of its Java type: {@code 42}, {@code 42L} and {@code "42"} are equal keys.
 * <ul>
 *     <li>{@link CharSequence}: UTF-8 bytes</li>
 *     <li>{@code byte}, {@code short}, {@code int}, {@code long}, {@link BigInteger}: decimal string</li>
 *     <li>{@link BigDecimal}: plain string with trailing zeros stripped ({@code 1.50} equals {@code 1.5})</li>
 *     <li>{@link UUID}: lowercase canonical string</li>
 *     <li>{@link Enum}: {@link Enum#name()}</li>
 *     <li>{@code byte[]}: used as-is</li>
 * </ul>
 * Other types are rejected instead of falling back to {@code toString()}, because an unstable
 * {@code toString()} would silently create false negatives. Provide your own {@link KeyEncoder} or
 * pass a canonical field (for example {@code #user.id}) instead.
 */
public final class DefaultKeyEncoder implements KeyEncoder {

    /** Default maximum encoded key size in bytes. */
    public static final int DEFAULT_MAX_KEY_BYTES = 4096;

    private final int maxKeyBytes;

    public DefaultKeyEncoder() {
        this(DEFAULT_MAX_KEY_BYTES);
    }

    /**
     * @param maxKeyBytes reject keys whose encoded size exceeds this many bytes
     */
    public DefaultKeyEncoder(int maxKeyBytes) {
        if (maxKeyBytes <= 0) {
            throw new IllegalArgumentException("maxKeyBytes must be > 0 but was " + maxKeyBytes);
        }
        this.maxKeyBytes = maxKeyBytes;
    }

    @Override
    public byte[] encode(Object key) {
        byte[] bytes = toBytes(key);
        if (bytes.length > maxKeyBytes) {
            // never include the key itself in the message: it may be sensitive (email, token, ...)
            throw new KeyTooLargeException(bytes.length, maxKeyBytes);
        }
        return bytes;
    }

    private static byte[] toBytes(Object key) {
        if (key == null) {
            throw new IllegalArgumentException("Bloom filter keys must not be null");
        }
        if (key instanceof byte[] raw) {
            return raw;
        }
        return canonicalString(key).getBytes(StandardCharsets.UTF_8);
    }

    private static String canonicalString(Object key) {
        if (key instanceof CharSequence cs) {
            return cs.toString();
        }
        if (key instanceof Long || key instanceof Integer || key instanceof Short || key instanceof Byte
                || key instanceof AtomicLong || key instanceof AtomicInteger) {
            return Long.toString(((Number) key).longValue());
        }
        if (key instanceof BigInteger bi) {
            return bi.toString();
        }
        if (key instanceof BigDecimal bd) {
            return bd.signum() == 0 ? "0" : bd.stripTrailingZeros().toPlainString();
        }
        if (key instanceof UUID uuid) {
            return uuid.toString();
        }
        if (key instanceof Enum<?> e) {
            return e.name();
        }
        if (key instanceof Character c) {
            return c.toString();
        }
        throw new IllegalArgumentException("Unsupported Bloom filter key type " + key.getClass().getName()
                + ". Use a String, integral number, UUID, enum or byte[] (for example key = \"#user.id\"), "
                + "or register a custom KeyEncoder bean.");
    }
}
