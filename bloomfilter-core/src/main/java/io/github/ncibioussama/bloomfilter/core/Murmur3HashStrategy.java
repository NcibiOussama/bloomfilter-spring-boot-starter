package io.github.ncibioussama.bloomfilter.core;

/**
 * MurmurHash3 x64 128-bit. Very fast, but the algorithm is public and unkeyed: an attacker who can
 * choose keys can craft many keys that set the same bits and push the false-positive rate up
 * (a "hash flooding" attack). Use {@link SipHashStrategy} when keys come from untrusted input.
 */
public final class Murmur3HashStrategy implements HashStrategy {

    private static final long C1 = 0x87c37b91114253d5L;
    private static final long C2 = 0x4cf5ad432745937fL;

    private final int seed;

    public Murmur3HashStrategy() {
        this(0);
    }

    /**
     * @param seed hash seed
     */
    public Murmur3HashStrategy(int seed) {
        this.seed = seed;
    }

    @Override
    public String fingerprint() {
        return "murmur3-128:" + Integer.toHexString(seed);
    }

    @Override
    public long[] hash(byte[] data) {
        int length = data.length;
        int blocks = length / 16;
        long h1 = seed & 0xffffffffL;
        long h2 = seed & 0xffffffffL;

        for (int i = 0; i < blocks; i++) {
            long k1 = Bytes.getLongLE(data, i * 16);
            long k2 = Bytes.getLongLE(data, i * 16 + 8);

            h1 ^= mixK1(k1);
            h1 = Long.rotateLeft(h1, 27);
            h1 += h2;
            h1 = h1 * 5 + 0x52dce729;

            h2 ^= mixK2(k2);
            h2 = Long.rotateLeft(h2, 31);
            h2 += h1;
            h2 = h2 * 5 + 0x38495ab5;
        }

        int tail = blocks * 16;
        long k1 = 0;
        long k2 = 0;
        switch (length & 15) {
            case 15: k2 ^= (long) (data[tail + 14] & 0xff) << 48;
            case 14: k2 ^= (long) (data[tail + 13] & 0xff) << 40;
            case 13: k2 ^= (long) (data[tail + 12] & 0xff) << 32;
            case 12: k2 ^= (long) (data[tail + 11] & 0xff) << 24;
            case 11: k2 ^= (long) (data[tail + 10] & 0xff) << 16;
            case 10: k2 ^= (long) (data[tail + 9] & 0xff) << 8;
            case 9:
                k2 ^= data[tail + 8] & 0xff;
                h2 ^= mixK2(k2);
            case 8: k1 ^= (long) (data[tail + 7] & 0xff) << 56;
            case 7: k1 ^= (long) (data[tail + 6] & 0xff) << 48;
            case 6: k1 ^= (long) (data[tail + 5] & 0xff) << 40;
            case 5: k1 ^= (long) (data[tail + 4] & 0xff) << 32;
            case 4: k1 ^= (long) (data[tail + 3] & 0xff) << 24;
            case 3: k1 ^= (long) (data[tail + 2] & 0xff) << 16;
            case 2: k1 ^= (long) (data[tail + 1] & 0xff) << 8;
            case 1:
                k1 ^= data[tail] & 0xff;
                h1 ^= mixK1(k1);
            default:
                break;
        }

        h1 ^= length;
        h2 ^= length;
        h1 += h2;
        h2 += h1;
        h1 = fmix64(h1);
        h2 = fmix64(h2);
        h1 += h2;
        h2 += h1;
        return new long[] {h1, h2};
    }

    private static long mixK1(long k1) {
        k1 *= C1;
        k1 = Long.rotateLeft(k1, 31);
        k1 *= C2;
        return k1;
    }

    private static long mixK2(long k2) {
        k2 *= C2;
        k2 = Long.rotateLeft(k2, 33);
        k2 *= C1;
        return k2;
    }

    private static long fmix64(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }
}
