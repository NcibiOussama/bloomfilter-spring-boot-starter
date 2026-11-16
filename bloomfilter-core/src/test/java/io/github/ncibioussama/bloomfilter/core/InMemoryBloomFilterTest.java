package io.github.ncibioussama.bloomfilter.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class InMemoryBloomFilterTest {

    @Test
    void hasNoFalseNegatives() {
        BloomFilter filter = new InMemoryBloomFilter(BloomFilterSpec.of("t", 100_000, 0.01));
        for (long i = 0; i < 100_000; i++) {
            filter.put(i);
        }
        for (long i = 0; i < 100_000; i++) {
            assertThat(filter.mightContain(i)).isTrue();
        }
    }

    @Test
    void falsePositiveRateStaysNearTarget() {
        double target = 0.01;
        int n = 100_000;
        BloomFilter filter = new InMemoryBloomFilter(BloomFilterSpec.of("t", n, target));
        filter.putAll(LongStream.range(0, n).boxed().toList());

        int falsePositives = 0;
        int probes = 200_000;
        for (long i = n; i < n + probes; i++) {
            if (filter.mightContain(i)) {
                falsePositives++;
            }
        }
        double observed = (double) falsePositives / probes;
        assertThat(observed).isLessThan(target * 1.3);
        assertThat(filter.stats().estimatedFalsePositiveRate()).isLessThan(target * 1.3);
    }

    @Test
    void murmur3AndSipHashBehaveTheSame() {
        for (HashStrategy strategy : List.of(new Murmur3HashStrategy(), SipHashStrategy.withRandomKey())) {
            BloomFilter filter = new InMemoryBloomFilter(BloomFilterSpec.of("t", 1000, 0.01),
                    strategy, new DefaultKeyEncoder());
            assertThat(filter.put("alice")).isTrue();
            assertThat(filter.put("alice")).isFalse();
            assertThat(filter.mightContain("alice")).isTrue();
        }
    }

    @Test
    void statsTrackApproximateCount() {
        BloomFilter filter = new InMemoryBloomFilter(BloomFilterSpec.of("t", 10_000, 0.01));
        assertThat(filter.isEmpty()).isTrue();
        for (int i = 0; i < 5_000; i++) {
            filter.put(UUID.randomUUID());
        }
        BloomFilterStats stats = filter.stats();
        assertThat(stats.approximateElementCount()).isBetween(4_750L, 5_250L);
        assertThat(stats.overCapacity()).isFalse();
        assertThat(filter.isEmpty()).isFalse();

        filter.clear();
        assertThat(filter.isEmpty()).isTrue();
    }

    @Test
    void isThreadSafe() throws Exception {
        BloomFilter filter = new InMemoryBloomFilter(BloomFilterSpec.of("t", 200_000, 0.01));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int offset = t * 25_000;
                futures.add(pool.submit(() -> {
                    for (long i = offset; i < offset + 25_000; i++) {
                        filter.put(i);
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }
        for (long i = 0; i < 200_000; i++) {
            assertThat(filter.mightContain(i)).isTrue();
        }
    }
}
