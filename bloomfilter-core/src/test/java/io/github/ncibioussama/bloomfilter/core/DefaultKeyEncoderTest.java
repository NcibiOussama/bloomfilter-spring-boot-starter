package io.github.ncibioussama.bloomfilter.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultKeyEncoderTest {

    private final DefaultKeyEncoder encoder = new DefaultKeyEncoder(64);

    @Test
    void numbersAndStringsAreTheSameKey() {
        byte[] expected = encoder.encode("42");
        assertThat(encoder.encode(42)).isEqualTo(expected);
        assertThat(encoder.encode(42L)).isEqualTo(expected);
        assertThat(encoder.encode((short) 42)).isEqualTo(expected);
        assertThat(encoder.encode(BigInteger.valueOf(42))).isEqualTo(expected);
        assertThat(encoder.encode(new BigDecimal("42.00"))).isEqualTo(expected);
    }

    @Test
    void encodesUuidCanonically() {
        UUID id = UUID.randomUUID();
        assertThat(encoder.encode(id)).isEqualTo(encoder.encode(id.toString()));
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> encoder.encode(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsupportedTypesInsteadOfUsingToString() {
        assertThatThrownBy(() -> encoder.encode(new Object()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported Bloom filter key type");
    }

    @Test
    void rejectsOversizedKeysWithoutLeakingThem() {
        String secretLooking = "x".repeat(65);
        assertThatThrownBy(() -> encoder.encode(secretLooking))
                .isInstanceOf(KeyTooLargeException.class)
                .hasMessageContaining("exceeds the limit of 64")
                .hasMessageNotContaining(secretLooking);
    }
}
