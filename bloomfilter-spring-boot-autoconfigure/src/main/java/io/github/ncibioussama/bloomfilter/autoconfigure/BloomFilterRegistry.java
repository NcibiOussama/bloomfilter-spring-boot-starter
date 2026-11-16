package io.github.ncibioussama.bloomfilter.autoconfigure;

import io.github.ncibioussama.bloomfilter.core.BloomFilter;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * All configured Bloom filters, by name.
 *
 * <pre>
 * BloomFilter users = registry.get("users");
 * if (!users.mightContain(email)) { ... }
 * </pre>
 */
public class BloomFilterRegistry {

    private final Map<String, ManagedBloomFilter> filters;

    BloomFilterRegistry(Map<String, ManagedBloomFilter> filters) {
        this.filters = Collections.unmodifiableMap(new LinkedHashMap<>(filters));
    }

    /**
     * @param name filter name as configured under {@code bloom.filters.<name>}
     * @return the filter
     * @throws IllegalArgumentException if no such filter is configured
     */
    public BloomFilter get(String name) {
        return managed(name);
    }

    /**
     * @param name filter name
     * @return the filter, if configured
     */
    public Optional<BloomFilter> find(String name) {
        return Optional.ofNullable(filters.get(name));
    }

    /**
     * @return names of all configured filters
     */
    public Set<String> names() {
        return filters.keySet();
    }

    /**
     * @return all filters with their management information
     */
    public Collection<ManagedBloomFilter> managed() {
        return filters.values();
    }

    /**
     * @param name filter name
     * @return the filter with its management information
     * @throws IllegalArgumentException if no such filter is configured
     */
    public ManagedBloomFilter managed(String name) {
        ManagedBloomFilter filter = filters.get(name);
        if (filter == null) {
            throw new IllegalArgumentException("No Bloom filter named '" + name + "'. Configured filters: "
                    + filters.keySet() + ". Declare it under bloom.filters." + name + ".*");
        }
        return filter;
    }
}
