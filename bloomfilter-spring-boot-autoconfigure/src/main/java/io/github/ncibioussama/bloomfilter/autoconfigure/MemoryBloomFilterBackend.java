package io.github.ncibioussama.bloomfilter.autoconfigure;

import io.github.ncibioussama.bloomfilter.core.BloomFilter;
import io.github.ncibioussama.bloomfilter.core.BloomFilterSpec;
import io.github.ncibioussama.bloomfilter.core.InMemoryBloomFilter;
import io.github.ncibioussama.bloomfilter.core.KeyEncoder;

/**
 * Creates {@link InMemoryBloomFilter} instances.
 */
public class MemoryBloomFilterBackend implements BloomFilterBackend {

    private final KeyEncoder keyEncoder;
    private final BloomFilterProperties.Security security;

    /**
     * @param keyEncoder key encoder
     * @param security   hashing configuration
     */
    public MemoryBloomFilterBackend(KeyEncoder keyEncoder, BloomFilterProperties.Security security) {
        this.keyEncoder = keyEncoder;
        this.security = security;
    }

    @Override
    public BloomFilterProperties.Backend type() {
        return BloomFilterProperties.Backend.MEMORY;
    }

    @Override
    public BloomFilter create(BloomFilterSpec spec, BloomFilterProperties.Filter config) {
        return new InMemoryBloomFilter(spec, HashStrategies.forBackend(type(), security), keyEncoder);
    }
}
