package io.github.ncibioussama.bloomfilter.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BloomFilterSpecTest {

    @Test
    void computesOptimalSize() {
        BloomFilterSpec spec = BloomFilterSpec.of("users", 1_000_000, 0.01);
        // m = -n ln p / (ln 2)^2 = 9,585,059 bits, rounded up to a multiple of 64
        assertThat(spec.bitSize()).isEqualTo(9_585_088L);
        assertThat(spec.hashFunctions()).isEqualTo(7);
    }

    @Test
    void rejectsFiltersAboveLimit() {
        assertThatThrownBy(() -> BloomFilterSpec.of("huge", 10_000_000_000L, 0.0001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds the limit");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "has space", "a/b", "this-name-is-way-too-long-for-a-bloom-filter-name-and-gets-rejected"})
    void rejectsInvalidNames(String name) {
        assertThatThrownBy(() -> BloomFilterSpec.of(name, 10, 0.01))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Bloom filter name");
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, 1, -0.5, 1.5, Double.NaN})
    void rejectsInvalidFalsePositiveRate(double p) {
        assertThatThrownBy(() -> BloomFilterSpec.of("f", 10, p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("falsePositiveRate");
    }

    @Test
    void rejectsNonPositiveInsertions() {
        assertThatThrownBy(() -> BloomFilterSpec.of("f", 0, 0.01))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedInsertions");
    }
}
