package io.github.ncibioussama.bloomfilter.autoconfigure;

import io.github.ncibioussama.bloomfilter.core.BloomFilter;
import io.github.ncibioussama.bloomfilter.core.BloomFilterSpec;

/**
 * Creates filters for one {@link BloomFilterProperties.Backend}.
 */
public interface BloomFilterBackend {

    /**
     * @return the backend type this factory serves
     */
    BloomFilterProperties.Backend type();

    /**
     * @param spec   the sizing
     * @param config the filter configuration
     * @return a new filter
     */
    BloomFilter create(BloomFilterSpec spec, BloomFilterProperties.Filter config);
}
