package io.github.ncibioussama.bloomfilter.core;

final class Bytes {

    private Bytes() {
    }

    static long getLongLE(byte[] b, int offset) {
        return (b[offset] & 0xffL)
                | (b[offset + 1] & 0xffL) << 8
                | (b[offset + 2] & 0xffL) << 16
                | (b[offset + 3] & 0xffL) << 24
                | (b[offset + 4] & 0xffL) << 32
                | (b[offset + 5] & 0xffL) << 40
                | (b[offset + 6] & 0xffL) << 48
                | (b[offset + 7] & 0xffL) << 56;
    }
}
