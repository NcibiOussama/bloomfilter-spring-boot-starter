package io.github.ncibioussama.bloomfilter.autoconfigure;

import io.github.ncibioussama.bloomfilter.core.BloomFilterSpec;
import io.github.ncibioussama.bloomfilter.core.BloomFilterStats;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;

/**
 * Read-only actuator endpoint {@code /actuator/bloomfilters}. Exposes sizing and statistics only,
 * never keys. Enable with {@code management.endpoints.web.exposure.include=bloomfilters}.
 */
@Endpoint(id = "bloomfilters")
public class BloomFiltersEndpoint {

    private final BloomFilterRegistry registry;

    /**
     * @param registry the filters
     */
    public BloomFiltersEndpoint(BloomFilterRegistry registry) {
        this.registry = registry;
    }

    /**
     * @return all filters
     */
    @ReadOperation
    public Map<String, Object> filters() {
        Map<String, Object> filters = new LinkedHashMap<>();
        for (ManagedBloomFilter filter : registry.managed()) {
            filters.put(filter.name(), describe(filter));
        }
        return Map.of("filters", filters);
    }

    /**
     * @param name filter name
     * @return one filter, or {@code null} (HTTP 404) if unknown
     */
    @ReadOperation
    public Map<String, Object> filter(@Selector String name) {
        return registry.names().contains(name) ? describe(registry.managed(name)) : null;
    }

    private static Map<String, Object> describe(ManagedBloomFilter filter) {
        BloomFilterSpec spec = filter.spec();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("backend", filter.backend());
        d.put("state", filter.state().name());
        d.put("expectedInsertions", spec.expectedInsertions());
        d.put("targetFalsePositiveRate", spec.falsePositiveRate());
        d.put("bitSize", spec.bitSize());
        d.put("sizeBytes", spec.bitSize() / 8);
        d.put("hashFunctions", spec.hashFunctions());
        try {
            BloomFilterStats stats = filter.stats();
            d.put("bitsSet", stats.bitsSet());
            d.put("fillRatio", stats.fillRatio());
            d.put("estimatedFalsePositiveRate", stats.estimatedFalsePositiveRate());
            d.put("approximateElementCount", stats.approximateElementCount());
            d.put("overCapacity", stats.overCapacity());
        } catch (RuntimeException e) {
            d.put("statsError", "backend unavailable");
        }
        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("mightContain", filter.mightContainCount());
        counters.put("absent", filter.absentCount());
        counters.put("bypassed", filter.bypassedCount());
        counters.put("puts", filter.putCount());
        counters.put("falsePositives", filter.falsePositiveCount());
        counters.put("backendErrors", filter.backendErrors());
        d.put("counters", counters);
        return d;
    }
}
