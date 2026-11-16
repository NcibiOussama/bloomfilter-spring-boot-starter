package io.github.ncibioussama.bloomfilter.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class InMemoryRebuildTest {

    private static final Duration TIMEOUT = Duration.ofMinutes(1);

    private final InMemoryBloomFilter filter = new InMemoryBloomFilter(BloomFilterSpec.of("t", 10_000, 0.001));

    @Test
    void rebuildDropsDeletedKeysAndKeepsConcurrentWrites() {
        filter.put("deleted-user");
        filter.put("alice");

        RebuildSession session = filter.beginRebuild(TIMEOUT);
        filter.put("added-during-rebuild");          // dual-written
        session.target().put("alice");               // the loader only sees current data
        assertThat(filter.mightContain("deleted-user")).as("old content serves until commit").isTrue();

        assertThat(session.commit()).isTrue();

        assertThat(filter.mightContain("alice")).isTrue();
        assertThat(filter.mightContain("added-during-rebuild")).isTrue();
        assertThat(filter.mightContain("deleted-user")).isFalse();
        assertThat(filter.stats().approximateElementCount()).isEqualTo(2);
    }

    @Test
    void onlyOneRebuildAtATime() {
        RebuildSession first = filter.beginRebuild(TIMEOUT);
        assertThat(filter.beginRebuild(TIMEOUT)).isNull();
        first.abort();
        assertThat(filter.beginRebuild(TIMEOUT)).isNotNull();
    }

    @Test
    void abortKeepsCurrentContent() {
        filter.put("alice");
        RebuildSession session = filter.beginRebuild(TIMEOUT);
        session.abort();
        assertThat(session.commit()).isFalse();
        assertThat(filter.mightContain("alice")).isTrue();
    }

    @Test
    void noWriteIsLostAcrossTheSwapUnderConcurrency() throws Exception {
        long maxKeys = 200_000;
        InMemoryBloomFilter big = new InMemoryBloomFilter(BloomFilterSpec.of("big", 1_000_000, 0.001));
        AtomicLong nextKey = new AtomicLong();
        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch started = new CountDownLatch(4);
        Thread[] writers = new Thread[4];
        for (int t = 0; t < writers.length; t++) {
            writers[t] = new Thread(() -> {
                started.countDown();
                while (running.get()) {
                    long key = nextKey.getAndIncrement();
                    if (key >= maxKeys) {
                        return;
                    }
                    big.put(key);
                }
            });
            writers[t].start();
        }
        started.await();
        for (int round = 0; round < 20; round++) {
            RebuildSession session = big.beginRebuild(TIMEOUT);
            // Like a real loader, read the source of truth after the rebuild started:
            // keys handed out later are guaranteed to be dual-written.
            long loadedUpTo = Math.min(nextKey.get(), maxKeys);
            session.target().putAll(LongStream.range(0, loadedUpTo).boxed().toList());
            assertThat(session.commit()).isTrue();
        }
        running.set(false);
        for (Thread writer : writers) {
            writer.join();
        }
        long written = Math.min(nextKey.get(), maxKeys);
        long lost = LongStream.range(0, written).filter(key -> !big.mightContain(key)).count();
        assertThat(lost).as("keys lost across %d swaps", 20).isZero();
    }
}
