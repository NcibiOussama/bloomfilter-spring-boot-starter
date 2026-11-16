package io.github.ncibioussama.bloomfilter.autoconfigure;

import io.github.ncibioussama.bloomfilter.core.BloomFilterStats;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.function.ToDoubleFunction;

/**
 * Publishes Micrometer metrics for every filter, tagged with {@code filter=<name>}.
 * <ul>
 *     <li>{@code bloom.filter.checks} with {@code result} = {@code might_contain}, {@code absent}, {@code bypassed}</li>
 *     <li>{@code bloom.filter.puts}, {@code bloom.filter.false.positives}, {@code bloom.filter.backend.errors}</li>
 *     <li>{@code bloom.filter.fill.ratio}, {@code bloom.filter.estimated.false.positive.rate},
 *         {@code bloom.filter.approximate.elements}, {@code bloom.filter.ready}</li>
 * </ul>
 */
public class BloomFilterMeterBinder implements MeterBinder {

    private static final long STATS_TTL_NANOS = 10_000_000_000L;

    private final BloomFilterRegistry registry;

    /**
     * @param registry the filters to instrument
     */
    public BloomFilterMeterBinder(BloomFilterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void bindTo(MeterRegistry meters) {
        for (ManagedBloomFilter filter : registry.managed()) {
            Tags tags = Tags.of("filter", filter.name(), "backend", filter.backend());

            counter(meters, "bloom.filter.checks", filter, ManagedBloomFilter::mightContainCount,
                    tags.and("result", "might_contain"), "Checks answered 'might contain'");
            counter(meters, "bloom.filter.checks", filter, ManagedBloomFilter::absentCount,
                    tags.and("result", "absent"), "Checks answered 'definitely absent'");
            counter(meters, "bloom.filter.checks", filter, ManagedBloomFilter::bypassedCount,
                    tags.and("result", "bypassed"), "Checks bypassed while warming up or degraded");
            counter(meters, "bloom.filter.puts", filter, ManagedBloomFilter::putCount, tags, "Keys added");
            counter(meters, "bloom.filter.false.positives", filter, ManagedBloomFilter::falsePositiveCount, tags,
                    "Checks that passed the filter but found nothing");
            counter(meters, "bloom.filter.backend.errors", filter, ManagedBloomFilter::backendErrors, tags,
                    "Backend failures");

            CachedStats stats = new CachedStats(filter);
            Gauge.builder("bloom.filter.fill.ratio", stats, s -> s.get(BloomFilterStats::fillRatio))
                    .tags(tags).strongReference(true).description("Fraction of bits set").register(meters);
            Gauge.builder("bloom.filter.estimated.false.positive.rate", stats,
                            s -> s.get(BloomFilterStats::estimatedFalsePositiveRate))
                    .tags(tags).strongReference(true).description("False-positive probability at the current fill").register(meters);
            Gauge.builder("bloom.filter.approximate.elements", stats,
                            s -> s.get(st -> (double) st.approximateElementCount()))
                    .tags(tags).strongReference(true).description("Estimated number of distinct keys").register(meters);
            Gauge.builder("bloom.filter.ready", filter, f -> f.state() == ManagedBloomFilter.State.READY ? 1 : 0)
                    .tags(tags).description("1 when the filter is answering checks, 0 when bypassed")
                    .register(meters);
        }
    }

    private static void counter(MeterRegistry meters, String name, ManagedBloomFilter filter,
                                ToDoubleFunction<ManagedBloomFilter> fn, Tags tags, String description) {
        FunctionCounter.builder(name, filter, fn).tags(tags).description(description).register(meters);
    }

    /** Caches stats so a scrape of several gauges costs at most one backend call per TTL. */
    private static final class CachedStats {

        private final ManagedBloomFilter filter;
        private volatile BloomFilterStats stats;
        private volatile long fetchedAt;

        CachedStats(ManagedBloomFilter filter) {
            this.filter = filter;
        }

        double get(ToDoubleFunction<BloomFilterStats> fn) {
            long now = System.nanoTime();
            BloomFilterStats current = stats;
            if (current == null || now - fetchedAt > STATS_TTL_NANOS) {
                try {
                    current = filter.stats();
                    stats = current;
                    fetchedAt = now;
                } catch (RuntimeException e) {
                    return Double.NaN;
                }
            }
            return fn.applyAsDouble(current);
        }
    }
}
