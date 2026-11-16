package io.github.ncibioussama.bloomfilter.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HashStrategyTest {

    @Test
    void murmur3MatchesReferenceVector() {
        long[] h = new Murmur3HashStrategy().hash(
                "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8));
        assertThat(h[0]).isEqualTo(0xe34bbc7bbc071b6cL);
        assertThat(h[1]).isEqualTo(0x7a433ca9c49a9347L);
    }

    @Test
    void murmur3OfEmptyInputWithSeedZeroIsZero() {
        assertThat(new Murmur3HashStrategy().hash(new byte[0])).containsExactly(0L, 0L);
    }

    @Test
    void sipHashMatchesReferenceVector() {
        // Test vector from the SipHash paper (Aumasson & Bernstein, appendix A)
        byte[] message = new byte[15];
        for (int i = 0; i < message.length; i++) {
            message[i] = (byte) i;
        }
        long k0 = 0x0706050403020100L;
        long k1 = 0x0f0e0d0c0b0a0908L;
        assertThat(SipHashStrategy.sipHash24(k0, k1, message)).isEqualTo(0xa129ca6149be45e5L);
    }

    @Test
    void sipHashDependsOnSecret() {
        byte[] key = "same key".getBytes(StandardCharsets.UTF_8);
        long[] a = SipHashStrategy.withSecret("first-secret-0123456789").hash(key);
        long[] b = SipHashStrategy.withSecret("second-secret-0123456789").hash(key);
        long[] a2 = SipHashStrategy.withSecret("first-secret-0123456789").hash(key);
        assertThat(a).isNotEqualTo(b).isEqualTo(a2);
    }

    @Test
    void sipHashFingerprintIsStableAndDoesNotContainSecret() {
        String secret = "a-very-secret-value-1234";
        String f1 = SipHashStrategy.withSecret(secret).fingerprint();
        assertThat(f1).isEqualTo(SipHashStrategy.withSecret(secret).fingerprint())
                .startsWith("siphash-2-4:")
                .doesNotContain(secret);
        assertThat(SipHashStrategy.withSecret("another-secret-value-12").fingerprint()).isNotEqualTo(f1);
    }

    @Test
    void sipHashRejectsShortSecret() {
        assertThatThrownBy(() -> SipHashStrategy.withSecret("short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 16 bytes");
    }

    @Test
    void sipHashHandlesAllTailLengths() {
        SipHashStrategy strategy = SipHashStrategy.withRandomKey();
        Set<Long> seen = new HashSet<>();
        for (int len = 0; len <= 40; len++) {
            seen.add(strategy.hash(new byte[len])[0]);
        }
        assertThat(seen).hasSize(41);
    }

    @Test
    void indexesAreInRange() {
        HashStrategy strategy = new Murmur3HashStrategy();
        for (int i = 0; i < 10_000; i++) {
            for (long index : strategy.indexes(("k" + i).getBytes(StandardCharsets.UTF_8), 7, 1000)) {
                assertThat(index).isBetween(0L, 999L);
            }
        }
    }
}
