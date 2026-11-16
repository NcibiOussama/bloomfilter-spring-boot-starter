package io.github.ncibioussama.bloomfilter.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Keyed SipHash-2-4. Without the secret key an attacker cannot predict which bits a key sets,
 * which defeats hash-flooding attacks that try to saturate the filter.
 * <p>
 * Two SipHash instances with independent 128-bit keys (derived from the secret with SHA-256)
 * produce the two hashes needed for double hashing.
 */
public final class SipHashStrategy implements HashStrategy {

    /** Minimum secret length in bytes. */
    public static final int MIN_SECRET_BYTES = 16;

    private final long k0;
    private final long k1;
    private final long k2;
    private final long k3;
    private final String fingerprint;

    /**
     * @param secret secret key material, at least {@value #MIN_SECRET_BYTES} bytes
     */
    public SipHashStrategy(byte[] secret) {
        if (secret == null || secret.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "SipHash secret must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        byte[] key = sha256(concat("bloomfilter-siphash-key:".getBytes(StandardCharsets.US_ASCII), secret));
        this.k0 = Bytes.getLongLE(key, 0);
        this.k1 = Bytes.getLongLE(key, 8);
        this.k2 = Bytes.getLongLE(key, 16);
        this.k3 = Bytes.getLongLE(key, 24);
        byte[] id = sha256(concat("bloomfilter-siphash-fingerprint:".getBytes(StandardCharsets.US_ASCII), secret));
        this.fingerprint = "siphash-2-4:" + HexFormat.of().formatHex(id, 0, 6);
    }

    /**
     * @param secret secret, at least {@value #MIN_SECRET_BYTES} UTF-8 bytes
     * @return a strategy keyed with the secret
     */
    public static SipHashStrategy withSecret(String secret) {
        if (secret == null) {
            throw new IllegalArgumentException("SipHash secret must not be null");
        }
        return new SipHashStrategy(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Creates a strategy with a random key. Suitable for in-memory filters that live only as long
     * as the JVM; not suitable for shared or persisted filters.
     *
     * @return a randomly keyed strategy
     */
    public static SipHashStrategy withRandomKey() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        return new SipHashStrategy(secret);
    }

    @Override
    public String fingerprint() {
        return fingerprint;
    }

    @Override
    public long[] hash(byte[] data) {
        return new long[] {sipHash24(k0, k1, data), sipHash24(k2, k3, data)};
    }

    /**
     * Reference SipHash-2-4 with a 64-bit output.
     *
     * @param k0   first half of the 128-bit key (little-endian)
     * @param k1   second half of the 128-bit key (little-endian)
     * @param data the message
     * @return the 64-bit hash
     */
    static long sipHash24(long k0, long k1, byte[] data) {
        long v0 = k0 ^ 0x736f6d6570736575L;
        long v1 = k1 ^ 0x646f72616e646f6dL;
        long v2 = k0 ^ 0x6c7967656e657261L;
        long v3 = k1 ^ 0x7465646279746573L;

        int length = data.length;
        int end = length - (length % 8);
        for (int i = 0; i < end; i += 8) {
            long m = Bytes.getLongLE(data, i);
            v3 ^= m;
            for (int r = 0; r < 2; r++) {
                v0 += v1; v1 = Long.rotateLeft(v1, 13); v1 ^= v0; v0 = Long.rotateLeft(v0, 32);
                v2 += v3; v3 = Long.rotateLeft(v3, 16); v3 ^= v2;
                v0 += v3; v3 = Long.rotateLeft(v3, 21); v3 ^= v0;
                v2 += v1; v1 = Long.rotateLeft(v1, 17); v1 ^= v2; v2 = Long.rotateLeft(v2, 32);
            }
            v0 ^= m;
        }

        long b = ((long) length) << 56;
        for (int i = 0; i < length - end; i++) {
            b |= (long) (data[end + i] & 0xff) << (8 * i);
        }
        v3 ^= b;
        for (int r = 0; r < 2; r++) {
            v0 += v1; v1 = Long.rotateLeft(v1, 13); v1 ^= v0; v0 = Long.rotateLeft(v0, 32);
            v2 += v3; v3 = Long.rotateLeft(v3, 16); v3 ^= v2;
            v0 += v3; v3 = Long.rotateLeft(v3, 21); v3 ^= v0;
            v2 += v1; v1 = Long.rotateLeft(v1, 17); v1 ^= v2; v2 = Long.rotateLeft(v2, 32);
        }
        v0 ^= b;

        v2 ^= 0xff;
        for (int r = 0; r < 4; r++) {
            v0 += v1; v1 = Long.rotateLeft(v1, 13); v1 ^= v0; v0 = Long.rotateLeft(v0, 32);
            v2 += v3; v3 = Long.rotateLeft(v3, 16); v3 ^= v2;
            v0 += v3; v3 = Long.rotateLeft(v3, 21); v3 ^= v0;
            v2 += v1; v1 = Long.rotateLeft(v1, 17); v1 ^= v2; v2 = Long.rotateLeft(v2, 32);
        }
        return v0 ^ v1 ^ v2 ^ v3;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
