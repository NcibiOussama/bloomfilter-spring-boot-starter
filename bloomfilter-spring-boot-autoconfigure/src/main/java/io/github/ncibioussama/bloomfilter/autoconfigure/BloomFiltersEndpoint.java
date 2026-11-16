package io.github.ncibioussama.bloomfilter.autoconfigure;

import io.github.ncibioussama.bloomfilter.core.BloomFilterSpec;
import io.github.ncibioussama.bloomfilter.core.BloomFilterStats;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

/**
 * Actuator endpoint {@code /actuator/bloomfilters}. Exposes sizing, statistics and rebuild status, never keys.
 * Enable with {@code management.endpoints.web.exposure.include=bloomfilters}.
 * <p>
 * {@code POST /actuator/bloomfilters/{name}} starts a rebuild, but only when
 * {@code bloom.rebuild.endpoint-enabled=true}.
 */
@Endpoint(id = "bloomfilters")
public class BloomFiltersEndpoint {

    private final BloomFilterRegistry registry;
    private final BloomFilterRebuilder rebuilder;
    private final BloomFilterProperties properties;

    /**
     * Read-only endpoint.
     *
     * @param registry the filters
     */
    public BloomFiltersEndpoint(BloomFilterRegistry registry) {
        this(registry, null, null);
    }

    /**
     * @param registry   the filters
     * @param rebuilder  the rebuilder, or {@code null}
     * @param properties the configuration, or {@code null}
     */
    public BloomFiltersEndpoint(BloomFilterRegistry registry, BloomFilterRebuilder rebuilder,
                                BloomFilterProperties properties) {
        this.registry = registry;
        this.rebuilder = rebuilder;
        this.properties = properties;
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

    /**
     * Starts a rebuild in the background.
     *
     * @param name filter name
     * @return the request status, or {@code null} (HTTP 404) if unknown
     */
    @WriteOperation
    public Map<String, Object> rebuild(@Selector String name) {
        if (!registry.names().contains(name)) {
            return null;
        }
        if (rebuilder == null || properties == null || !properties.getRebuild().isEndpointEnabled()) {
            return Map.of("filter", name, "status", "disabled",
                    "hint", "set bloom.rebuild.endpoint-enabled=true and secure the actuator endpoints");
        }
        if (registry.managed(name).isRebuilding()) {
            return Map.of("filter", name, "status", "already-running");
        }
        rebuilder.rebuildAsync(name);
        return Map.of("filter", name, "status", "started");
    }

    private Map<String, Object> describe(ManagedBloomFilter filter) {
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

        Map<String, Object> rebuild = new LinkedHashMap<>();
        rebuild.put("running", filter.isRebuilding());
        rebuild.put("successes", filter.rebuildSuccessCount());
        rebuild.put("failures", filter.rebuildFailureCount());
        rebuild.put("lastSuccess", filter.lastRebuild() == null ? null : filter.lastRebuild().toString());
        BloomFilterProperties.Filter config = properties == null ? null : properties.getFilters().get(filter.name());
        rebuild.put("cron", config == null ? null : config.getRebuildCron());
        d.put("rebuild", rebuild);
        return d;
    }
}
